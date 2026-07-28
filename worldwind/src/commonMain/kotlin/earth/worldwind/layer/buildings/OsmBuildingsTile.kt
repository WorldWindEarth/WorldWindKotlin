package earth.worldwind.layer.buildings

import earth.worldwind.PickedObject
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
import earth.worldwind.util.FloatList
import earth.worldwind.util.IntList
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.NumericArray
import earth.worldwind.util.glu.GLU
import earth.worldwind.util.glu.GLUtessellatorCallbackAdapter
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_ELEMENT_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_TRIANGLES
import earth.worldwind.util.kgl.GL_UNSIGNED_INT
import kotlin.math.min
import kotlin.time.TimeSource

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
 * Geometry per building follows the OSM "Simple 3D Buildings" convention:
 *   - Top cap: flat horizontal slab tessellated via [GLU] (with holes from inner rings) at
 *     `max(corner ground) + height` so the roof clears every footprint corner.
 *   - Side walls: each edge gets four fresh corner vertices (no sharing across walls or with the
 *     cap) so the fragment shader's dFdx-derived flat normal stays sharp at every face break.
 *     Ground-rooted walls drape their base to per-corner local terrain — the wall meets the
 *     ground at each corner regardless of slope.
 *   - Bottom cap: only when [OsmBuilding.minHeight] > 0 (floating slab — bridges, podiums). For
 *     floating buildings the wall base is also planar at `max(corner ground) + minHeight` so the
 *     slab stays coplanar with the floor cap.
 *
 * Per-building picking: in pick mode each [OsmBuilding] gets its own pick id and one
 * [DrawShapeState.drawElements] sub-draw. A separate building-major element buffer
 * ([TileData.pickElementArray]) backs this; the visual EBO stays colour-major. Click hits
 * resolve to the [OsmBuilding] via [PickedObject.userObject].
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

    // Per the OSM "Simple 3D Buildings" convention, when a `building=*` outline has any
    // `building:part=*` inside it, the outline is a grouping feature and the parts replace its
    // volume. Skip such outlines once at construction — keeping them produces coplanar roof
    // surfaces (outline roof + covering part roof at the same height) whose Z-fight only resolves
    // cleanly on 24-bit-depth targets; on 16-bit-depth defaults (notably the WebGL default
    // framebuffer) the two roofs flicker per-pixel.
    private val effectiveBuildings: List<OsmBuilding> = filterRedundantOutlines(buildings)
    // Footprint 2D triangulations, index-aligned with [effectiveBuildings]. Computed ONCE at
    // construction — which [OsmBuildingsLayer.fetch] runs on Dispatchers.Default — because the
    // triangulation depends only on the (lon, lat) contours, never on terrain, projection or
    // vertical exaggeration. GLU tessellation dominated [assembleGeometry] (~60% in profiling),
    // and running it lazily on first render stacked ~300 ms of triangulation onto single
    // Choreographer ticks when a cache burst delivered a whole tile window at once; it also
    // re-ran on every DEM/VE re-tessellation for no reason. [assembleGeometry] now only maps the
    // cached topology through per-corner terrain sampling and geographicToCartesian.
    private val capMeshes: List<CapMesh?> = tessellateFootprints(effectiveBuildings)
    private val data = mutableMapOf<Globe.State?, TileData>()
    private val boundingBox = BoundingBox()
    private var bufferDataVersion = 0
    private val scratchPoint = Vec3()
    private val vertices = FloatList()
    // Per-colour element buckets — LinkedHashMap so sub-draw order matches insertion order. Active
    // bucket switches via [selectColor] before each face; [emitCap] writes to it implicitly.
    private val perColorElements = LinkedHashMap<Int, IntList>()
    private var currentElements: IntList = IntList()
    // Building-major mirror of [perColorElements] — feeds the pick EBO so each building's
    // triangles end up contiguous. [emitTriangle] writes to both views.
    private val pickElements = IntList()
    private val vertexOrigin = Vec3()

    /**
     * One footprint's cached triangulation. [triangles] holds cap-local ordinals: `0 until
     * cornerCount` are ring corners in push order (outer ring then each hole with ≥ 3 distinct
     * corners, closing duplicates dropped — see [ringCount]); `cornerCount + k` is the k-th
     * GLU-combined vertex, whose (lon°, lat°) pair sits at [combinedLonLat][2k]. Triangles are
     * stored top-cap winding (CCW from above); the bottom cap reverses at emit time.
     */
    private class CapMesh(val triangles: IntArray, val combinedLonLat: DoubleArray, val cornerCount: Int)

    /**
     * Per-globe-state geometry cache. [elementArray] is colour-major (one [ColorRange] per bucket);
     * [pickElementArray] holds the same triangle indices reordered building-major (one [PickRange]
     * per building). Both share the same [vertexArray] / VBO.
     */
    private class TileData {
        val vertexOrigin = Vec3()
        var vertexArray = FloatArray(0)
        var elementArray = IntArray(0)
        var colorRanges: List<ColorRange> = emptyList()
        var pickElementArray = IntArray(0)
        var pickRanges: List<PickRange> = emptyList()
        val vertexBufferKey = Any()
        val elementBufferKey = Any()
        val pickElementBufferKey = Any()
        var refreshGeometry = true
        var lastTimestamp = 0L
        var lastVE = 0.0
        var groundSignature = 0L
    }

    private class ColorRange(val colorPacked: Int, val offset: Int, val count: Int)
    private class PickRange(val building: OsmBuilding, val offset: Int, val count: Int)

    override fun doRender(rc: RenderContext) {
        val tileData = data.getOrPut(rc.globeState) { TileData() }
        // Re-tessellate when the cached geometry is stale: first frame, a new DEM coverage landed
        // (per-vertex baked-in ground altitude is out of date), or vertical exaggeration changed
        // (Globe.geographicToCartesian scales every altitude by VE). [rc.globeState] keying already
        // separates 2D / projection variants. AbstractShape.checkTerrainState uses the same pattern.
        val timestamp = rc.elevationModelTimestamp
        val ve = rc.globe.verticalExaggeration
        var needsAssembly = tileData.refreshGeometry || ve != tileData.lastVE
        if (!needsAssembly && timestamp != tileData.lastTimestamp) {
            if (computeGroundSignature(rc) != tileData.groundSignature) needsAssembly = true
            else tileData.lastTimestamp = timestamp // ground unchanged — absorb the tick, no re-tess
        }
        // Charge the per-frame assembly budget so a DEM update that invalidates every visible tile
        // (potentially thousands of buildings across a city) spreads its re-tessellation across
        // several frames instead of stalling one Choreographer tick. Budget-exhausted tiles fall
        // through with last frame's geometry (or nothing on the first frame); [canAssembleGeometry]
        // schedules the follow-up redraw. Same pattern as AbstractShape.prepareGeometry.
        if (needsAssembly && rc.canAssembleGeometry()) {
            val mark = TimeSource.Monotonic.markNow()
            assembleGeometry(rc, tileData)
            rc.recordAssemblyTime(mark.elapsedNow().inWholeNanoseconds)
            tileData.refreshGeometry = false
            tileData.lastTimestamp = timestamp
            tileData.lastVE = ve
            tileData.groundSignature = computeGroundSignature(rc)
        }
        if (tileData.vertexArray.isEmpty()) return
        if (!boundingBox.intersectsFrustum(rc.frustum)) return

        val cameraDistanceSq = computeCameraDistanceSq(rc)
        if (rc.isPickMode) {
            // Pick pass: one sub-draw per building using the building-major pick EBO. Each
            // building gets its own [rc.nextPickedObjectId].
            val ranges = tileData.pickRanges
            if (ranges.isEmpty()) return
            var chunkStart = 0
            while (chunkStart < ranges.size) {
                val chunkEnd = min(chunkStart + DrawShapeState.MAX_DRAW_ELEMENTS, ranges.size)
                emitPickDrawable(rc, tileData, ranges, chunkStart, chunkEnd, cameraDistanceSq)
                chunkStart = chunkEnd
            }
            return
        }

        // One sub-draw per colour, chunked across [DrawableShape] instances when colours exceed
        // [DrawShapeState.MAX_DRAW_ELEMENTS]. Every chunk references the same VBO + EBO via the
        // tile's buffer keys — extra drawables do NOT add GL buffers.
        val ranges = tileData.colorRanges
        if (ranges.isEmpty()) return
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

    private fun emitPickDrawable(
        rc: RenderContext, tileData: TileData, ranges: List<PickRange>,
        from: Int, to: Int, cameraDistanceSq: Double,
    ) {
        val pool = rc.getDrawablePool(DrawableShape.KEY)
        val drawable = DrawableShape.obtain(pool)
        val drawState = drawable.drawState
        drawState.program = TriangleShaderProgram.get(rc)

        // Shared VBO with the visual pass; pick has its own EBO so the building-major order
        // here doesn't affect the colour-major draw path.
        drawState.vertexBuffer = rc.getBufferObject(tileData.vertexBufferKey) {
            BufferObject(GL_ARRAY_BUFFER, 0)
        }
        rc.offerGLBufferUpload(tileData.vertexBufferKey, bufferDataVersion) {
            NumericArray.Floats(tileData.vertexArray)
        }
        drawState.elementBuffer = rc.getBufferObject(tileData.pickElementBufferKey) {
            BufferObject(GL_ELEMENT_ARRAY_BUFFER, 0)
        }
        rc.offerGLBufferUpload(tileData.pickElementBufferKey, bufferDataVersion) {
            NumericArray.Ints(tileData.pickElementArray)
        }

        drawState.opacity = 1f
        drawState.vertexOrigin.copy(tileData.vertexOrigin)
        drawState.boundingCenter.copy(boundingBox.center)
        drawState.boundingRadius = boundingBox.radius
        drawState.vertexStride = VERTEX_STRIDE * Float.SIZE_BYTES
        drawState.enableCullFace = true
        drawState.enableDepthTest = attributes.isDepthTest
        drawState.enableLighting = false
        drawState.shadowMode = ShadowMode.DISABLED
        drawState.enableDepthWrite = true
        drawState.texCoordAttrib.size = 1
        drawState.texCoordAttrib.offset = 0

        val layer = rc.currentLayer
        for (i in from until to) {
            val range = ranges[i]
            if (range.count == 0) continue
            val pickedObjectId = rc.nextPickedObjectId()
            PickedObject.identifierToUniqueColor(pickedObjectId, drawState.color)
            drawState.drawElements(
                GL_TRIANGLES, range.count, GL_UNSIGNED_INT, range.offset * Int.SIZE_BYTES,
            )
            rc.offerPickedObject(PickedObject.fromUserObject(pickedObjectId, range.building, layer))
        }

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
        pickElements.clear()
        val pickRangesTmp = ArrayList<PickRange>(effectiveBuildings.size)

        // Anchor the tile-local origin at the first valid building's first corner so per-vertex
        // floats stay small relative to ECEF magnitudes. Skip the tile if no building has a
        // viable outer ring.
        val anchor = effectiveBuildings.firstOrNull { it.outerRing.size >= 3 }?.outerRing?.get(0)
        if (anchor == null) {
            tileData.vertexArray = FloatArray(0)
            tileData.elementArray = IntArray(0)
            tileData.colorRanges = emptyList()
            tileData.pickElementArray = IntArray(0)
            tileData.pickRanges = emptyList()
            return
        }
        rc.geographicToCartesian(
            anchor.latitude, anchor.longitude, 0.0, AltitudeMode.ABSOLUTE, vertexOrigin, useEM = true,
        )
        tileData.vertexOrigin.copy(vertexOrigin)

        val defaultWallColorPacked = packColor(attributes.interiorColor)
        for ((bi, b) in effectiveBuildings.withIndex()) {
            if (b.outerRing.size < 3) continue
            val pickStart = pickElements.size
            assembleBuilding(rc, b, capMeshes[bi], defaultWallColorPacked)
            val pickCount = pickElements.size - pickStart
            if (pickCount > 0) pickRangesTmp.add(PickRange(b, pickStart, pickCount))
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
        tileData.pickElementArray = pickElements.toIntArray()
        tileData.pickRanges = pickRangesTmp

        boundingBox.setToPoints(tileData.vertexArray, tileData.vertexArray.size, VERTEX_STRIDE)
        boundingBox.translate(tileData.vertexOrigin.x, tileData.vertexOrigin.y, tileData.vertexOrigin.z)

        // Drop the working buffers — otherwise they pin ~the same memory as the final arrays.
        vertices.shrink()
        perColorElements.clear()
        pickElements.clear()
    }

    /**
     * Rolling hash of the ground elevation under one representative corner of each building.
     * Used only to decide whether a DEM tick actually moved this tile's terrain (see [doRender]),
     * NOT to seat geometry — so a single sample per footprint is enough: elevation tiles load at a
     * far coarser granularity than a building, so when this tile's DEM arrives every building's
     * sample moves together. One getElevation per building (cache lookup + bilinear; no projection,
     * no tessellation). A missed change only costs a slightly-late re-tessellation, never wrong
     * output, and `refreshGeometry` / VE changes re-assemble unconditionally.
     */
    private fun computeGroundSignature(rc: RenderContext): Long {
        var sig = 1125899906842597L // large-prime seed
        for (b in effectiveBuildings) {
            if (b.outerRing.size < 3) continue
            val p = b.outerRing[0]
            sig = 31L * sig + rc.globe.getElevation(p.latitude, p.longitude).toRawBits()
        }
        return sig
    }

    private fun assembleBuilding(rc: RenderContext, b: OsmBuilding, mesh: CapMesh?, defaultWallColorPacked: Int) {
        // Per-corner terrain samples implement OSM "Simple 3D Buildings" on sloped ground:
        // ground-rooted walls drape their bases to local elevation at each footprint corner so
        // the building meets the terrain everywhere (a single anchor sample leaves downhill
        // corners flying above ground). Roofs — and floors of [minHeight] > 0 floating slabs —
        // remain planar; their reference altitude is the MAX corner elevation so the slab clears
        // every corner of its footprint by the requested height instead of disappearing into the
        // hill on the high side.
        val outer = b.outerRing
        val outerGroundAlts = DoubleArray(outer.size)
        var maxGround = Double.NEGATIVE_INFINITY
        for (i in outer.indices) {
            val alt = rc.globe.getElevation(outer[i].latitude, outer[i].longitude)
            outerGroundAlts[i] = alt
            if (alt > maxGround) maxGround = alt
        }
        val innerGroundAlts = Array(b.innerRings.size) { hi ->
            val hole = b.innerRings[hi]
            DoubleArray(hole.size) { i ->
                val alt = rc.globe.getElevation(hole[i].latitude, hole[i].longitude)
                if (alt > maxGround) maxGround = alt
                alt
            }
        }
        val isFloating = b.minHeight > 0.0
        val topAlt = maxGround + b.height
        val floatingFloorAlt = maxGround + b.minHeight

        val wallColorPacked = if (useOsmColors) {
            OsmColors.resolve(b.tags)?.let { packColor(it) } ?: defaultWallColorPacked
        } else defaultWallColorPacked
        val roofColorPacked = if (useOsmColors) {
            OsmColors.parseColor(b.tags["roof:colour"])?.let { packColor(it) } ?: wallColorPacked
        } else wallColorPacked

        selectColor(wallColorPacked)
        emitRingGeometry(rc, outer, outerGroundAlts, topAlt, floatingFloorAlt, isFloating, isHole = false)
        for ((hi, hole) in b.innerRings.withIndex()) {
            if (hole.size >= 3) emitRingGeometry(
                rc, hole, innerGroundAlts[hi], topAlt, floatingFloorAlt, isFloating, isHole = true,
            )
        }
        if (isFloating) emitCap(rc, b, mesh, floatingFloorAlt, isTop = false)
        selectColor(roofColorPacked)
        emitCap(rc, b, mesh, topAlt, isTop = true)
    }

    private fun selectColor(colorPacked: Int) {
        currentElements = perColorElements.getOrPut(colorPacked) { IntList() }
    }

    // Mirror every triangle into [pickElements] so the building-major pick EBO is built in lockstep
    // with the colour-major visual EBO. Both consume from the same VBO, so the indices are the same.
    private fun emitTriangle(v0: Int, v1: Int, v2: Int) {
        currentElements.add(v0); currentElements.add(v1); currentElements.add(v2)
        pickElements.add(v0); pickElements.add(v1); pickElements.add(v2)
    }

    /**
     * Four fresh corner vertices per edge so adjacent walls each get their own dFdx-derived
     * normal. For holes the winding is reversed so the wall faces inward into the courtyard.
     *
     * [groundAlts] is the per-corner terrain elevation for [ring]; ground-rooted walls
     * ([isFloating] = false) drape their base to that elevation at each corner. Floating
     * slabs use [floatingFloorAlt] for both endpoints so the wall bottom stays coplanar
     * with the floor cap.
     */
    private fun emitRingGeometry(
        rc: RenderContext, ring: List<Position>, groundAlts: DoubleArray,
        topAlt: Double, floatingFloorAlt: Double, isFloating: Boolean, isHole: Boolean,
    ) {
        val n = ringCount(ring)
        if (n < 2) return
        for (i in 0 until n) {
            val a = ring[i]
            val b = ring[(i + 1) % n]
            val baseAltA = if (isFloating) floatingFloorAlt else groundAlts[i]
            val baseAltB = if (isFloating) floatingFloorAlt else groundAlts[(i + 1) % n]
            val topAIdx = pushLocalVertex(rc, a.latitude, a.longitude, topAlt)
            val topBIdx = pushLocalVertex(rc, b.latitude, b.longitude, topAlt)
            val botAIdx = pushLocalVertex(rc, a.latitude, a.longitude, baseAltA)
            val botBIdx = pushLocalVertex(rc, b.latitude, b.longitude, baseAltB)
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
     * Emits one cap (top or bottom) from the building's cached [CapMesh]: pushes the corner and
     * GLU-combined vertices at [alt] — in exactly the ordinal order the construction-time
     * tessellation assigned — then replays the cached triangles. No tessellation happens here;
     * this is the render-state-dependent remainder (terrain sampling + geographicToCartesian).
     * GLU emits CCW-from-above regardless of winding hints, so the bottom cap reverses each
     * triangle to face down.
     */
    private fun emitCap(rc: RenderContext, b: OsmBuilding, mesh: CapMesh?, alt: Double, isTop: Boolean) {
        mesh ?: return
        if (mesh.triangles.isEmpty()) return
        val base = vertices.size / VERTEX_STRIDE
        val outer = b.outerRing
        val n = ringCount(outer)
        for (i in 0 until n) pushLocalVertex(rc, outer[i].latitude, outer[i].longitude, alt)
        for (hole in b.innerRings) {
            val m = ringCount(hole)
            if (m >= 3) for (i in 0 until m) pushLocalVertex(rc, hole[i].latitude, hole[i].longitude, alt)
        }
        var k = 0
        while (k < mesh.combinedLonLat.size) {
            pushLocalVertex(rc, mesh.combinedLonLat[k + 1].degrees, mesh.combinedLonLat[k].degrees, alt)
            k += 2
        }
        val t = mesh.triangles
        var i = 0
        if (isTop) while (i < t.size) {
            emitTriangle(base + t[i], base + t[i + 1], base + t[i + 2]); i += 3
        } else while (i < t.size) {
            emitTriangle(base + t[i], base + t[i + 2], base + t[i + 1]); i += 3
        }
    }

    /**
     * Construction-time GLU tessellation of every footprint — pure 2D (lon°, lat°) work with no
     * render-state inputs, so it runs on the fetch coroutine's Dispatchers.Default thread and its
     * result is reused for both caps of a floating building and across every DEM/VE
     * re-tessellation. All tessellation state is local to this call; nothing is shared with the
     * render thread until the constructed tile is published through the layer's results channel.
     */
    private fun tessellateFootprints(buildings: List<OsmBuilding>): List<CapMesh?> {
        if (buildings.isEmpty()) return emptyList()
        val tess = GLU.gluNewTess()
        val triangles = IntList()
        val combined = ArrayList<Double>()
        var cornerCount = 0
        val triIdx = IntArray(3)
        var triCount = 0
        val callback = object : GLUtessellatorCallbackAdapter() {
            override fun vertexData(vertexData: Any?, polygonData: Any?) {
                triIdx[triCount++] = vertexData as Int
                if (triCount == 3) {
                    triCount = 0
                    triangles.add(triIdx[0]); triangles.add(triIdx[1]); triangles.add(triIdx[2])
                }
            }

            override fun combineData(
                coords: DoubleArray, data: Array<Any?>, weight: FloatArray, outData: Array<Any?>, polygonData: Any?
            ) {
                // GLU invokes combine on intersecting / coincident vertices. coords come back in
                // the (lon°, lat°) space we fed gluTessVertex; record the pair and hand back its
                // ordinal — [emitCap] materialises it after the ring corners at render time.
                outData[0] = cornerCount + combined.size / 2
                combined.add(coords[0])
                combined.add(coords[1])
            }

            override fun errorData(errnum: Int, polygonData: Any?) {
                // Most GLU errors on OSM input are recoverable warnings (self-intersecting rings,
                // near-coincident corners). Match [earth.worldwind.shape.Polygon] and keep whatever
                // triangles GLU did emit rather than dropping the building — wholesale-dropping
                // every building with a stray warning produced visible holes across the scene.
                logMessage(WARN, "OsmBuildingsTile", "tessellateFootprints",
                    "GLU error $errnum: ${GLU.gluErrorString(errnum)}")
            }

            // Registering edgeFlag forces GLU to emit GL_TRIANGLES exclusively. Without it GLU may
            // choose GL_TRIANGLE_FAN / GL_TRIANGLE_STRIP for some inputs — and the [vertexData]
            // loop here treats every three vertices as an independent triangle, mangling strip/fan
            // output and leaving holes in the roof of certain buildings.
            override fun edgeFlagData(boundaryEdge: Boolean, polygonData: Any?) { /* no-op */ }
        }
        // Same projection normal for every cap — footprints are 2D contours at z = 0.
        GLU.gluTessNormal(tess, 0.0, 0.0, 1.0)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_VERTEX_DATA, callback)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_COMBINE_DATA, callback)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_ERROR_DATA, callback)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_EDGE_FLAG_DATA, callback)
        val tessCoords = DoubleArray(3)
        val meshes = buildings.map { b ->
            val outer = b.outerRing
            val n = ringCount(outer)
            if (n < 3) return@map null
            // Corner ordinals: outer ring 0 until n, then each hole with >= 3 distinct corners —
            // [emitCap] pushes render vertices in this exact order.
            cornerCount = n + b.innerRings.sumOf { hole -> ringCount(hole).takeIf { it >= 3 } ?: 0 }
            triangles.clear()
            combined.clear()
            triCount = 0
            GLU.gluTessBeginPolygon(tess, Unit)
            var baseOrdinal = 0
            GLU.gluTessBeginContour(tess)
            for (i in 0 until n) {
                tessCoords[0] = outer[i].longitude.inDegrees
                tessCoords[1] = outer[i].latitude.inDegrees
                tessCoords[2] = 0.0
                GLU.gluTessVertex(tess, tessCoords, 0, baseOrdinal + i)
            }
            GLU.gluTessEndContour(tess)
            baseOrdinal += n
            for (hole in b.innerRings) {
                val m = ringCount(hole)
                if (m < 3) continue
                GLU.gluTessBeginContour(tess)
                for (i in 0 until m) {
                    tessCoords[0] = hole[i].longitude.inDegrees
                    tessCoords[1] = hole[i].latitude.inDegrees
                    tessCoords[2] = 0.0
                    GLU.gluTessVertex(tess, tessCoords, 0, baseOrdinal + i)
                }
                GLU.gluTessEndContour(tess)
                baseOrdinal += m
            }
            GLU.gluTessEndPolygon(tess)
            CapMesh(triangles.toIntArray(), combined.toDoubleArray(), cornerCount)
        }
        GLU.gluTessCallback(tess, GLU.GLU_TESS_VERTEX_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_COMBINE_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_ERROR_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_EDGE_FLAG_DATA, null)
        return meshes
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
     * Drops this tile's GL buffer entries from [earth.worldwind.render.RenderResourceCache] for
     * every cached globe state. Called by [OsmBuildingsLayer] on LRU eviction so the kernel
     * reclaims GPU pages eagerly instead of waiting for renderResourceCache pressure.
     */
    fun releaseRenderResources(rc: RenderContext) {
        for (td in data.values) {
            rc.renderResourceCache.remove(td.vertexBufferKey)
            rc.renderResourceCache.remove(td.elementBufferKey)
            rc.renderResourceCache.remove(td.pickElementBufferKey)
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

        /**
         * Drop `building=*` outlines whose footprint contains at least one `building:part=*`
         * centroid AND whose own height would Z-fight with the parts above it. Implements OSM
         * "Simple 3D Buildings" with one practical relaxation:
         *
         * Per spec an outline is 2D-only once parts exist — parts must fully describe the
         * volume. In practice many contributors take a shortcut: they tag the outline as
         * `building=*` with its own `height` (using the outline's polygon as the podium) and
         * model only the upper parts as `building:part=yes` with `min_height = podium_height`.
         * Strict filtering throws the podium away and the upper parts levitate. We therefore
         * KEEP an outline-with-height when every part it contains starts at or above its top
         * (`part.minHeight >= outline.height` for all enclosed parts) — there's no roof
         * Z-fight to avoid, and the outline IS the podium the contributor encoded.
         *
         * Spec-compliant data (podium tagged as a separate part with `min_height = 0`) still
         * filters cleanly: at least one of its enclosed parts will have `min_height = 0`, the
         * relaxation does not fire, the outline is dropped as before.
         *
         * Containment test uses each part's bbox center against the outline's outer ring with
         * a bbox prefilter. Inner rings (holes) of the outline are intentionally ignored — a
         * part centred in a hole would falsely keep the outline, but real OSM data rarely
         * places a part inside a hole and the worst case is a kept outline (no missing geometry).
         */
        internal fun filterRedundantOutlines(input: List<OsmBuilding>): List<OsmBuilding> {
            if (input.size < 2) return input
            val outlines = ArrayList<OsmBuilding>()
            val parts = ArrayList<OsmBuilding>()
            for (b in input) {
                val partTag = b.tags["building:part"]
                if (partTag != null && partTag != "no") parts += b else outlines += b
            }
            if (parts.isEmpty() || outlines.isEmpty()) return input

            val partCenters = DoubleArray(parts.size * 2)
            for (i in parts.indices) {
                val (cx, cy) = bboxCenterDeg(parts[i].outerRing)
                partCenters[i * 2] = cx
                partCenters[i * 2 + 1] = cy
            }

            val result = ArrayList<OsmBuilding>(input.size)
            for (outline in outlines) {
                var minLon = Double.POSITIVE_INFINITY; var maxLon = Double.NEGATIVE_INFINITY
                var minLat = Double.POSITIVE_INFINITY; var maxLat = Double.NEGATIVE_INFINITY
                for (p in outline.outerRing) {
                    val lon = p.longitude.inDegrees; val lat = p.latitude.inDegrees
                    if (lon < minLon) minLon = lon
                    if (lon > maxLon) maxLon = lon
                    if (lat < minLat) minLat = lat
                    if (lat > maxLat) maxLat = lat
                }
                // Walk every enclosed part once. Track whether ANY part is inside (covered),
                // and the LOWEST start altitude across them — the outline is acting as a podium
                // iff that lowest start sits at or above the outline's own roof.
                var covered = false
                var minPartStart = Double.POSITIVE_INFINITY
                for (i in parts.indices) {
                    val px = partCenters[i * 2]
                    val py = partCenters[i * 2 + 1]
                    if (px < minLon || px > maxLon || py < minLat || py > maxLat) continue
                    if (!pointInRingDeg(px, py, outline.outerRing)) continue
                    covered = true
                    if (parts[i].minHeight < minPartStart) minPartStart = parts[i].minHeight
                }
                val outlineIsPodium = covered && outline.height > 0.0 && minPartStart >= outline.height
                if (!covered || outlineIsPodium) result += outline
            }
            for (p in parts) result += p
            return result
        }

        private fun bboxCenterDeg(ring: List<Position>): Pair<Double, Double> {
            var minLon = Double.POSITIVE_INFINITY; var maxLon = Double.NEGATIVE_INFINITY
            var minLat = Double.POSITIVE_INFINITY; var maxLat = Double.NEGATIVE_INFINITY
            for (p in ring) {
                val lon = p.longitude.inDegrees; val lat = p.latitude.inDegrees
                if (lon < minLon) minLon = lon
                if (lon > maxLon) maxLon = lon
                if (lat < minLat) minLat = lat
                if (lat > maxLat) maxLat = lat
            }
            return ((minLon + maxLon) * 0.5) to ((minLat + maxLat) * 0.5)
        }

        /** Even-odd ray-cast point-in-polygon in (lon°, lat°) space. */
        private fun pointInRingDeg(x: Double, y: Double, ring: List<Position>): Boolean {
            var inside = false
            var j = ring.size - 1
            for (i in ring.indices) {
                val xi = ring[i].longitude.inDegrees; val yi = ring[i].latitude.inDegrees
                val xj = ring[j].longitude.inDegrees; val yj = ring[j].latitude.inDegrees
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }
    }
}
