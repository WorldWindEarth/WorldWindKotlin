package earth.worldwind.layer.ogc3d.content

import earth.worldwind.WorldWind
import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.BoundingSphere
import earth.worldwind.geom.Vec3
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.util.NumericArray
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_ELEMENT_ARRAY_BUFFER
import kotlin.concurrent.Volatile
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * GPU-resident container for a single Gaussian-splat 3D Tiles tile. Sealed TileContent
 * variant parallel to [MeshContent] / [PointCloudContent]; the GPU buffer slots reflect
 * what a Gaussian-splat renderer needs (positions, anisotropic scales, rotation
 * quaternions, opacity, and either flat RGBA or view-dependent spherical harmonic
 * coefficients).
 *
 * Lifecycle:
 *  1. [prepareGaussianContent] runs off the render thread on the parse coroutine,
 *     captures per-attribute float arrays, quantizes centres to int16 (for the sort),
 *     computes the bounding sphere.
 *  2. [syncGaussianContentGpu] runs each render frame, looking up each attribute's VBO
 *     from the cache and re-uploading the kept-alive payload when LRU eviction has
 *     dropped it. After the first sync the float CPU arrays are dropped; the int16
 *     centres + the sort indices stay alive to feed [resortByCamera].
 *  3. [resortByCamera] runs each render frame: launches the bucket sort on
 *     [Dispatchers.Default] when the camera direction has moved past the angle gate, then
 *     enqueues the resulting indices for GL upload on the next render frame. Stationary or
 *     slow camera pays nothing; in-flight sorts block restart until the upload drains.
 *
 * A concrete Gaussian-splat codec ([earth.worldwind.layer.ogc3d.content.spz.SpzGaussianLoader])
 * produces a [GaussianPayload]; the built-in rasterizer
 * ([earth.worldwind.layer.ogc3d.draw.DrawableTileGaussian]) renders the splats as GL_POINTS
 * with a Gaussian falloff per fragment.
 */
class GaussianContent internal constructor(
    val rtcCenter: DoubleArray?,
) : TileContent {

    /** Tile-local-space bounding sphere — computed during [prepareGaussianContent]. */
    val localBoundingSphere: BoundingSphere = BoundingSphere()

    // CPU payload held until the first GPU sync, then nulled (see [syncGaussianContentGpu]).
    // Opacities and (so far) spherical-harmonics aren't retained — the anisotropic shader
    // reads alpha from rgba and uses the DC term in rgba for colour.

    /** Interleaved per-splat vertex attributes: 32 B/splat = scale (3 × float, offset 0) +
     *  rgba (4 × uint8 normalized, offset 12) + rotation (4 × float, offset 16). One VBO + one
     *  bindBuffer call per drawable instead of three separate streams. RGB×UInt8 is normalized
     *  to vec4 in [0, 1] by GL. Null when the codec didn't emit a rotation (round-splat mode);
     *  drawable then early-returns. */
    @Volatile internal var attribsBytes: ByteArray? = null
    @Volatile internal var sortIndexArray: IntArray? = null

    /** Int16-quantized centres (3 shorts per splat). The single source of truth for positions:
     *  uploaded to the GPU as GL_SHORT normalized=true and dequantized in the vertex shader
     *  via `qPosCenter`/`qPosHalfRange`, also used by the background sort. Halves persistent
     *  CPU+GPU footprint per splat vs a Float source (12 bytes → 6 bytes each). */
    @Volatile internal var centerArrayQ: ShortArray? = null
    /** Per-axis shader-side dequant: `worldLocal = vertexPosition * qPosHalfRange + qPosCenter`,
     *  where vertexPosition is the normalized [-1,+1] signed int16. */
    @Volatile internal var qPosCenterX: Float = 0f
    @Volatile internal var qPosCenterY: Float = 0f
    @Volatile internal var qPosCenterZ: Float = 0f
    @Volatile internal var qPosHalfRangeX: Float = 0f
    @Volatile internal var qPosHalfRangeY: Float = 0f
    @Volatile internal var qPosHalfRangeZ: Float = 0f
    /** Per-axis quant scale used by the CPU bucket sort to fold the forward direction into a
     *  single MAD chain over the int16 centres. Equals `range / 65535` per axis. */
    @Volatile internal var qScaleX: Float = 0f
    @Volatile internal var qScaleY: Float = 0f
    @Volatile internal var qScaleZ: Float = 0f

    @Volatile internal var centersKey: GaussianCentersVboKey? = null
    @Volatile internal var attribsKey: GaussianAttribsVboKey? = null
    @Volatile internal var sortIndicesKey: GaussianSortIndicesEboKey? = null

    // GPU [BufferObject] references intentionally NOT cached on content. The drawable resolves
    // them fresh from the RR cache at enqueue time (see `enqueueGaussianDrawable`), matching
    // the engine convention used by Mesh / Path / OSM Buildings drawables. This makes
    // stale-ref → wrong-buffer binding structurally impossible: cache eviction surfaces as a
    // null slot on the drawable, the draw skips, and the touch loop on the next frame
    // detects the cache miss and triggers a re-fetch.

    /** Number of splats; populated by [prepareGaussianContent]. */
    @Volatile var splatCount: Int = 0

    /** Flipped to true on the first successful [syncGaussianContentGpu] call. */
    @Volatile var firstSyncDone: Boolean = false

    /** Incremented every time [resortByCamera] uploads new indices so the upload-queue
     *  version-skip lets the write through. */
    @Volatile internal var sortVersion: Int = 1

    /** Last tile-local view-forward direction used for the sort. View-Z proxy order is
     *  invariant under camera translation, so the resort gate is angular, not positional.
     *  NaN sentinel = "never sorted". */
    @Volatile internal var lastSortForwardX: Float = Float.NaN
    @Volatile internal var lastSortForwardY: Float = Float.NaN
    @Volatile internal var lastSortForwardZ: Float = Float.NaN

    /** Set while a background sort coroutine is running OR while its GL upload is queued.
     *  Single-buffered: a fresh resort can't start until both phases finish — the sort would
     *  race the upload's reader, and the upload would lose the previous indices. */
    @Volatile internal var sortJobInFlight: Boolean = false

    /** Set by the background sort coroutine when [sortIndexArray] holds fresh indices and the
     *  GPU upload still needs to be enqueued. Consumed on the next render frame by
     *  [resortByCamera] — the offer must happen on the render thread (it mutates the cache +
     *  upload queue, neither thread-safe). */
    @Volatile internal var sortPending: Boolean = false

    /** 256-entry bucket-counts scratch reused across sorts. */
    @Volatile internal var sortBucketScratch: IntArray? = null

    override var gpuByteCount: Int = 0
        internal set

    override fun release(dc: DrawContext) {
        sortJobInFlight = false
        sortPending = false
        attribsBytes = null; sortIndexArray = null
        centerArrayQ = null
        sortBucketScratch = null
        splatCount = 0
        gpuByteCount = 0
        // GPU BufferObjects are owned by the RR cache (not this content). The cache's
        // eviction queue calls BufferObject.release(dc) on its own schedule.
    }
}

/** Stable cache keys for each Gaussian-splat per-attribute VBO under one content URI. */
internal data class GaussianCentersVboKey(val contentUri: String)
internal data class GaussianAttribsVboKey(val contentUri: String)
internal data class GaussianRgbaVboKey(val contentUri: String)
internal data class GaussianSortIndicesEboKey(val contentUri: String)

/**
 * Off-thread Gaussian-splat preparation. Captures the codec's per-attribute float arrays,
 * computes the static back-to-front sort + tile-local bounding sphere, populates the
 * content's CPU-side payload slots. Runs from the parse coroutine; no render-context access.
 */
internal fun GaussianContent.prepareGaussianContent(payload: GaussianPayload, contentUri: String) {
    if (centerArrayQ != null) return
    val count = payload.splatCount
    if (count <= 0) return

    var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
    for (i in 0 until count) {
        val x = payload.centers[i * 3 + 0]
        val y = payload.centers[i * 3 + 1]
        val z = payload.centers[i * 3 + 2]
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
    }
    val cx = (minX + maxX) * 0.5; val cy = (minY + maxY) * 0.5; val cz = (minZ + maxZ) * 0.5
    val hx = (maxX - minX) * 0.5; val hy = (maxY - minY) * 0.5; val hz = (maxZ - minZ) * 0.5
    val r = sqrt(hx * hx + hy * hy + hz * hz)
    localBoundingSphere.set(Vec3(cx, cy, cz), r)

    centersKey = GaussianCentersVboKey(contentUri)

    // Quantize centres to int16 per axis using the tile bounding box. Resolution =
    // bbox_size / 65535 — for a 500m tile that's ~7.6 mm, far finer than sort order needs.
    // The bbox half-range + centre are the shader-side dequant uniforms.
    val rangeX = (maxX - minX).coerceAtLeast(1e-6f)
    val rangeY = (maxY - minY).coerceAtLeast(1e-6f)
    val rangeZ = (maxZ - minZ).coerceAtLeast(1e-6f)
    qPosCenterX = (minX + maxX) * 0.5f
    qPosCenterY = (minY + maxY) * 0.5f
    qPosCenterZ = (minZ + maxZ) * 0.5f
    qPosHalfRangeX = rangeX * 0.5f
    qPosHalfRangeY = rangeY * 0.5f
    qPosHalfRangeZ = rangeZ * 0.5f
    qScaleX = rangeX / 65535f
    qScaleY = rangeY / 65535f
    qScaleZ = rangeZ / 65535f
    val invSx = 65535f / rangeX
    val invSy = 65535f / rangeY
    val invSz = 65535f / rangeZ
    val q = ShortArray(count * 3)
    for (i in 0 until count) {
        val ix = ((payload.centers[i * 3]     - minX) * invSx).toInt().coerceIn(0, 65535)
        val iy = ((payload.centers[i * 3 + 1] - minY) * invSy).toInt().coerceIn(0, 65535)
        val iz = ((payload.centers[i * 3 + 2] - minZ) * invSz).toInt().coerceIn(0, 65535)
        // Shift to signed int16 range so ShortArray stores it natively.
        q[i * 3]     = (ix - 32768).toShort()
        q[i * 3 + 1] = (iy - 32768).toShort()
        q[i * 3 + 2] = (iz - 32768).toShort()
    }
    centerArrayQ = q

    // Interleaved per-splat vertex attributes — one VBO instead of three. Layout per splat
    // (stride 32 B): scale[3] float at offset 0, rgba[4] uint8 at offset 12, rotation[4]
    // float at offset 16. Built off-thread here; the render-thread sync just hands the
    // ByteArray to the upload queue. Skipped when the codec emitted no rotations (round-
    // splat mode): the drawable early-returns on a null attribs buffer.
    if (payload.rotations.size >= count * 4) {
        val rgba = payload.rgba ?: ByteArray(count * 4) { -0x1 /* opaque white default */ }
        val attribs = ByteArray(count * STRIDE_ATTRIBS)
        val srcScales = payload.scales
        val srcRotations = payload.rotations
        // LE-float bit math inlined into the loop — extension-function dispatch was 20% of
        // parse-storm wall time on debug-build ART when this was a separate `writeLEFloat`
        // helper. 7 float writes × 1M-splat tile compounds quickly.
        for (i in 0 until count) {
            val base = i * STRIDE_ATTRIBS
            val si = i * 3
            val sx = srcScales[si].toRawBits()
            val sy = srcScales[si + 1].toRawBits()
            val sz = srcScales[si + 2].toRawBits()
            attribs[base]      = (sx and 0xFF).toByte()
            attribs[base + 1]  = ((sx ushr 8)  and 0xFF).toByte()
            attribs[base + 2]  = ((sx ushr 16) and 0xFF).toByte()
            attribs[base + 3]  = ((sx ushr 24) and 0xFF).toByte()
            attribs[base + 4]  = (sy and 0xFF).toByte()
            attribs[base + 5]  = ((sy ushr 8)  and 0xFF).toByte()
            attribs[base + 6]  = ((sy ushr 16) and 0xFF).toByte()
            attribs[base + 7]  = ((sy ushr 24) and 0xFF).toByte()
            attribs[base + 8]  = (sz and 0xFF).toByte()
            attribs[base + 9]  = ((sz ushr 8)  and 0xFF).toByte()
            attribs[base + 10] = ((sz ushr 16) and 0xFF).toByte()
            attribs[base + 11] = ((sz ushr 24) and 0xFF).toByte()
            val ri = i * 4
            attribs[base + 12] = rgba[ri]
            attribs[base + 13] = rgba[ri + 1]
            attribs[base + 14] = rgba[ri + 2]
            attribs[base + 15] = rgba[ri + 3]
            val rx = srcRotations[ri].toRawBits()
            val ry = srcRotations[ri + 1].toRawBits()
            val rz = srcRotations[ri + 2].toRawBits()
            val rw = srcRotations[ri + 3].toRawBits()
            attribs[base + 16] = (rx and 0xFF).toByte()
            attribs[base + 17] = ((rx ushr 8)  and 0xFF).toByte()
            attribs[base + 18] = ((rx ushr 16) and 0xFF).toByte()
            attribs[base + 19] = ((rx ushr 24) and 0xFF).toByte()
            attribs[base + 20] = (ry and 0xFF).toByte()
            attribs[base + 21] = ((ry ushr 8)  and 0xFF).toByte()
            attribs[base + 22] = ((ry ushr 16) and 0xFF).toByte()
            attribs[base + 23] = ((ry ushr 24) and 0xFF).toByte()
            attribs[base + 24] = (rz and 0xFF).toByte()
            attribs[base + 25] = ((rz ushr 8)  and 0xFF).toByte()
            attribs[base + 26] = ((rz ushr 16) and 0xFF).toByte()
            attribs[base + 27] = ((rz ushr 24) and 0xFF).toByte()
            attribs[base + 28] = (rw and 0xFF).toByte()
            attribs[base + 29] = ((rw ushr 8)  and 0xFF).toByte()
            attribs[base + 30] = ((rw ushr 16) and 0xFF).toByte()
            attribs[base + 31] = ((rw ushr 24) and 0xFF).toByte()
        }
        attribsBytes = attribs
        attribsKey = GaussianAttribsVboKey(contentUri)
    }

    // payload.opacities + payload.sphericalHarmonics deliberately go out of scope: the
    // shader uses alpha from rgba and the DC colour term only.

    // Back-to-front sort by distance from tile centroid using the int16 centres directly
    // (centroid sits at the origin in centerArrayQ space). O(N) bucket sort + reuses the
    // 1 KB scratch [resortByCamera] will need anyway. Static — the first per-frame
    // [resortByCamera] replaces it with the view-Z proxy order before the next draw.
    val scratch = IntArray(SORT_BUCKETS)
    sortBucketScratch = scratch
    val indices = IntArray(count) { it }
    bucketSortByCentroidDistance(q, indices, count, scratch)
    sortIndexArray = indices
    sortIndicesKey = GaussianSortIndicesEboKey(contentUri)

    splatCount = count
}

/**
 * Per-frame render-thread GPU sync. Ensures each per-tile VBO/EBO exists in the RR cache and
 * queues the corresponding upload. The drawable resolves the live [BufferObject] references
 * from the cache at enqueue time — they are deliberately not cached on this content (see
 * `enqueueGaussianDrawable`). Sync is idempotent post-`firstSyncDone`: cache `get` returns
 * the existing entry, `offerGLBufferUpload` skips when the version matches.
 */
internal fun GaussianContent.syncGaussianContentGpu(rc: RenderContext) {
    val centersQ = this.centerArrayQ ?: return
    val centersKey = this.centersKey ?: return
    var totalBytes = 0
    firstSyncDone = true

    // Centres go up as int16 (6 B/splat) — the shader dequantizes via the qPosCenter /
    // qPosHalfRange uniforms. The same array is the CPU bucket-sort source, so it stays alive.
    val centersBytes = centersQ.size * 2
    rc.getBufferObject(centersKey) { BufferObject(GL_ARRAY_BUFFER, centersBytes) }
    rc.offerGLBufferUpload(centersKey, 1) { NumericArray.Shorts(centersQ) }
    totalBytes += centersBytes

    attribsBytes?.let { arr ->
        val key = attribsKey ?: return@let
        rc.getBufferObject(key) { BufferObject(GL_ARRAY_BUFFER, arr.size) }
        rc.offerGLBufferUpload(key, 1) { NumericArray.Bytes(arr) }
        totalBytes += arr.size
    }
    sortIndexArray?.let { arr ->
        val key = sortIndicesKey ?: return@let
        val bytes = arr.size * 4
        rc.getBufferObject(key) { BufferObject(GL_ELEMENT_ARRAY_BUFFER, bytes) }
        rc.offerGLBufferUpload(key, 1) { NumericArray.Ints(arr) }
        // Budget against gpuByteCount counts both the GPU EBO and the kept-alive CPU IntArray.
        totalBytes += bytes * 2
    }
    // CPU-only buffers the background sort keeps alive ([centerArrayQ] doubles as the GPU
    // upload source so its bytes are already counted in [centersBytes]).
    sortBucketScratch?.let { arr -> totalBytes += arr.size * 4 }
    gpuByteCount = totalBytes

    // Drop CPU refs the GL upload closure already owns. [centerArrayQ] + [sortIndexArray]
    // stay alive for the background sort.
    this.attribsBytes = null
}

/** CPU-only release. Nulls the kept-alive sort state before `tile.content = null` so the
 *  10 b/splat carcass is GC'd without waiting for a pending GL upload to drain. */
internal fun GaussianContent.releaseSortState() {
    sortBucketScratch = null
    centerArrayQ = null
    sortIndexArray = null
    sortJobInFlight = false
    sortPending = false
}

/**
 * Per-frame view-aware bucket sort + GL upload. The sort runs on [gaussianSortScope]
 * (Dispatchers.Default) — on the c4ds Android setup the engine's render phase is on the
 * main UI thread, and a per-tile sort of ~100k splats was the single largest UI-thread
 * cost (~7.7% / 842 ms over a 7 s profile). The GL upload offer still runs on the render
 * thread the frame after the sort completes — [RenderContext.offerGLBufferUpload] mutates
 * the cache + upload queue and is not thread-safe.
 *
 * Single-buffered via [sortJobInFlight]: a new sort can't start while a previous sort or
 * its upload is unfinished — concurrent writes to [sortIndexArray] would corrupt the
 * in-flight upload. Worst-case staleness on fast camera motion: two frames (one for the
 * background sort, one for the upload) — sub-perceptual at typical viewing distances.
 *
 * [fx]/[fy]/[fz] is the tile-local forward-into-scene unit vector. The sort key is the
 * view-Z proxy `(P - C) · F` — same plane-distance metric the depth buffer uses, and the
 * constant `-C · F` drops out so neither camera position nor centre offsets matter for
 * ordering. Saves 3 muls + 3 adds + 3 subs per splat per pass vs squared euclidean.
 */
internal fun GaussianContent.resortByCamera(
    rc: RenderContext, fx: Float, fy: Float, fz: Float,
) {
    val key = sortIndicesKey ?: return

    // Drain a completed background sort first: enqueue its GPU upload on the render thread.
    // Has to land here (not in the bg coroutine) because offerGLBufferUpload mutates
    // renderResourceCache + uploadQueue, neither thread-safe. Stays sortJobInFlight until
    // the upload's `onUploaded` callback fires.
    if (sortPending) {
        val indices = sortIndexArray
        sortPending = false
        if (indices == null) {
            sortJobInFlight = false
            return
        }
        rc.offerGLBufferUpload(key, sortVersion, onUploaded = { sortJobInFlight = false }) {
            NumericArray.Ints(indices)
        }
        return
    }

    if (sortJobInFlight) return

    // View-direction threshold: forward rotated < ~0.2° (~12 arcmin) since the last sort
    // → order unchanged. Tight enough that worst-case staleness stays well below the
    // visible blend-error threshold; loose enough to skip work on stationary camera and
    // slow pan. dot(prev, curr) > cos(0.2°) ≈ 0.9999939.
    val lastFx = lastSortForwardX
    if (!lastFx.isNaN()) {
        val dot = fx * lastFx + fy * lastSortForwardY + fz * lastSortForwardZ
        if (dot > 0.9999939f) return
    }

    val centersQ = centerArrayQ ?: return
    val indices = sortIndexArray ?: return
    val count = splatCount
    if (count <= 0) return
    val scratch = sortBucketScratch ?: IntArray(SORT_BUCKETS).also { sortBucketScratch = it }

    // Update the angle gate + version synchronously so the next-frame guard sees the
    // most-recent-requested direction (not the most-recently-completed one) and so the
    // upload-queue version check on drain matches the sort that produced these indices.
    lastSortForwardX = fx
    lastSortForwardY = fy
    lastSortForwardZ = fz
    sortVersion++

    // Fold the per-axis quant scale into the forward direction once, outside the hot loop.
    // The quant offset and `-C · F` only shift the key by a constant; ordering is unchanged.
    val fxs = fx * qScaleX
    val fys = fy * qScaleY
    val fzs = fz * qScaleZ

    sortJobInFlight = true
    gaussianSortScope.launch {
        bucketSortByViewZ(centersQ, indices, count, fxs, fys, fzs, scratch)
        sortPending = true
        // c4ds Android renders on-demand. Nudge the engine so the next frame fires and
        // the upload-offer drain path above gets a chance to run.
        WorldWind.requestRedraw()
    }
}

/** Detached scope for off-thread Gaussian view-Z sorts. Each job is short-lived (a few ms
 *  for ~100k splats), CPU-bound, and idempotent — no lifecycle coupling to layer / content
 *  release is needed (the result lands in fields the next render frame either consumes or
 *  ignores, and `release()` clears [GaussianContent.sortJobInFlight] +
 *  [GaussianContent.sortPending] so a fresh content can restart). `Dispatchers.Default`'s
 *  CPU-cores-bound pool naturally caps concurrency across many splat tiles. */
private val gaussianSortScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** 256-bucket counting sort over the signed view-Z proxy, O(N) and cache-linear. Bucket 0
 *  = farthest, 255 = nearest; the EBO consumes indices in increasing order so the draw goes
 *  back-to-front. [fxs]/[fys]/[fzs] are the forward direction with the per-axis quant scale
 *  pre-folded in, so the inner loop is one MAD chain over the int16 centres without a dequant
 *  step. [counts] is a caller-owned scratch reused across sorts. */
private fun bucketSortByViewZ(
    centersQ: ShortArray, indices: IntArray, count: Int,
    fxs: Float, fys: Float, fzs: Float,
    counts: IntArray,
) {
    var minZ = Float.POSITIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    for (i in 0 until count) {
        val i3 = i * 3
        val z = centersQ[i3]    .toInt() * fxs +
                centersQ[i3 + 1].toInt() * fys +
                centersQ[i3 + 2].toInt() * fzs
        if (z < minZ) minZ = z
        if (z > maxZ) maxZ = z
    }
    val invRange = if (maxZ > minZ) 255f / (maxZ - minZ) else 0f

    for (b in 0 until SORT_BUCKETS) counts[b] = 0

    val lastBucket = SORT_BUCKETS - 1
    for (i in 0 until count) {
        val i3 = i * 3
        val z = centersQ[i3]    .toInt() * fxs +
                centersQ[i3 + 1].toInt() * fys +
                centersQ[i3 + 2].toInt() * fzs
        // Inline clamp — kotlin.coerceIn isn't @inline so showed as 6% of self-time when 3N×per-sort.
        val raw = ((z - minZ) * invRange).toInt()
        val q = if (raw < 0) 0 else if (raw > lastBucket) lastBucket else raw
        counts[lastBucket - q]++
    }

    var sum = 0
    for (b in 0 until SORT_BUCKETS) {
        val c = counts[b]
        counts[b] = sum
        sum += c
    }

    for (i in 0 until count) {
        val i3 = i * 3
        val z = centersQ[i3]    .toInt() * fxs +
                centersQ[i3 + 1].toInt() * fys +
                centersQ[i3 + 2].toInt() * fzs
        val raw = ((z - minZ) * invRange).toInt()
        val q = if (raw < 0) 0 else if (raw > lastBucket) lastBucket else raw
        val bucket = lastBucket - q
        indices[counts[bucket]++] = i
    }
}

private const val SORT_BUCKETS = 256

/** Interleaved per-splat vertex-attribute stride: scale(3xfloat=12) + rgba(4xbyte=4) +
 *  rotation(4xfloat=16) = 32 bytes. See `DrawableTileGaussian.draw` for the matching
 *  `vertexAttribPointer` offsets; the bit math that builds it is inlined into
 *  [prepareGaussianContent] to avoid extension-fn dispatch on the per-splat hot loop. */
internal const val STRIDE_ATTRIBS = 32

/** 256-bucket counting sort by Euclidean distance from the tile centroid in centerArrayQ
 *  space — centroid is approximately the origin since centres are quantised around the
 *  tile bbox midpoint. Bucket 0 = farthest, 255 = nearest; EBO consumes increasing buckets
 *  for a back-to-front draw. Shape matches [bucketSortByViewZ]; sort key differs. */
private fun bucketSortByCentroidDistance(
    centersQ: ShortArray, indices: IntArray, count: Int, counts: IntArray,
) {
    var minD = Float.POSITIVE_INFINITY
    var maxD = Float.NEGATIVE_INFINITY
    for (i in 0 until count) {
        val i3 = i * 3
        val dx = centersQ[i3]    .toInt().toFloat()
        val dy = centersQ[i3 + 1].toInt().toFloat()
        val dz = centersQ[i3 + 2].toInt().toFloat()
        val d = dx * dx + dy * dy + dz * dz
        if (d < minD) minD = d
        if (d > maxD) maxD = d
    }
    val invRange = if (maxD > minD) 255f / (maxD - minD) else 0f

    for (b in 0 until SORT_BUCKETS) counts[b] = 0

    val lastBucket = SORT_BUCKETS - 1
    for (i in 0 until count) {
        val i3 = i * 3
        val dx = centersQ[i3]    .toInt().toFloat()
        val dy = centersQ[i3 + 1].toInt().toFloat()
        val dz = centersQ[i3 + 2].toInt().toFloat()
        val d = dx * dx + dy * dy + dz * dz
        val raw = ((d - minD) * invRange).toInt()
        val q = if (raw < 0) 0 else if (raw > lastBucket) lastBucket else raw
        counts[lastBucket - q]++
    }

    var sum = 0
    for (b in 0 until SORT_BUCKETS) {
        val c = counts[b]
        counts[b] = sum
        sum += c
    }

    for (i in 0 until count) {
        val i3 = i * 3
        val dx = centersQ[i3]    .toInt().toFloat()
        val dy = centersQ[i3 + 1].toInt().toFloat()
        val dz = centersQ[i3 + 2].toInt().toFloat()
        val d = dx * dx + dy * dy + dz * dz
        val raw = ((d - minD) * invRange).toInt()
        val q = if (raw < 0) 0 else if (raw > lastBucket) lastBucket else raw
        val bucket = lastBucket - q
        indices[counts[bucket]++] = i
    }
}

/** Pre-sync (CPU populated) OR post-sync (every VBO still cached) counts as loaded. Used by
 *  the layer's touch loop. The RR-cache evicts buffers independently when memory pressure
 *  hits — every key has to be checked, otherwise a partial eviction leaves the drawable
 *  binding stale `BufferObject`s whose GL names were deleted or reused for someone else's
 *  data, producing visibly corrupted splats (oversized / wrong-orientation / wrong-colour). */
internal fun GaussianContent.isResourcesLoaded(rc: RenderContext): Boolean {
    if (centerArrayQ != null) return true
    val centersKey = this.centersKey ?: return false
    val attribsKey = this.attribsKey ?: return false
    val sortIndicesKey = this.sortIndicesKey ?: return false
    val cache = rc.renderResourceCache
    return cache.containsKey(centersKey) &&
        cache.containsKey(attribsKey) &&
        cache.containsKey(sortIndicesKey)
}

/** Strict ready check for the traverser: only the post-sync state counts AND every VBO
 *  must still be cached. Otherwise the parent fallback gets dropped during the sync window
 *  and the area blinks empty (firstSyncDone false), or a partial eviction lets the traverser
 *  refine into a tile whose drawable would render garbage (any cache miss). */
internal fun GaussianContent.isReadyForRefinement(rc: RenderContext): Boolean {
    if (!firstSyncDone) return false
    val centersKey = this.centersKey ?: return false
    val attribsKey = this.attribsKey ?: return false
    val sortIndicesKey = this.sortIndicesKey ?: return false
    val cache = rc.renderResourceCache
    return cache.containsKey(centersKey) &&
        cache.containsKey(attribsKey) &&
        cache.containsKey(sortIndicesKey)
}

/** Bump every VBO key to the LRU head. */
internal fun GaussianContent.touchCache(rc: RenderContext) {
    centersKey?.let { rc.renderResourceCache[it] }
    attribsKey?.let { rc.renderResourceCache[it] }
    sortIndicesKey?.let { rc.renderResourceCache[it] }
}
