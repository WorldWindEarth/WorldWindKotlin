package earth.worldwind.layer.mvt

import earth.worldwind.PickedObject
import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.BoundingBox
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.program.TriangleShaderProgram
import earth.worldwind.shape.AbstractShape
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.util.IntList
import earth.worldwind.util.NumericArray
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_ELEMENT_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_TRIANGLE_STRIP
import earth.worldwind.util.kgl.GL_UNSIGNED_INT
import kotlin.math.max
import kotlin.math.min

/**
 * Batched line-stroke renderable for one MVT slippy tile.
 *
 * One shared VBO + one shared EBO per [Globe.State]. Polylines that share the same
 * `(colorPacked, lineWidth)` bucket are concatenated into a single index-buffer range,
 * separated by degenerate `GL_TRIANGLE_STRIP` bridges so each bucket emits exactly ONE
 * [DrawShapeState.drawElements] call.
 *
 * Vertex layout matches [earth.worldwind.shape.Path]'s outline format so the existing
 * [TriangleShaderProgram] (with `drawState.isLine = true`) renders the strip:
 *   - VERTEX_STRIDE = 5 floats per GPU vertex (x, y, z, OUTLINE_CORNER_tag, texCoord1d).
 *   - Each logical waypoint expands to 4 GPU vertices (UL, LL, UR, LR corners). The shader
 *     uses the OUTLINE_CORNER tag to compute the perpendicular line-width offset.
 *   - Position triple is in DEGREE-relative coords (lon_diff_deg, lat_diff_deg, alt_diff);
 *     the surface compositor projects to terrain.
 *
 * Each polyline contributes one leading dummy + K waypoints + 2 trailing dummies. The
 * trailing dummies exist because the line shader's `pointC` reads two vertex slots past
 * the current — WebGL rejects `drawElements` otherwise. Only the leading dummy + waypoints
 * contribute indices; trailing dummies are unindexed padding.
 */
class MvtBatchedLineTile(
    private val features: List<BatchLineFeature>,
    private val boundingSector: Sector,
    displayName: String? = null,
) : AbstractRenderable(displayName) {

    /**
     * Per-frame multiplier applied to every line's width at draw time. Lets
     * [MvtVectorLayer] smoothly scale line widths between tile-zoom boundaries (a tile
     * baked at z=12 looks 1× thick at camera zoom 12, ~2× thick at camera zoom 13). The
     * layer writes this each frame before calling [doRender]; default 1.0 = no scale.
     */
    var widthScale: Float = 1f

    /**
     * One polyline feature pre-resolved into a position list, attributes, and z-order.
     * [MvtVectorLayer] builds these off-thread during the tile fetch.
     */
    data class BatchLineFeature(
        val positions: List<Position>,
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
    // Working buffers, cleared/grown across assembleGeometry calls.
    private val vertices = FloatList()
    private val ranges = ArrayList<LineRange>()

    private class TileData {
        val vertexOrigin = Vec3()
        var vertexArray = FloatArray(0)
        var elementArray = IntArray(0)
        var lineRanges: List<LineRange> = emptyList()
        /**
         * Per-feature element ranges as `[offset0, count0, offset1, count1, …]` in [features]
         * order. Used by the pick render path; left zero-filled when picking isn't enabled.
         */
        var featureElementRanges: IntArray = IntArray(0)
        val vertexBufferKey = Any()
        val elementBufferKey = Any()
        var refreshGeometry = true
    }

    private val scratchPickColor = Color()

    /**
     * One polyline's slice of the shared EBO, with its style state captured at assembly time.
     * Drawn as `GL_TRIANGLE_STRIP` against the shared VBO; the shader expands each logical
     * 4-vertex group into a perpendicular-offset quad.
     */
    private class LineRange(
        val colorPacked: Int,
        val lineWidth: Float,
        val opacity: Float,
        val offset: Int,
        val count: Int,
        /** Optional stipple texture source (Mapbox `line-dasharray`); null = solid line. */
        val outlineImageSource: earth.worldwind.render.image.ImageSource? = null,
    )

    /** Hash + equality key for bucketing line features by visual state. */
    private data class BucketKey(
        val colorPacked: Int,
        val widthBits: Int,
        val outlineImageSource: earth.worldwind.render.image.ImageSource?,
    )

    /**
     * Pre-assemble this tile's line vertex array for [globeState] off the render thread.
     * Line assembly is pure (lon°, lat°) arithmetic — no Globe-dependent ECEF math like the
     * polygon tile needs — but we still gate by [globeState] so a globe-state change at
     * runtime can invalidate and rebuild.
     */
    fun assemble(globeState: Globe.State?) {
        val tileData = data.getOrPut(globeState) { TileData() }
        if (tileData.refreshGeometry) {
            assembleGeometry(tileData)
            tileData.refreshGeometry = false
        }
    }

    override fun doRender(rc: RenderContext) {
        val tileData = data.getOrPut(rc.globeState) { TileData() }
        if (tileData.refreshGeometry) {
            assembleGeometry(tileData)
            tileData.refreshGeometry = false
        }
        if (tileData.vertexArray.isEmpty() || tileData.lineRanges.isEmpty()) return

        if (rc.isPickMode) {
            emitPickDrawables(rc, tileData)
            return
        }

        val rangesList = tileData.lineRanges
        var chunkStart = 0
        while (chunkStart < rangesList.size) {
            val chunkEnd = min(chunkStart + DrawShapeState.MAX_DRAW_ELEMENTS, rangesList.size)
            emitDrawable(rc, tileData, rangesList, chunkStart, chunkEnd)
            chunkStart = chunkEnd
        }
    }

    private fun emitPickDrawables(rc: RenderContext, tileData: TileData) {
        val featureRanges = tileData.featureElementRanges
        if (featureRanges.isEmpty()) return
        val n = features.size
        var i = 0
        while (i < n) {
            val drawable = newPickDrawable(rc, tileData)
            val drawState = drawable.drawState
            var primCount = 0
            while (i < n && primCount < DrawShapeState.MAX_DRAW_ELEMENTS) {
                val feature = features[i]
                val payload = feature.pickPayload
                val offset = featureRanges[i * 2]
                val count = featureRanges[i * 2 + 1]
                if (payload != null && count > 0) {
                    val pickedId = rc.nextPickedObjectId()
                    PickedObject.identifierToUniqueColor(pickedId, scratchPickColor)
                    rc.offerPickedObject(
                        PickedObject.fromUserObject(pickedId, payload, rc.currentLayer, useTerrainPosition = true)
                    )
                    drawState.color.copy(scratchPickColor)
                    drawState.opacity = 1f
                    // Pick draws ignore widthScale — we want hit area = actual rendered area, so
                    // applying the same width as the visible drawable would be ideal. But adding
                    // a +0.5 fattening pixel here keeps tiny lines pickable without making the
                    // visible result inconsistent.
                    drawState.lineWidth = feature.attributes.outlineWidth * widthScale + 0.5f
                    drawState.drawElements(
                        GL_TRIANGLE_STRIP, count, GL_UNSIGNED_INT, offset * Int.SIZE_BYTES,
                    )
                    primCount++
                }
                i++
            }
            if (primCount == 0) continue
            drawState.enableDepthWrite = true
            rc.offerSurfaceDrawable(drawable, zOrder)
        }
    }

    private fun newPickDrawable(rc: RenderContext, tileData: TileData): DrawableSurfaceShape {
        val pool = rc.getDrawablePool(DrawableSurfaceShape.KEY)
        val drawable = DrawableSurfaceShape.obtain(pool)
        val drawState = drawable.drawState
        drawable.offset = rc.globe.offset
        drawable.sector.copy(boundingSector)
        drawable.version = 31 * hashCode() + bufferDataVersion
        drawState.isLine = true
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
        drawState.texCoordAttrib.size = 2
        drawState.texCoordAttrib.offset = 3 * Float.SIZE_BYTES
        return drawable
    }

    private fun emitDrawable(
        rc: RenderContext, tileData: TileData, rangesList: List<LineRange>,
        from: Int, to: Int,
    ) {
        val pool = rc.getDrawablePool(DrawableSurfaceShape.KEY)
        val drawable = DrawableSurfaceShape.obtain(pool)
        val drawState = drawable.drawState

        drawable.offset = rc.globe.offset
        drawable.sector.copy(boundingSector)
        drawable.version = 31 * hashCode() + bufferDataVersion

        // Line mode — TriangleShaderProgram interprets vertex buffer as Path's outline format.
        drawState.isLine = true
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
        // Outline path doesn't sample a texture for plain solid lines; texCoordAttrib.size = 2
        // because Path's vertex layout dedicates two trailing floats (corner + texCoord1d) the
        // shader reads as a vec2.
        drawState.texCoordAttrib.size = 2
        drawState.texCoordAttrib.offset = 3 * Float.SIZE_BYTES

        var anyOpaque = false
        for (i in from until to) {
            val range = rangesList[i]
            unpackColorInto(range.colorPacked, drawState.color)
            drawState.opacity = range.opacity * rc.currentLayer.opacity
            // +0.5 px corrects the offscreen-texture pass that makes surface lines look
            // thinner when sampled back; matches Path's surface branch.
            drawState.lineWidth = range.lineWidth * widthScale + 0.5f
            // Set the per-range outline texture (Mapbox `line-dasharray`) before each draw —
            // DrawShapeState.drawElements captures drawState.texture into the prim, so the
            // next range's null/non-null texture overrides it independently.
            drawState.texture = range.outlineImageSource?.let {
                rc.getTexture(it, defaultOutlineImageOptions)
            }
            drawState.textureLod = 0
            if (drawState.color.alpha * drawState.opacity >= 1f) anyOpaque = true
            drawState.drawElements(
                GL_TRIANGLE_STRIP, range.count, GL_UNSIGNED_INT, range.offset * Int.SIZE_BYTES,
            )
        }
        // Clear so the pooled drawable doesn't carry a stale stipple texture if its next
        // recycle is non-dashed.
        drawState.texture = null
        drawState.enableDepthWrite = anyOpaque

        rc.offerSurfaceDrawable(drawable, zOrder)
    }

    private fun assembleGeometry(tileData: TileData) {
        ++bufferDataVersion
        vertices.clear()
        ranges.clear()

        // Anchor in degrees at the first feature's first waypoint. Surface shapes' vertex
        // origin is (longitudeDeg, latitudeDeg, altitude); per-vertex floats are deltas in
        // the same units. The compositor expects this layout for surface-path shaders.
        val anchor = features.firstOrNull { it.positions.size >= 2 }?.positions?.get(0)
        if (anchor == null) {
            tileData.vertexArray = FloatArray(0)
            tileData.elementArray = IntArray(0)
            tileData.lineRanges = emptyList()
            tileData.featureElementRanges = IntArray(0)
            return
        }
        tileData.vertexOrigin.set(
            anchor.longitude.inDegrees, anchor.latitude.inDegrees, anchor.altitude,
        )

        // Sort indices by zOrder while remembering original positions — the pick render path
        // indexes featureElementRanges by original feature index.
        val sortedIndices = features.indices.toMutableList()
        if (features.size > 1) sortedIndices.sortBy { features[it].zOrder }
        val featureRanges = IntArray(features.size * 2)

        // Bucket by `(colorPacked, lineWidth, outlineImageSource)`. Stippled (dashed) lines
        // share a bucket with the same dash pattern; solid lines share the null-source
        // bucket. `Float.toRawBits` gives exact equality on identical literals; ImageSource
        // identity equality is the bucket discriminator for stipple.
        val groups = LinkedHashMap<BucketKey, MutableList<Int>>()
        for (origIdx in sortedIndices) {
            val feature = features[origIdx]
            if (feature.positions.size < 2) continue
            val attrs = feature.attributes
            val colorPacked = packColor(attrs.outlineColor)
            val key = BucketKey(
                colorPacked = colorPacked,
                widthBits = attrs.outlineWidth.toRawBits(),
                outlineImageSource = attrs.outlineImageSource,
            )
            groups.getOrPut(key) { ArrayList() } += origIdx
        }

        // Emit one contiguous element range per bucket. Between concatenated polylines we
        // insert `[lastIdx, firstNewIdx]` — four zero-area triangles that bridge two strips
        // without rasterising any fragments. Track each feature's own (offset, count) for
        // the pick path (excludes bridge indices, which "belong" to no feature).
        val elementsScratch = IntList()
        for ((key, memberIndices) in groups) {
            val first = features[memberIndices[0]].attributes
            val groupColorPacked = packColor(first.outlineColor)
            val groupLineWidth = first.outlineWidth
            val rangeStart = elementsScratch.size
            var lastIdx = -1
            for (origIdx in memberIndices) {
                if (lastIdx >= 0) {
                    val firstNewIdx = vertices.size / VERTEX_STRIDE
                    elementsScratch.add(lastIdx)
                    elementsScratch.add(firstNewIdx)
                }
                val featureStart = elementsScratch.size
                emitPolyline(features[origIdx].positions, tileData.vertexOrigin, elementsScratch)
                featureRanges[origIdx * 2] = featureStart
                featureRanges[origIdx * 2 + 1] = elementsScratch.size - featureStart
                lastIdx = elementsScratch[elementsScratch.size - 1]
            }
            val count = elementsScratch.size - rangeStart
            if (count == 0) continue
            ranges += LineRange(
                colorPacked = groupColorPacked,
                lineWidth = groupLineWidth,
                opacity = 1f,
                offset = rangeStart,
                count = count,
                outlineImageSource = key.outlineImageSource,
            )
        }

        if (ranges.isEmpty()) {
            tileData.vertexArray = FloatArray(0)
            tileData.elementArray = IntArray(0)
            tileData.lineRanges = emptyList()
            tileData.featureElementRanges = IntArray(0)
            vertices.shrink()
            return
        }

        tileData.vertexArray = vertices.toFloatArray()
        tileData.elementArray = IntArray(elementsScratch.size)
        elementsScratch.copyTo(tileData.elementArray, 0)
        tileData.lineRanges = ranges.toList()
        tileData.featureElementRanges = featureRanges

        // BoundingBox is set in unit-box mode for surface shapes — the compositor frustum-
        // tests against [boundingSector], not [boundingBox]. Match Path's surface branch.
        boundingBox.setToUnitBox()

        // Drop working buffers.
        vertices.shrink()
        ranges.clear()
    }

    /**
     * Emit one polyline's vertex corners into [vertices] and its index strip into
     * [outElements]. Replicates [earth.worldwind.shape.Path]'s outline assembly:
     *
     *   - Logical vertex layout: `[dummy0, wp0, wp1, ..., wpN-1, dummyEnd, dummyEnd]`.
     *   - Each logical vertex = 4 GPU vertices (UL, LL, UR, LR) at the SAME position.
     *   - Indices added for: leading dummy + each waypoint = `4 × (N + 1)` index entries.
     *   - Trailing 2 dummies have no indices but reserve VBO slots — the line shader's
     *     `pointC` reads 2 logical-vertex slots ahead of the current index, and WebGL
     *     rejects drawElements that reference past-end vertices.
     */
    private fun emitPolyline(
        positions: List<Position>, vertexOrigin: Vec3, outElements: IntList,
    ) {
        val n = positions.size
        // Leading dummy at positions[0], with indices (matches Path's pattern).
        val first = positions[0]
        addLogicalVertex(first, vertexOrigin, addIndices = true, outElements)
        // Real waypoints, each with indices.
        for (i in 0 until n) {
            addLogicalVertex(positions[i], vertexOrigin, addIndices = true, outElements)
        }
        // Two trailing dummies at positions[n-1], NO indices — padding for the shader's
        // 2-slot lookahead.
        val last = positions[n - 1]
        addLogicalVertex(last, vertexOrigin, addIndices = false, outElements)
        addLogicalVertex(last, vertexOrigin, addIndices = false, outElements)
    }

    /**
     * Add one logical waypoint to the VBO: 4 corner vertices (UL, LL, UR, LR), each 5 floats.
     * When [addIndices] is true, also emits 4 index entries into [outElements] referencing the
     * just-written corners as a contiguous strip slice.
     */
    private fun addLogicalVertex(
        pos: Position, origin: Vec3, addIndices: Boolean, outElements: IntList,
    ) {
        val x = (pos.longitude.inDegrees - origin.x).toFloat()
        val y = (pos.latitude.inDegrees - origin.y).toFloat()
        val z = (pos.altitude - origin.z).toFloat()
        // texCoord1d would normally be cumulative line length (used only for textured outlines;
        // our solid lines ignore it). Setting 0 across all corners matches Path's behaviour for
        // untextured outline paint.
        val tex = 0f
        val baseLogicalIdx = vertices.size / (4 * VERTEX_STRIDE)
        vertices.add(x); vertices.add(y); vertices.add(z); vertices.add(AbstractShape.OUTLINE_CORNER_UL); vertices.add(tex)
        vertices.add(x); vertices.add(y); vertices.add(z); vertices.add(AbstractShape.OUTLINE_CORNER_LL); vertices.add(tex)
        vertices.add(x); vertices.add(y); vertices.add(z); vertices.add(AbstractShape.OUTLINE_CORNER_UR); vertices.add(tex)
        vertices.add(x); vertices.add(y); vertices.add(z); vertices.add(AbstractShape.OUTLINE_CORNER_LR); vertices.add(tex)
        if (addIndices) {
            // Logical vertex index → 4 GPU vertex indices (the four corners we just wrote).
            val base = baseLogicalIdx * 4
            outElements.add(base)
            outElements.add(base + 1)
            outElements.add(base + 2)
            outElements.add(base + 3)
        }
    }

    /** See [MvtBatchedPolygonTile.releaseGlobeStatesExcept]. */
    fun releaseGlobeStatesExcept(rc: RenderContext, keepState: Globe.State?) {
        val it = data.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key != keepState) {
                rc.renderResourceCache.remove(entry.value.vertexBufferKey)
                rc.renderResourceCache.remove(entry.value.elementBufferKey)
                it.remove()
            }
        }
    }

    /** Drop this tile's GL buffer entries from the render-resource cache. */
    fun releaseRenderResources(rc: RenderContext) {
        for (td in data.values) {
            rc.renderResourceCache.remove(td.vertexBufferKey)
            rc.renderResourceCache.remove(td.elementBufferKey)
        }
    }

    companion object {
        // Path's outline VBO uses 5 floats per GPU corner-vertex; 4 corners per logical
        // waypoint; the shader interprets the 4th float as the OUTLINE_CORNER_* tag.
        private const val VERTEX_STRIDE = 5

        // Same options Path uses for its outline stipple texture: REPEAT + NEAREST so the
        // dash pattern tiles cleanly without bilinear smear.
        private val defaultOutlineImageOptions = earth.worldwind.render.image.ImageOptions().apply {
            wrapMode = earth.worldwind.render.image.WrapMode.REPEAT
            resamplingMode = earth.worldwind.render.image.ResamplingMode.NEAREST_NEIGHBOR
        }

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

