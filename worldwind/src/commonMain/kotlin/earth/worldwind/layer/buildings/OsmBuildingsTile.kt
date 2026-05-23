package earth.worldwind.layer.buildings

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.DrawableShape
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.BoundingBox
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.layer.shadow.ShadowMode
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.program.TriangleShaderProgram
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.util.IntList
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.NumericArray
import earth.worldwind.util.glu.GLU
import earth.worldwind.util.glu.GLUtessellator
import earth.worldwind.util.glu.GLUtessellatorCallbackAdapter
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_ELEMENT_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_TRIANGLES
import earth.worldwind.util.kgl.GL_UNSIGNED_INT
import kotlin.math.max
import kotlin.math.min

/**
 * Renders every building of one slippy-map tile as a single batched mesh — one VBO + one EBO per
 * tile per [Globe.State], regardless of building count. Drop-in replacement for the legacy
 * "one [earth.worldwind.shape.Polygon] per building" path that OOMs the Adreno KGSL shared-memory
 * pool on dense urban tiles (~500 buildings × 4 buffers × ≥1-page mmap exhausts the kernel's GPU
 * buffer-object slots by *count* long before raw bytes become a concern). Per-tile batching is
 * the approach Cesium 3D Tiles `b3dm`, Mapbox `fill-extrusion`, and deck.gl `SolidPolygonLayer`
 * all use for the same reason.
 *
 * Per-building wall / roof colour ([useOsmColors]) is supported by grouping triangles within the
 * tile's element array by colour and issuing one [DrawShapeState.drawElements] call per colour.
 * Same one-VBO-one-EBO footprint; just more sub-draw calls per tile. A uniform-colour tile (the
 * default) collapses to a single sub-draw.
 *
 * Geometry per building:
 *   - Top cap: tessellated via [GLU] with holes from inner rings.
 *   - Side walls: each edge gets four fresh corner vertices (no sharing across walls or with the
 *     cap) so the fragment shader's dFdx-derived flat normal stays sharp at every face break.
 *   - Bottom cap: only when [OsmBuilding.minHeight] > 0 (floating slab — bridges, podiums).
 *     Ground-rooted buildings have a draping skirt that meets the terrain implicitly.
 *
 * Pickability is intentionally off — schematic scenery, not interactive.
 */
class OsmBuildingsTile(
    private val buildings: List<OsmBuilding>,
    var attributes: ShapeAttributes,
    /**
     * When true, walls are coloured via [OsmColors.resolve] (falling back to
     * [ShapeAttributes.interiorColor] when no tags resolve) and roof caps via `roof:colour`
     * (falling back to the wall colour). When false, every triangle uses [attributes]'s
     * interior colour — a single colour bucket, one sub-draw per tile.
     */
    private val useOsmColors: Boolean = false,
    /**
     * Per-tile shadow participation; overrides [attributes]'s shadow mode so a layer can set
     * shadow behaviour for all its tiles without mutating user-supplied [ShapeAttributes].
     */
    var shadowMode: ShadowMode = ShadowMode.ENABLED,
    displayName: String? = null,
) : AbstractRenderable(displayName) {

    private val data = mutableMapOf<Globe.State?, TileData>()
    private val boundingBox = BoundingBox()
    private var bufferDataVersion = 0
    private val scratchPoint = Vec3()
    private val tessCoords = DoubleArray(3)
    private val vertices = FloatList()
    // Per-colour element buckets — LinkedHashMap so sub-draw order matches insertion order. Active
    // bucket switches via [selectColor] before each face; the tess callback writes to it implicitly.
    private val perColorElements = LinkedHashMap<Int, IntList>()
    private var currentElements: IntList = IntList()
    private val vertexOrigin = Vec3()
    // [topIndexBase] is the absolute index of the FIRST cap-corner vertex of the current building;
    // the tess callback receives a per-cap ordinal (0..n-1) as vertexData and resolves the global
    // index via `topIndexBase + ordinal`. [tessTriIdx] accumulates three IDs before emitting one
    // triangle.
    private var topIndexBase = 0
    private val tessTriIdx = IntArray(3)
    private var tessTriCount = 0
    // True for the bottom cap: GLU emits CCW-from-above regardless of [GLU.gluTessNormal], so we
    // reverse the winding here to make the bottom face down.
    private var tessSwapWinding = false
    // RC + altitude in scope during one [runTess] call so [combineData] can resolve synthesised
    // vertices back to local Cartesian — without these the combined vertex would end up at
    // (lon°, lat°, z) interpreted as local floats, collapsing to a sliver near vertexOrigin and
    // looking like dropped roof triangles.
    private var tessRc: RenderContext? = null
    private var tessCapAlt: Double = 0.0
    private val tessCallback = object : GLUtessellatorCallbackAdapter() {
        override fun vertexData(vertexData: Any?, polygonData: Any?) {
            tessTriIdx[tessTriCount++] = topIndexBase + (vertexData as Int)
            if (tessTriCount == 3) {
                tessTriCount = 0
                if (tessSwapWinding) emitTriangle(tessTriIdx[0], tessTriIdx[2], tessTriIdx[1])
                else emitTriangle(tessTriIdx[0], tessTriIdx[1], tessTriIdx[2])
            }
        }

        override fun combineData(
            coords: DoubleArray, data: Array<Any?>, weight: FloatArray, outData: Array<Any?>, polygonData: Any?
        ) {
            // GLU invokes combine on intersecting / coincident vertices. coords come back in the
            // (lon°, lat°) space we fed gluTessVertex, so convert to local Cartesian via the same
            // helper used for original cap corners. Altitude is uniform per cap (single terrain
            // sample), so [tessCapAlt] suffices.
            val rc = tessRc ?: return
            val lon = coords[0].degrees
            val lat = coords[1].degrees
            val newIdx = pushLocalVertex(rc, lat, lon, tessCapAlt)
            outData[0] = newIdx - topIndexBase
        }

        override fun errorData(errnum: Int, polygonData: Any?) {
            // Most GLU errors on OSM input are recoverable warnings (self-intersecting rings,
            // near-coincident corners). Match [earth.worldwind.shape.Polygon] and keep whatever
            // triangles GLU did emit rather than rewinding the bucket — wholesale-dropping every
            // building with a stray warning produced visible holes across the scene.
            logMessage(WARN, "OsmBuildingsTile", "runTess",
                "GLU error $errnum: ${GLU.gluErrorString(errnum)}")
        }

        // Registering edgeFlag forces GLU to emit GL_TRIANGLES exclusively. Without it GLU may
        // choose GL_TRIANGLE_FAN / GL_TRIANGLE_STRIP for some inputs — and the [vertexData] loop
        // here treats every three vertices as an independent triangle, mangling strip/fan output
        // and leaving holes in the roof of certain buildings.
        override fun edgeFlagData(boundaryEdge: Boolean, polygonData: Any?) { /* no-op */ }
    }

    init { isPickEnabled = false }

    /**
     * Per-globe-state geometry cache. [elementArray] is ordered by colour bucket so each
     * [ColorRange] in [colorRanges] is a contiguous slice; [vertexArray] is shared across buckets.
     */
    private class TileData {
        val vertexOrigin = Vec3()
        var vertexArray = FloatArray(0)
        var elementArray = IntArray(0)
        var colorRanges: List<ColorRange> = emptyList()
        val vertexBufferKey = Any()
        val elementBufferKey = Any()
        var refreshGeometry = true
    }

    private class ColorRange(val colorPacked: Int, val offset: Int, val count: Int)

    override fun doRender(rc: RenderContext) {
        val tileData = data.getOrPut(rc.globeState) { TileData() }
        if (tileData.refreshGeometry) {
            assembleGeometry(rc, tileData)
            tileData.refreshGeometry = false
        }
        if (tileData.vertexArray.isEmpty()) return
        if (!boundingBox.intersectsFrustum(rc.frustum)) return

        // One sub-draw per colour, chunked across [DrawableShape] instances when colours exceed
        // [DrawShapeState.MAX_DRAW_ELEMENTS]. Every chunk references the same VBO + EBO via the
        // tile's buffer keys — extra drawables do NOT add GL buffers.
        val ranges = tileData.colorRanges
        if (ranges.isEmpty()) return
        val cameraDistanceSq = computeCameraDistanceSq(rc)
        var chunkStart = 0
        while (chunkStart < ranges.size) {
            val chunkEnd = min(chunkStart + DrawShapeState.MAX_DRAW_ELEMENTS, ranges.size)
            emitDrawable(rc, tileData, ranges, chunkStart, chunkEnd, cameraDistanceSq)
            chunkStart = chunkEnd
        }
    }

    private fun emitDrawable(
        rc: RenderContext, tileData: TileData, ranges: List<ColorRange>,
        from: Int, to: Int, cameraDistanceSq: Double,
    ) {
        val pool = rc.getDrawablePool(DrawableShape.KEY)
        val drawable = DrawableShape.obtain(pool)
        val drawState = drawable.drawState
        drawState.program = TriangleShaderProgram.get(rc)

        drawState.vertexBuffer = rc.getBufferObject(tileData.vertexBufferKey) {
            BufferObject(GL_ARRAY_BUFFER, 0)
        }
        rc.offerGLBufferUpload(tileData.vertexBufferKey, bufferDataVersion) {
            NumericArray.Floats(tileData.vertexArray)
        }
        drawState.elementBuffer = rc.getBufferObject(tileData.elementBufferKey) {
            BufferObject(GL_ELEMENT_ARRAY_BUFFER, 0)
        }
        rc.offerGLBufferUpload(tileData.elementBufferKey, bufferDataVersion) {
            NumericArray.Ints(tileData.elementArray)
        }

        drawState.opacity = rc.currentLayer.opacity
        drawState.vertexOrigin.copy(tileData.vertexOrigin)
        drawState.boundingCenter.copy(boundingBox.center)
        drawState.boundingRadius = boundingBox.radius
        drawState.vertexStride = VERTEX_STRIDE * Float.SIZE_BYTES
        drawState.enableCullFace = true
        drawState.enableDepthTest = attributes.isDepthTest
        drawState.enableLighting = attributes.isLightingEnabled
        drawState.shadowMode = shadowMode
        // texCoordAttrib.size must be ≥ 1 (GL rejects 0); reuse the position floats — texCoord is
        // only read when enableTexture is true, which it isn't here.
        drawState.texCoordAttrib.size = 1
        drawState.texCoordAttrib.offset = 0

        // Each [DrawShapeState.drawElements] call captures [drawState.color] at call time, so we
        // set the colour then draw, and the prim records that colour.
        var anyOpaque = false
        for (i in from until to) {
            val range = ranges[i]
            unpackColorInto(range.colorPacked, drawState.color)
            if (drawState.color.alpha * drawState.opacity >= 1f) anyOpaque = true
            drawState.drawElements(
                GL_TRIANGLES, range.count, GL_UNSIGNED_INT, range.offset * Int.SIZE_BYTES,
            )
        }
        drawState.enableDepthWrite = anyOpaque

        rc.offerShapeDrawable(drawable, cameraDistanceSq)
    }

    private fun computeCameraDistanceSq(rc: RenderContext): Double {
        val c = boundingBox.center
        val dx = c.x - rc.cameraPoint.x
        val dy = c.y - rc.cameraPoint.y
        val dz = c.z - rc.cameraPoint.z
        return dx * dx + dy * dy + dz * dz
    }

    private fun assembleGeometry(rc: RenderContext, tileData: TileData) {
        ++bufferDataVersion
        vertices.clear()
        perColorElements.clear()

        // Anchor the tile-local origin at the first valid building's first corner so per-vertex
        // floats stay small relative to ECEF magnitudes. Skip the tile if no building has a
        // viable outer ring.
        val anchor = buildings.firstOrNull { it.outerRing.size >= 3 }?.outerRing?.get(0)
        if (anchor == null) {
            tileData.vertexArray = FloatArray(0)
            tileData.elementArray = IntArray(0)
            tileData.colorRanges = emptyList()
            return
        }
        rc.geographicToCartesian(
            anchor.latitude, anchor.longitude, 0.0, AltitudeMode.ABSOLUTE, vertexOrigin, useEM = true,
        )
        tileData.vertexOrigin.copy(vertexOrigin)

        val defaultWallColorPacked = packColor(attributes.interiorColor)
        for (b in buildings) {
            if (b.outerRing.size < 3) continue
            assembleBuilding(rc, b, defaultWallColorPacked)
        }

        // Concatenate per-colour buckets into the final element array, recording offset/count per
        // colour. Single EBO; the colour switch is just a uniform change between sub-draws.
        val totalElements = perColorElements.values.sumOf { it.size }
        val finalElements = IntArray(totalElements)
        val ranges = ArrayList<ColorRange>(perColorElements.size)
        var offset = 0
        for ((colorPacked, list) in perColorElements) {
            if (list.size == 0) continue
            list.copyTo(finalElements, offset)
            ranges.add(ColorRange(colorPacked, offset, list.size))
            offset += list.size
        }
        tileData.vertexArray = vertices.toFloatArray()
        tileData.elementArray = finalElements
        tileData.colorRanges = ranges

        boundingBox.setToPoints(tileData.vertexArray, tileData.vertexArray.size, VERTEX_STRIDE)
        boundingBox.translate(tileData.vertexOrigin.x, tileData.vertexOrigin.y, tileData.vertexOrigin.z)

        // Drop the working buffers — otherwise they pin ~the same memory as the final arrays.
        vertices.shrink()
        perColorElements.clear()
    }

    private fun assembleBuilding(rc: RenderContext, b: OsmBuilding, defaultWallColorPacked: Int) {
        // Single-sample terrain anchor matches Polygon.isPlanar: keeps tower-on-podium tops and
        // skirts coplanar on sloped ground without a per-vertex elevation walk.
        val anchor = b.outerRing[0]
        val groundAlt = rc.globe.getElevation(anchor.latitude, anchor.longitude)
        val topAlt = groundAlt + b.height
        val baseAlt = groundAlt + b.minHeight

        val wallColorPacked = if (useOsmColors) {
            OsmColors.resolve(b.tags)?.let { packColor(it) } ?: defaultWallColorPacked
        } else defaultWallColorPacked
        val roofColorPacked = if (useOsmColors) {
            OsmColors.parseColor(b.tags["roof:colour"])?.let { packColor(it) } ?: wallColorPacked
        } else wallColorPacked

        selectColor(wallColorPacked)
        emitRingGeometry(rc, b.outerRing, topAlt, baseAlt, isHole = false)
        for (hole in b.innerRings) {
            if (hole.size >= 3) emitRingGeometry(rc, hole, topAlt, baseAlt, isHole = true)
        }
        if (b.minHeight > 0.0) emitCap(rc, b, baseAlt, isTop = false)
        selectColor(roofColorPacked)
        emitCap(rc, b, topAlt, isTop = true)
    }

    private fun selectColor(colorPacked: Int) {
        currentElements = perColorElements.getOrPut(colorPacked) { IntList() }
    }

    private fun emitTriangle(v0: Int, v1: Int, v2: Int) {
        currentElements.add(v0); currentElements.add(v1); currentElements.add(v2)
    }

    /**
     * Four fresh corner vertices per edge so adjacent walls each get their own dFdx-derived
     * normal. For holes the winding is reversed so the wall faces inward into the courtyard.
     */
    private fun emitRingGeometry(
        rc: RenderContext, ring: List<Position>, topAlt: Double, baseAlt: Double, isHole: Boolean,
    ) {
        val n = ringCount(ring)
        if (n < 2) return
        for (i in 0 until n) {
            val a = ring[i]
            val b = ring[(i + 1) % n]
            val topAIdx = pushLocalVertex(rc, a.latitude, a.longitude, topAlt)
            val topBIdx = pushLocalVertex(rc, b.latitude, b.longitude, topAlt)
            val botAIdx = pushLocalVertex(rc, a.latitude, a.longitude, baseAlt)
            val botBIdx = pushLocalVertex(rc, b.latitude, b.longitude, baseAlt)
            if (!isHole) {
                // CCW outer ring: outward = right-of-(a→b). Verify via right-hand rule on the
                // first edge of any CCW-from-above building.
                emitTriangle(topAIdx, botAIdx, topBIdx)
                emitTriangle(topBIdx, botAIdx, botBIdx)
            } else {
                // CW hole: mirror the winding so courtyard walls face into the void.
                emitTriangle(topAIdx, topBIdx, botAIdx)
                emitTriangle(topBIdx, botBIdx, botAIdx)
            }
        }
    }

    /**
     * Emits one cap (top or bottom). Lays out outer-ring then inner-ring corner vertices
     * contiguously, sets [topIndexBase] for the [tessCallback], and runs GLU over the contours.
     */
    private fun emitCap(rc: RenderContext, b: OsmBuilding, alt: Double, isTop: Boolean) {
        val outer = b.outerRing
        val n = ringCount(outer)
        if (n < 3) return
        topIndexBase = vertices.size / VERTEX_STRIDE
        for (i in 0 until n) pushLocalVertex(rc, outer[i].latitude, outer[i].longitude, alt)
        val holeBases = IntArray(b.innerRings.size)
        for ((hi, hole) in b.innerRings.withIndex()) {
            val m = ringCount(hole)
            holeBases[hi] = (vertices.size / VERTEX_STRIDE) - topIndexBase
            if (m >= 3) for (i in 0 until m) {
                pushLocalVertex(rc, hole[i].latitude, hole[i].longitude, alt)
            }
        }
        runTess(rc, outer, b.innerRings, holeBases, alt, isTop)
    }

    /**
     * Feeds outer + inner contours to GLU and collects emitted triangles into the active colour
     * bucket via [tessCallback]. [tessRc] / [tessCapAlt] stay populated so [combineData] can
     * convert synthesised (lon, lat) vertices back to local Cartesian.
     */
    private fun runTess(
        rc: RenderContext, outer: List<Position>, innerRings: List<List<Position>>,
        holeBases: IntArray, capAlt: Double, isTop: Boolean,
    ) {
        val tess = rc.tessellator
        tessTriCount = 0
        tessSwapWinding = !isTop
        tessRc = rc
        tessCapAlt = capAlt

        // Same projection normal for both caps — the input contours are the same XY contour at
        // different Z. Bottom cap face direction comes from [tessSwapWinding].
        GLU.gluTessNormal(tess, 0.0, 0.0, 1.0)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_VERTEX_DATA, tessCallback)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_COMBINE_DATA, tessCallback)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_ERROR_DATA, tessCallback)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_EDGE_FLAG_DATA, tessCallback)
        GLU.gluTessBeginPolygon(tess, this)

        feedContour(tess, outer, baseOrdinal = 0)
        for ((hi, hole) in innerRings.withIndex()) {
            if (hole.size >= 3) feedContour(tess, hole, baseOrdinal = holeBases[hi])
        }

        GLU.gluTessEndPolygon(tess)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_VERTEX_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_COMBINE_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_ERROR_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_EDGE_FLAG_DATA, null)
        tessRc = null
    }

    /**
     * Streams one ring's corners as 2D (lon, lat) coords. [baseOrdinal] starts at 0 for the outer
     * ring; each hole starts at its own [holeBases] offset so the tess callback can map ordinal
     * back to absolute vertex index via `topIndexBase + ordinal`.
     */
    private fun feedContour(tess: GLUtessellator, ring: List<Position>, baseOrdinal: Int) {
        val n = ringCount(ring)
        GLU.gluTessBeginContour(tess)
        for (i in 0 until n) {
            val p = ring[i]
            tessCoords[0] = p.longitude.inDegrees
            tessCoords[1] = p.latitude.inDegrees
            tessCoords[2] = 0.0
            GLU.gluTessVertex(tess, tessCoords, 0, baseOrdinal + i)
        }
        GLU.gluTessEndContour(tess)
    }

    /**
     * OSM rings commonly close (last == first); counting the duplicate would shift our
     * `topIndexBase + ordinal` resolution by one. Use this everywhere we walk the ring.
     */
    private fun ringCount(ring: List<Position>): Int {
        val n = ring.size
        if (n < 2) return n
        return if (ring[0].latitude == ring[n - 1].latitude
            && ring[0].longitude == ring[n - 1].longitude) n - 1 else n
    }

    private fun pushLocalVertex(rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double): Int {
        rc.geographicToCartesian(latitude, longitude, altitude, AltitudeMode.ABSOLUTE, scratchPoint, useEM = true)
        val idx = vertices.size / VERTEX_STRIDE
        vertices.add((scratchPoint.x - vertexOrigin.x).toFloat())
        vertices.add((scratchPoint.y - vertexOrigin.y).toFloat())
        vertices.add((scratchPoint.z - vertexOrigin.z).toFloat())
        return idx
    }

    /**
     * Drops this tile's GL buffer entries from [RenderResourceCache] for every cached globe state.
     * Called by [OsmBuildingsLayer] on LRU eviction so the kernel reclaims GPU pages eagerly
     * instead of waiting for renderResourceCache pressure.
     */
    fun releaseRenderResources(rc: RenderContext) {
        for (td in data.values) {
            rc.renderResourceCache.remove(td.vertexBufferKey)
            rc.renderResourceCache.remove(td.elementBufferKey)
        }
    }

    companion object {
        // x, y, z local-space float — matches [TriangleShaderProgram]'s one-vertex-mode layout.
        private const val VERTEX_STRIDE = 3

        /** Pack RGBA floats [0, 1] into one Int (R | G | B | A), 8 bits per channel. */
        private fun packColor(c: Color): Int {
            val r = (c.red * 255f).toInt().coerceIn(0, 255)
            val g = (c.green * 255f).toInt().coerceIn(0, 255)
            val b = (c.blue * 255f).toInt().coerceIn(0, 255)
            val a = (c.alpha * 255f).toInt().coerceIn(0, 255)
            return (r shl 24) or (g shl 16) or (b shl 8) or a
        }

        private fun unpackColorInto(packed: Int, out: Color) {
            val r = ((packed ushr 24) and 0xFF) / 255f
            val g = ((packed ushr 16) and 0xFF) / 255f
            val b = ((packed ushr 8) and 0xFF) / 255f
            val a = (packed and 0xFF) / 255f
            out.set(r, g, b, a)
        }
    }
}

/**
 * Primitive-Float dynamic list. Mirrors [IntList] for hot vertex accumulation where boxing each
 * `Float` would dominate assembly time on dense tiles.
 */
private class FloatList(initialCapacity: Int = 64) {
    private var data: FloatArray = FloatArray(max(initialCapacity, 0))
    var size: Int = 0
        private set

    operator fun get(index: Int): Float = data[index]

    fun add(value: Float) {
        if (size == data.size) grow(size + 1)
        data[size++] = value
    }

    fun clear() { size = 0 }

    fun shrink() {
        data = FloatArray(0)
        size = 0
    }

    fun toFloatArray(): FloatArray = data.copyOf(size)

    private fun grow(minCapacity: Int) {
        val current = data.size
        val target = max(minCapacity, current + max(current shr 1, 64))
        data = data.copyOf(target)
    }
}
