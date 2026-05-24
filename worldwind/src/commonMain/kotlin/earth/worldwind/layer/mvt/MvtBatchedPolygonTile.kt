package earth.worldwind.layer.mvt

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.BoundingBox
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.PickedObject
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
 * Batched polygon-fill renderable for one MVT slippy tile.
 *
 * One VBO + one EBO per [Globe.State], regardless of polygon count. Triangles are grouped
 * by `(zOrder, colorPacked)` and one [DrawShapeState.drawElements] call is issued per
 * bucket. Buffer keys are shared across buckets so sub-draws don't allocate extra GL
 * buffers.
 *
 * Scope:
 *   - POLYGON features only. LINESTRING features go through [MvtBatchedLineTile]; POINT
 *     features become [earth.worldwind.shape.Label]s.
 *   - Surface-clamped via [DrawableSurfaceShape] — same compositing pipeline as
 *     `altitudeMode = CLAMP_TO_GROUND, isFollowTerrain = true` on [earth.worldwind.shape.Polygon].
 *     Vertex altitudes are baked at 0 m; the compositor projects to terrain.
 *
 * Input rings are assumed CCW in lat/lon — [MvtGeometry.decodePolygons] guarantees this
 * from the MVT spec's positive-area-in-tile-space exterior rule. Inner rings (holes) feed
 * gluTess as combined contours without separate winding handling.
 */
class MvtBatchedPolygonTile(
    private val features: List<BatchFeature>,
    private val boundingSector: Sector,
    displayName: String? = null,
) : AbstractRenderable(displayName) {

    /**
     * One polygon feature pre-resolved into rings + style attributes. [MvtVectorLayer] builds
     * this list off-thread during the tile fetch; [MvtBatchedPolygonTile.assembleGeometry]
     * then turns it into GPU-friendly geometry on the render thread.
     *
     * [zOrder] from [MvtStyle.zOrderFor] controls per-feature paint order within the tile.
     * Features sort ascending; ties keep encounter order. Same-color features at different
     * z's get separate triangle buckets, so an interleaving feature can paint between them.
     */
    data class BatchFeature(
        val outer: List<Position>,
        val holes: List<List<Position>>,
        val attributes: ShapeAttributes,
        val zOrder: Int = 0,
        /**
         * If non-null and the layer is pick-enabled, this feature draws separately with a
         * unique pick color in pick mode and exposes [pickPayload] via [PickedObject.userObject].
         */
        val pickPayload: MvtPickedFeature? = null,
    )

    private val data = mutableMapOf<Globe.State?, TileData>()
    private val boundingBox = BoundingBox()
    private var bufferDataVersion = 0
    private val scratchPoint = Vec3()
    private val tessCoords = DoubleArray(3)
    private val vertices = FloatList()
    // Bucket key: high 32 bits = signed Int zOrder, low 32 bits = colorPacked. Sorting features
    // by zOrder before assembly makes [LinkedHashMap]'s insertion order match z-ascending
    // automatically; same-color features at different z's still land in distinct buckets so an
    // intervening feature can paint between them.
    private val perZColorElements = LinkedHashMap<Long, IntList>()
    private var currentElements: IntList = IntList()
    private val vertexOrigin = Vec3()
    private var topIndexBase = 0
    private val tessTriIdx = IntArray(3)
    private var tessTriCount = 0
    // Held only for the duration of one assembleGeometry call so combineData can resolve
    // ECEF for synthesized vertices.
    private var tessGlobe: Globe? = null
    private val tessCallback = object : GLUtessellatorCallbackAdapter() {
        override fun vertexData(vertexData: Any?, polygonData: Any?) {
            tessTriIdx[tessTriCount++] = topIndexBase + (vertexData as Int)
            if (tessTriCount == 3) {
                tessTriCount = 0
                emitTriangle(tessTriIdx[0], tessTriIdx[1], tessTriIdx[2])
            }
        }

        override fun combineData(
            coords: DoubleArray, data: Array<Any?>, weight: FloatArray, outData: Array<Any?>, polygonData: Any?
        ) {
            // GLU calls combine on intersecting/coincident vertices. coords come back in the
            // (lon°, lat°) space we fed gluTessVertex; convert back to local Cartesian at
            // altitude 0 — surface compositing reprojects to terrain elevation anyway.
            val globe = tessGlobe ?: return
            val lon = coords[0].degrees
            val lat = coords[1].degrees
            val newIdx = pushLocalVertex(globe, lat, lon)
            outData[0] = newIdx - topIndexBase
        }

        override fun errorData(errnum: Int, polygonData: Any?) {
            // Most GLU errors on tessellated MVT input are recoverable warnings (self-touching
            // rings near tile borders, near-coincident vertices). Keep whatever triangles GLU
            // managed to emit rather than dropping the feature wholesale.
            logMessage(WARN, "MvtBatchedPolygonTile", "runTess",
                "GLU error $errnum: ${GLU.gluErrorString(errnum)}")
        }

        // Force GL_TRIANGLES output — without this GLU may emit STRIP/FAN, which the
        // three-vertices-per-triangle [vertexData] handler would mangle.
        override fun edgeFlagData(boundaryEdge: Boolean, polygonData: Any?) { /* no-op */ }
    }

    /** Per-globe-state geometry cache. */
    private class TileData {
        val vertexOrigin = Vec3()
        var vertexArray = FloatArray(0)
        var elementArray = IntArray(0)
        var colorRanges: List<ColorRange> = emptyList()
        /**
         * Flat `[offset0, count0, offset1, count1, …]` per feature in [features] order; empty
         * (or zero counts) when picking isn't enabled. Each pair points into [elementArray].
         */
        var featureElementRanges: IntArray = IntArray(0)
        val vertexBufferKey = Any()
        val elementBufferKey = Any()
        var refreshGeometry = true
    }

    private class ColorRange(val colorPacked: Int, val offset: Int, val count: Int)
    private class FeatureLocalRange(val featureIndex: Int, val bucketKey: Long, val localOffset: Int, val localCount: Int)
    // Scratch list filled during assembleGeometry, drained on EBO concat.
    private val featureLocalRanges = ArrayList<FeatureLocalRange>()
    private val scratchPickColor = Color()

    /**
     * Pre-assemble this tile's geometry for the given [globe] / [globeState] off the render
     * thread. Safe to call from any thread *as long as only one thread assembles a given
     * instance at a time* — [MvtVectorLayer] calls this once per tile on a fetch coroutine,
     * before placing the tile in the LRU. Falls back to lazy assembly in [doRender] when the
     * globe state changes at runtime or when the layer never got a chance to capture the
     * Globe (pre-first-doRender fetch).
     */
    fun assemble(globe: Globe, globeState: Globe.State?) {
        val tileData = data.getOrPut(globeState) { TileData() }
        if (tileData.refreshGeometry) {
            assembleGeometry(globe, tileData)
            tileData.refreshGeometry = false
        }
    }

    override fun doRender(rc: RenderContext) {
        val tileData = data.getOrPut(rc.globeState) { TileData() }
        if (tileData.refreshGeometry) {
            // Lazy fallback when a globe-state change invalidates the pre-assembled tile or
            // when the layer hadn't yet captured a globe at fetch time.
            assembleGeometry(rc.globe, tileData)
            tileData.refreshGeometry = false
        }
        if (tileData.vertexArray.isEmpty()) return

        if (rc.isPickMode) {
            emitPickDrawables(rc, tileData)
            return
        }

        val ranges = tileData.colorRanges
        if (ranges.isEmpty()) return
        var chunkStart = 0
        while (chunkStart < ranges.size) {
            val chunkEnd = min(chunkStart + DrawShapeState.MAX_DRAW_ELEMENTS, ranges.size)
            emitDrawable(rc, tileData, ranges, chunkStart, chunkEnd)
            chunkStart = chunkEnd
        }
    }

    /**
     * In pick mode emit one [DrawableSurfaceShape] per chunk of features (up to
     * [DrawShapeState.MAX_DRAW_ELEMENTS] features per drawable, each prim drawn with its
     * own unique pick color). Each pickable feature registers a [PickedObject] before its
     * drawable is enqueued so the resolve pass can recover [BatchFeature.pickPayload] from
     * the readback pick color.
     */
    private fun emitPickDrawables(rc: RenderContext, tileData: TileData) {
        val ranges = tileData.featureElementRanges
        if (ranges.isEmpty()) return
        val n = features.size
        var i = 0
        while (i < n) {
            // Collect up to MAX_DRAW_ELEMENTS pickable features into one drawable.
            val drawable = lazyPickDrawable(rc, tileData) ?: return
            val drawState = drawable.drawState
            var primCount = 0
            while (i < n && primCount < DrawShapeState.MAX_DRAW_ELEMENTS) {
                val payload = features[i].pickPayload
                val offset = ranges[i * 2]
                val count = ranges[i * 2 + 1]
                if (payload != null && count > 0) {
                    val pickedId = rc.nextPickedObjectId()
                    PickedObject.identifierToUniqueColor(pickedId, scratchPickColor)
                    rc.offerPickedObject(
                        PickedObject.fromUserObject(pickedId, payload, rc.currentLayer, useTerrainPosition = true)
                    )
                    drawState.color.copy(scratchPickColor)
                    drawState.opacity = 1f
                    drawState.drawElements(GL_TRIANGLES, count, GL_UNSIGNED_INT, offset * Int.SIZE_BYTES)
                    primCount++
                }
                i++
            }
            if (primCount == 0) {
                // No pickable features in this chunk — skip enqueuing the drawable (avoid
                // emitting an empty shape into the pick queue).
                continue
            }
            drawState.enableDepthWrite = true
            rc.offerSurfaceDrawable(drawable, zOrder = zOrder)
        }
    }

    private fun lazyPickDrawable(rc: RenderContext, tileData: TileData): DrawableSurfaceShape? {
        val pool = rc.getDrawablePool(DrawableSurfaceShape.KEY)
        val drawable = DrawableSurfaceShape.obtain(pool)
        val drawState = drawable.drawState
        drawable.offset = rc.globe.offset
        drawable.sector.copy(boundingSector)
        drawable.version = 31 * hashCode() + bufferDataVersion
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
        drawState.vertexOrigin.copy(tileData.vertexOrigin)
        drawState.boundingCenter.copy(boundingBox.center)
        drawState.boundingRadius = boundingBox.radius
        drawState.vertexStride = VERTEX_STRIDE * Float.SIZE_BYTES
        drawState.enableCullFace = false
        drawState.enableDepthTest = true
        drawState.enableLighting = false
        drawState.texCoordAttrib.size = 1
        drawState.texCoordAttrib.offset = 0
        return drawable
    }

    private fun emitDrawable(
        rc: RenderContext, tileData: TileData, ranges: List<ColorRange>,
        from: Int, to: Int,
    ) {
        val pool = rc.getDrawablePool(DrawableSurfaceShape.KEY)
        val drawable = DrawableSurfaceShape.obtain(pool)
        val drawState = drawable.drawState

        // Surface-shape compositor inputs.
        drawable.offset = rc.globe.offset
        drawable.sector.copy(boundingSector)
        // Version key: tile identity (this) + a counter that increments whenever the geometry
        // is reassembled. Stable across frames for a static tile = surface-tile-cache hits.
        drawable.version = 31 * hashCode() + bufferDataVersion

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
        drawState.enableCullFace = false
        // Decorative per surface-compositor handling; mirrors Polygon's clamp-to-ground branch.
        drawState.enableDepthTest = true
        drawState.enableLighting = false
        // texCoordAttrib.size must be ≥ 1 (GL rejects 0); no texture is sampled here.
        drawState.texCoordAttrib.size = 1
        drawState.texCoordAttrib.offset = 0

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

        // Tile-level zOrder set by the layer (typically the lowest feature z so lines
        // composite above). Per-feature ordering within the tile is preserved by EBO bucket
        // order.
        rc.offerSurfaceDrawable(drawable, zOrder = zOrder)
    }

    private fun assembleGeometry(globe: Globe, tileData: TileData) {
        ++bufferDataVersion
        vertices.clear()
        perZColorElements.clear()
        featureLocalRanges.clear()

        // Anchor at the first feature's first outer vertex. Local-Cartesian floats stay
        // small (sub-tile range) relative to ECEF magnitudes, dodging single-precision loss.
        val anchor = features.firstOrNull { it.outer.size >= 3 }?.outer?.get(0)
        if (anchor == null) {
            tileData.vertexArray = FloatArray(0)
            tileData.elementArray = IntArray(0)
            tileData.colorRanges = emptyList()
            tileData.featureElementRanges = IntArray(0)
            return
        }
        globe.geographicToCartesian(anchor.latitude, anchor.longitude, 0.0, vertexOrigin)
        tileData.vertexOrigin.copy(vertexOrigin)

        // Stable sort by zOrder ascending; ties keep the MVT server's intra-layer order. Build
        // an index-back-map so we can record per-feature ranges against the ORIGINAL
        // [features] list (the order the layer hands out pickPayloads in).
        val originalIndices = features.indices.toMutableList()
        if (features.size > 1) originalIndices.sortBy { features[it].zOrder }
        for (idx in originalIndices) {
            val f = features[idx]
            if (f.outer.size < 3) continue
            assembleFeature(globe, f, idx)
        }

        // Concat per-(z, color) buckets into the final EBO; LinkedHashMap iteration = z-asc.
        val totalElements = perZColorElements.values.sumOf { it.size }
        val finalElements = IntArray(totalElements)
        val ranges = ArrayList<ColorRange>(perZColorElements.size)
        val bucketGlobalOffset = HashMap<Long, Int>()
        var offset = 0
        for ((key, list) in perZColorElements) {
            if (list.size == 0) continue
            val colorPacked = (key and 0xFFFFFFFFL).toInt()
            bucketGlobalOffset[key] = offset
            list.copyTo(finalElements, offset)
            ranges.add(ColorRange(colorPacked, offset, list.size))
            offset += list.size
        }
        // Resolve per-feature ranges against the final EBO. `featureElementRanges[2*i]` is the
        // offset (in indices) and `[2*i+1]` is the count. Features with no triangles emit a
        // (0, 0) range so the pick path can skip them by checking count==0.
        val flat = IntArray(features.size * 2)
        for (r in featureLocalRanges) {
            val base = bucketGlobalOffset[r.bucketKey] ?: continue
            flat[r.featureIndex * 2] = base + r.localOffset
            flat[r.featureIndex * 2 + 1] = r.localCount
        }
        tileData.vertexArray = vertices.toFloatArray()
        tileData.elementArray = finalElements
        tileData.colorRanges = ranges
        tileData.featureElementRanges = flat

        boundingBox.setToPoints(tileData.vertexArray, tileData.vertexArray.size, VERTEX_STRIDE)
        boundingBox.translate(tileData.vertexOrigin.x, tileData.vertexOrigin.y, tileData.vertexOrigin.z)

        // Drop the working buffers — they pin ~the same memory as the final arrays.
        vertices.shrink()
        perZColorElements.clear()
        featureLocalRanges.clear()
    }

    private fun assembleFeature(globe: Globe, feature: BatchFeature, featureIndex: Int) {
        val colorPacked = packColor(feature.attributes.interiorColor)
        selectBucket(feature.zOrder, colorPacked)
        val bucketKey = currentBucketKey
        val localOffset = currentElements.size

        // Push outer ring corners; remember the base for tess to resolve ordinals later.
        topIndexBase = vertices.size / VERTEX_STRIDE
        val outer = feature.outer
        val outerN = ringCount(outer)
        if (outerN < 3) return
        for (i in 0 until outerN) pushLocalVertex(globe, outer[i].latitude, outer[i].longitude)

        // Push hole ring corners — each at its own ordinal base; feedContour resolves it
        // back to global vertex index via topIndexBase + (baseOrdinal + i).
        val holes = feature.holes
        val holeBases = IntArray(holes.size)
        for ((hi, hole) in holes.withIndex()) {
            val m = ringCount(hole)
            holeBases[hi] = (vertices.size / VERTEX_STRIDE) - topIndexBase
            if (m >= 3) for (i in 0 until m) {
                pushLocalVertex(globe, hole[i].latitude, hole[i].longitude)
            }
        }
        runTess(globe, outer, holes, holeBases)

        val count = currentElements.size - localOffset
        if (count > 0) featureLocalRanges += FeatureLocalRange(featureIndex, bucketKey, localOffset, count)
    }

    private var currentBucketKey: Long = 0L

    private fun selectBucket(zOrder: Int, colorPacked: Int) {
        // High 32 bits: signed-int zOrder. Low 32 bits: colorPacked treated as unsigned. The
        // `& 0xFFFFFFFFL` keeps a negative-bit-pattern color from sign-extending into the
        // zOrder half and producing collisions.
        val key = (zOrder.toLong() shl 32) or (colorPacked.toLong() and 0xFFFFFFFFL)
        currentBucketKey = key
        currentElements = perZColorElements.getOrPut(key) { IntList() }
    }

    private fun emitTriangle(v0: Int, v1: Int, v2: Int) {
        currentElements.add(v0); currentElements.add(v1); currentElements.add(v2)
    }

    private fun runTess(
        globe: Globe, outer: List<Position>, holes: List<List<Position>>,
        holeBases: IntArray,
    ) {
        // Fresh tessellator instance per call — `Dispatchers.Default` callers may run on any
        // worker thread, so `rc.tessellator` (the render-thread default) can't be shared.
        val tess = GLU.gluNewTess()
        tessTriCount = 0
        tessGlobe = globe

        // Tessellation happens in the (lon, lat) plane (we feed [feedContour] (lon, lat, 0)),
        // so normal = +Z is correct. The VBO is filled with ECEF by [pushLocalVertex] —
        // distinct from the tess input.
        GLU.gluTessNormal(tess, 0.0, 0.0, 1.0)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_VERTEX_DATA, tessCallback)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_COMBINE_DATA, tessCallback)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_ERROR_DATA, tessCallback)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_EDGE_FLAG_DATA, tessCallback)
        GLU.gluTessBeginPolygon(tess, this)

        feedContour(tess, outer, baseOrdinal = 0)
        for ((hi, hole) in holes.withIndex()) {
            if (hole.size >= 3) feedContour(tess, hole, baseOrdinal = holeBases[hi])
        }

        GLU.gluTessEndPolygon(tess)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_VERTEX_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_COMBINE_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_ERROR_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_EDGE_FLAG_DATA, null)
        tessGlobe = null
    }

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

    private fun ringCount(ring: List<Position>): Int = ring.size

    private fun pushLocalVertex(globe: Globe, latitude: Angle, longitude: Angle): Int {
        // Altitude 0 — surface compositor reprojects to terrain. Pure ellipsoid math is
        // off-thread safe.
        globe.geographicToCartesian(latitude, longitude, 0.0, scratchPoint)
        val idx = vertices.size / VERTEX_STRIDE
        vertices.add((scratchPoint.x - vertexOrigin.x).toFloat())
        vertices.add((scratchPoint.y - vertexOrigin.y).toFloat())
        vertices.add((scratchPoint.z - vertexOrigin.z).toFloat())
        return idx
    }

    /**
     * Drop this tile's GL buffer entries from [earth.worldwind.render.RenderResourceCache] for
     * every cached globe state. Called by [MvtVectorLayer] on LRU eviction so the kernel
     * reclaims GPU pages eagerly instead of waiting for cache pressure.
     */
    fun releaseRenderResources(rc: RenderContext) {
        for (td in data.values) {
            rc.renderResourceCache.remove(td.vertexBufferKey)
            rc.renderResourceCache.remove(td.elementBufferKey)
        }
    }

    /**
     * Drop cached [TileData] for any globe state other than [keepState]. Callers that swap
     * projections at runtime can keep the per-frame cache small without churning fully.
     */
    fun releaseGlobeStatesExcept(keepState: Globe.State?) {
        val it = data.entries.iterator()
        while (it.hasNext()) if (it.next().key != keepState) it.remove()
    }

    companion object {
        // Three local-space floats per vertex (x, y, z). The TriangleShaderProgram still
        // expects a texCoord attribute pointer, but we set texCoordAttrib.size = 1 and let
        // it alias the first float of position — no texture is sampled.
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

