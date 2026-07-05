package earth.worldwind.layer.mvt

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.BoundingBox
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
import earth.worldwind.util.FloatList
import earth.worldwind.util.IntList
import earth.worldwind.util.NumericArray
import earth.worldwind.util.Earcut
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_ELEMENT_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_TRIANGLES
import earth.worldwind.util.kgl.GL_UNSIGNED_INT
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.Volatile
import kotlin.math.abs
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
 * Each feature's outer ring + holes are triangulated by [earcut] (ear-clipping) — the triangulator
 * Mapbox/MapLibre use for MVT fills; far cheaper than a GLU sweep-line tessellator (no half-edge
 * mesh allocation). earcut normalises winding itself, so input rings need no particular orientation.
 */
class MvtBatchedPolygonTile(
    private val features: List<BatchFeature>,
    private val boundingSector: Sector,
    /** Absolute minimum hole area in degrees² (≈ one screen pixel at the tile's zoom); holes smaller
     *  than this are dropped as sub-pixel. `0` falls back to the legacy area-relative-to-outer gate.
     *  An absolute (pixel-scale) floor is correct where a relative one is not: 2e-4 of a long river is
     *  a *visible* island, so the relative gate fills real islands in as water. */
    private val minHoleAreaDeg2: Double = 0.0,
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
    class BatchFeature(
        /** Outer ring as flat interleaved `[lon°, lat°, …]` (degrees) — fed straight to earcut and
         *  the VBO. Emptied after assembly (the VBO holds it; pick path needs only [pickPayload]). */
        var outer: DoubleArray,
        /** Hole rings, each flat interleaved `[lon°, lat°, …]`. Emptied after assembly. */
        var holes: List<DoubleArray>,
        val attributes: ShapeAttributes,
        val zOrder: Int = 0,
        /**
         * If non-null and the layer is pick-enabled, this feature draws separately with a
         * unique pick color in pick mode and exposes [pickPayload] via [PickedObject.userObject].
         */
        val pickPayload: MvtPickedFeature? = null,
    )

    // One TileData for the whole tile: geometry is degree-space (globe-independent), so it's
    // assembled once and reused for every globe state — no per-state duplicates to retain.
    private val tileData = TileData()
    private val boundingBox = BoundingBox()
    private var bufferDataVersion = 0
    private val vertices = FloatList()
    // Bucket key: high 32 bits = signed Int zOrder, low 32 bits = colorPacked. Sorting features
    // by zOrder before assembly makes [LinkedHashMap]'s insertion order match z-ascending
    // automatically; same-color features at different z's still land in distinct buckets so an
    // intervening feature can paint between them.
    private val perZColorElements = LinkedHashMap<Long, IntList>()
    private var currentElements: IntList = IntList()
    private val vertexOrigin = Vec3()
    private var topIndexBase = 0

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
        // @Volatile: written under [assembleLock] off-thread, read by the render thread's doRender guard.
        @Volatile
        var refreshGeometry = true
    }

    private class ColorRange(val colorPacked: Int, val offset: Int, val count: Int)
    private class FeatureLocalRange(val featureIndex: Int, val bucketKey: Long, val localOffset: Int, val localCount: Int)
    // Scratch list filled during assembleGeometry, drained on EBO concat.
    private val featureLocalRanges = ArrayList<FeatureLocalRange>()
    private val scratchPickColor = Color()
    // Reused earcut output across a tile's features (cleared per call) — no IntList per feature.
    private val scratchTris = IntList()
    // Reused SoA earcut arena across this tile's features — zero per-vertex allocation; released
    // (arrays freed) once assembleGeometry finishes so the largest feature isn't pinned per tile.
    private val earcut = Earcut()
    // Serialises assembleGeometry across the off-thread assemble() and the render-thread doRender()
    // fallback. The SoA earcut arena + vertex/element buffers are shared mutable state, so two
    // concurrent assembleGeometry() calls stomp each other's node links and emit triangles to the
    // wrong vertices — stray geometry spiking across the map. tryLock() is non-suspending + atomic.
    private val assembleLock = Mutex()

    /** Build the geometry once; if another thread already holds the lock, return without touching the
     *  shared buffers (the caller treats geometry as not-yet-ready and skips this frame). */
    private fun assembleOnceIfNeeded() {
        if (!tileData.refreshGeometry) return
        if (!assembleLock.tryLock()) return
        try {
            if (tileData.refreshGeometry) {
                assembleGeometry(tileData)
                tileData.refreshGeometry = false
                freeSourceCoords()
            }
        } finally {
            assembleLock.unlock()
        }
    }

    /**
     * Pre-assemble this tile's geometry off the render thread (one assembler per instance at a time).
     * [doRender] lazy-assembles as a fallback. [globe]/[globeState] are unused — vertices are
     * degree-space, so one assembly serves every globe state; kept for call-site symmetry.
     */
    @Suppress("UNUSED_PARAMETER")
    fun assemble(globe: Globe, globeState: Globe.State?) {
        assembleOnceIfNeeded()
    }

    override fun doRender(rc: RenderContext) {
        // Lazy fallback when the layer hadn't captured a globe at fetch time. Guarded so it can't race
        // the off-thread assemble(); if geometry still isn't ready (another thread is assembling), skip
        // this frame — a coarser ancestor covers the gap (progressive refinement). Drawing now would
        // read a half-written vertex/element buffer.
        assembleOnceIfNeeded()
        if (tileData.refreshGeometry || tileData.vertexArray.isEmpty()) return

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
            val drawable = lazyPickDrawable(rc, tileData)
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

    private fun lazyPickDrawable(rc: RenderContext, tileData: TileData): DrawableSurfaceShape {
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

    private fun assembleGeometry(tileData: TileData) {
        ++bufferDataVersion
        vertices.clear()
        perZColorElements.clear()
        featureLocalRanges.clear()
        currentBucketValid = false

        // outer is flat (lon,lat pairs): a ring needs >= 3 verts = 6 doubles. Anchor = first vertex.
        val anchor = features.firstOrNull { it.outer.size >= 6 }?.outer
        if (anchor == null) {
            tileData.vertexArray = FloatArray(0)
            tileData.elementArray = IntArray(0)
            tileData.colorRanges = emptyList()
            tileData.featureElementRanges = IntArray(0)
            return
        }
        // Degrees (lon, lat) to match the surface compositor's texture-space MVP — it maps
        // geographic degrees to the terrain-tile texture; per-vertex floats are degree deltas.
        // (ECEF metres would project millions of units outside the texture → no fill rasterised.)
        vertexOrigin.set(anchor[0], anchor[1], 0.0)
        tileData.vertexOrigin.copy(vertexOrigin)

        // Pre-size the vertex buffer to the tile's whole vertex count so the per-feature pushes
        // don't trigger the incremental array-growth copyOf storm.
        var tileVerts = 0
        for (fi in features.indices) {
            val f = features[fi]
            if (f.outer.size >= 6) tileVerts += f.outer.size / 2
            val fHoles = f.holes
            for (hi in fHoles.indices) { val h = fHoles[hi]; if (h.size >= 6) tileVerts += h.size / 2 }
        }
        vertices.ensureCapacity(tileVerts * VERTEX_STRIDE)

        // Stable sort by zOrder ascending; ties keep the MVT server's intra-layer order. Build
        // an index-back-map so we can record per-feature ranges against the ORIGINAL
        // [features] list (the order the layer hands out pickPayloads in).
        val originalIndices = features.indices.toMutableList()
        if (features.size > 1) originalIndices.sortBy { features[it].zOrder }
        for (oi in originalIndices.indices) {
            val idx = originalIndices[oi]
            val f = features[idx]
            if (f.outer.size < 6) continue
            assembleFeature(f, idx)
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
        for (fri in featureLocalRanges.indices) {
            val r = featureLocalRanges[fri]
            val base = bucketGlobalOffset[r.bucketKey] ?: continue
            flat[r.featureIndex * 2] = base + r.localOffset
            flat[r.featureIndex * 2 + 1] = r.localCount
        }
        tileData.vertexArray = vertices.toFloatArray()
        tileData.elementArray = finalElements
        tileData.colorRanges = ranges
        tileData.featureElementRanges = flat

        // Compositor frustum-tests against boundingSector, not boundingBox (degree-space verts
        // have no meaningful ECEF box). Match MvtBatchedLineTile / Path's surface branch.
        boundingBox.setToUnitBox()

        // Drop the working buffers — they pin ~the same memory as the final arrays.
        vertices.shrink()
        perZColorElements.clear()
        featureLocalRanges.clear()
        earcut.release() // free the tessellation arena — not needed until this tile re-assembles
    }

    private fun assembleFeature(feature: BatchFeature, featureIndex: Int) {
        val colorPacked = packColor(feature.attributes.interiorColor)
        selectBucket(feature.zOrder, colorPacked)
        val bucketKey = currentBucketKey
        val localOffset = currentElements.size

        val outer = feature.outer                 // flat [lon°, lat°, …]
        val outerN = outer.size / 2               // vertex count
        if (outerN < 3) return
        // A hole must lie inside its outer ring. The MVT decode attaches each interior ring to the most
        // recent exterior, so a misclassified ring (non-spec winding) or a dropped exterior can leave a
        // hole that actually belongs to a DIFFERENT, distant polygon. Bridging such a hole fans a
        // triangle across the gap — the "spike across the ocean" at globe scale. Drop any hole whose
        // bbox is disjoint from the outer's (a real hole always overlaps it); fractional quantization
        // overshoots still overlap and are kept.
        var oMinX = outer[0]; var oMaxX = outer[0]; var oMinY = outer[1]; var oMaxY = outer[1]
        var oArea2 = 0.0 // twice the signed outer-ring area (shoelace), for the relative hole-size gate
        run {
            var jx = outer[(outerN - 1) * 2]; var jy = outer[(outerN - 1) * 2 + 1]
            var k = 0
            while (k < outerN) {
                val x = outer[k * 2]; val y = outer[k * 2 + 1]
                if (x < oMinX) oMinX = x; if (x > oMaxX) oMaxX = x
                if (y < oMinY) oMinY = y; if (y > oMaxY) oMaxY = y
                oArea2 += jx * y - x * jy
                jx = x; jy = y; k++
            }
        }
        // Drop a hole if it can't be a real interior ring of THIS outer: its bbox is disjoint (a foreign
        // ring → earcut bridges across the gap), OR its area is below ~2e-4 of the outer's. Real OSM ocean
        // tiles carry dozens of sub-pixel island holes clustered tightly; earcut's left-to-right hole
        // bridges then interfere and emit one triangle spanning the whole polygon (the "fan" across the
        // ocean). Their fill is invisible at that zoom; the fan is not. (Verified on the z4 NE-Brazil tile:
        // dropping holes < 2e-4·outer takes the triangulated/true area ratio from 1.0033 back to 1.0000.)
        // <0 disables the area gate (keep every bbox-overlapping hole); >0 is an absolute pixel-scale
        // floor; 0 is the legacy fraction-of-outer gate (correct only when outer area ≈ the viewport).
        val minHoleArea = when {
            minHoleAreaDeg2 < 0.0 -> 0.0
            minHoleAreaDeg2 > 0.0 -> minHoleAreaDeg2
            else -> abs(oArea2) * 0.5 * 2e-4
        }
        // Common case is hole-less; skip the filter (and its empty-list alloc) entirely then.
        val validHoles = if (feature.holes.isEmpty()) emptyList() else feature.holes.filter { h ->
            if (h.size < 6) return@filter false
            val hn = h.size / 2
            var hMinX = h[0]; var hMaxX = h[0]; var hMinY = h[1]; var hMaxY = h[1]
            var hArea2 = 0.0
            var jx = h[(hn - 1) * 2]; var jy = h[(hn - 1) * 2 + 1]
            var k = 0
            while (k < hn) {
                val x = h[k * 2]; val y = h[k * 2 + 1]
                if (x < hMinX) hMinX = x; if (x > hMaxX) hMaxX = x
                if (y < hMinY) hMinY = y; if (y > hMaxY) hMaxY = y
                hArea2 += jx * y - x * jy
                jx = x; jy = y; k++
            }
            hMaxX >= oMinX && hMinX <= oMaxX && hMaxY >= oMinY && hMinY <= oMaxY &&
                (minHoleArea <= 0.0 || abs(hArea2) * 0.5 > minHoleArea)
        }

        // Push outer-ring then hole vertices to the VBO in the SAME order their (lon°, lat°) goes
        // into the earcut coord array, so an earcut vertex ordinal `j` is VBO index topIndexBase+j.
        topIndexBase = vertices.size / VERTEX_STRIDE
        for (i in 0 until outerN) pushLocalVertex(outer[i * 2], outer[i * 2 + 1])
        for (vhi in validHoles.indices) {
            val hole = validHoles[vhi]
            val hn = hole.size / 2
            for (i in 0 until hn) pushLocalVertex(hole[i * 2], hole[i * 2 + 1])
        }

        // Hole-less (the common case): the flat outer ring IS the earcut input — no coords array.
        // With holes: concat outer+holes into one array (earcut keys off data.size, so it must fit
        // exactly). earcut writes triangle ordinals into the reused scratchTris.
        val tris = if (validHoles.isEmpty()) {
            earcut.triangulate(outer, null, 2, scratchTris)
        } else {
            var combinedVerts = outerN
            for (vhi in validHoles.indices) combinedVerts += validHoles[vhi].size / 2
            val coords = DoubleArray(combinedVerts * 2)
            outer.copyInto(coords, 0)
            val holeIndices = IntArray(validHoles.size)
            var dst = outer.size
            var vOff = outerN
            for (k in validHoles.indices) {
                val hole = validHoles[k]
                holeIndices[k] = vOff
                hole.copyInto(coords, dst)
                dst += hole.size
                vOff += hole.size / 2
            }
            earcut.triangulate(coords, holeIndices, 2, scratchTris)
        }
        var t = 0
        while (t < tris.size) {
            emitTriangle(topIndexBase + tris[t], topIndexBase + tris[t + 1], topIndexBase + tris[t + 2])
            t += 3
        }

        val count = currentElements.size - localOffset
        if (count > 0) featureLocalRanges += FeatureLocalRange(featureIndex, bucketKey, localOffset, count)
    }

    private var currentBucketKey: Long = 0L
    private var currentBucketValid = false // cache: skip the boxed-Long map lookup for a run of same-bucket features

    private fun selectBucket(zOrder: Int, colorPacked: Int) {
        // High 32 bits: signed-int zOrder. Low 32 bits: colorPacked treated as unsigned. The
        // `& 0xFFFFFFFFL` keeps a negative-bit-pattern color from sign-extending into the
        // zOrder half and producing collisions.
        val key = (zOrder.toLong() shl 32) or (colorPacked.toLong() and 0xFFFFFFFFL)
        if (currentBucketValid && key == currentBucketKey) return // same bucket as the previous feature — reuse, skip the boxed lookup
        currentBucketKey = key
        currentElements = perZColorElements.getOrPut(key) { IntList() }
        currentBucketValid = true
    }

    private fun emitTriangle(v0: Int, v1: Int, v2: Int) {
        currentElements.add(v0); currentElements.add(v1); currentElements.add(v2)
    }

    private fun pushLocalVertex(lonDeg: Double, latDeg: Double) {
        // Degree deltas from the tile's degree-space [vertexOrigin] — the surface compositor
        // reprojects (lon°, lat°) onto the terrain. Altitude 0; off-thread safe (no globe math).
        vertices.add((lonDeg - vertexOrigin.x).toFloat())
        vertices.add((latDeg - vertexOrigin.y).toFloat())
        vertices.add(0f)
    }

    /** Approximate GPU byte footprint (VBO + EBO) for LRU byte-weighting. Once assembled it's the
     *  exact array bytes; before assembly it estimates from source vertex counts (VERTEX_STRIDE
     *  floats/vertex for the VBO + ~3 triangle indices/vertex for the EBO, 4 bytes each). */
    val gpuByteEstimate: Int get() = if (!tileData.refreshGeometry) {
        (tileData.vertexArray.size + tileData.elementArray.size) * Int.SIZE_BYTES
    } else {
        var verts = 0
        for (fi in features.indices) {
            val f = features[fi]
            verts += f.outer.size / 2
            for (hi in f.holes.indices) verts += f.holes[hi].size / 2
        }
        verts * (VERTEX_STRIDE + 3) * Int.SIZE_BYTES
    }

    /**
     * Drop this tile's GL buffer entries from [earth.worldwind.render.RenderResourceCache] for
     * every cached globe state. Called by [MvtVectorLayer] on LRU eviction so the kernel
     * reclaims GPU pages eagerly instead of waiting for cache pressure.
     */
    fun releaseRenderResources(rc: RenderContext) {
        rc.renderResourceCache.remove(tileData.vertexBufferKey)
        rc.renderResourceCache.remove(tileData.elementBufferKey)
    }

    // The VBO/EBO are built, so the per-feature degree-coord arrays are dead weight — and the
    // dominant per-tile heap cost across the resident set (was driving blocking-GC stalls during
    // horizon pans). Geometry is globe-independent (one TileData, never re-assembled), so dropping
    // them is safe; the pick path keeps each feature's pickPayload.
    private fun freeSourceCoords() {
        for (fi in features.indices) { val f = features[fi]; f.outer = EMPTY_RING; f.holes = emptyList() }
    }

    companion object {
        // Three local-space floats per vertex (x, y, z). The TriangleShaderProgram still
        // expects a texCoord attribute pointer, but we set texCoordAttrib.size = 1 and let
        // it alias the first float of position — no texture is sampled.
        private const val VERTEX_STRIDE = 3

        // Shared empty ring assigned to a feature's coords once they're folded into the VBO.
        private val EMPTY_RING = DoubleArray(0)

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
