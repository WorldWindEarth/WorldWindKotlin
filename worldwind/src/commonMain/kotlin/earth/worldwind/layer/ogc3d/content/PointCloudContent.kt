package earth.worldwind.layer.ogc3d.content

import earth.worldwind.draw.DrawContext
import earth.worldwind.formats.ogc3d.PntsPayload
import earth.worldwind.geom.BoundingSphere
import earth.worldwind.geom.Vec3
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.util.FloatArrayPool
import earth.worldwind.util.NumericArray
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER
import kotlin.concurrent.Volatile
import kotlin.math.sqrt

/**
 * GPU-resident point cloud content for a single pnts tile. Single VBO with interleaved
 * `[posX, posY, posZ, colorR, colorG, colorB, colorA]` (7 floats = 28 bytes per point).
 * Color is normalized to [0, 1] floats at parse time to keep the shader path simple.
 *
 * Lifecycle:
 *  1. [preparePointCloudContent] runs off the render thread on the parse coroutine,
 *     interleaves position + colour into [interleavedVertices], and computes the local
 *     bounding sphere.
 *  2. [syncPointCloudContentGpu] runs each render frame, looking up the VBO from the cache
 *     and re-uploading [interleavedVertices] when the cache has dropped it. The CPU-side
 *     payload stays alive on the content for this re-upload path.
 *
 * 4x memory overhead vs packed uint8 RGBA, but the all-float layout means we reuse the
 * existing [NumericArray.Floats] upload pipeline without introducing a Bytes variant.
 */
class PointCloudContent internal constructor(
    /** RTC_CENTER offset to compose with the tile-to-world transform. */
    val rtcCenter: DoubleArray?,
) : TileContent {

    /** Interleaved positions + colours. Pool-backed and released after the first sync
     *  via the `onUploaded` callback on `offerGLBufferUpload`; null thereafter. */
    @Volatile internal var interleavedVertices: FloatArray? = null

    /** Stable cache key for the VBO; populated together with [interleavedVertices]. */
    @Volatile internal var vboKey: PointCloudVboKey? = null

    // GPU [BufferObject] reference intentionally NOT cached on content. The drawable resolves
    // it fresh from the RR cache at enqueue time (see `enqueuePointCloudDrawable`), matching
    // the engine convention used by Mesh / Path / OSM Buildings drawables. Makes
    // stale-ref → wrong-buffer binding structurally impossible: a cache eviction surfaces as
    // a null slot on the drawable; the touch loop catches the miss and triggers re-fetch.

    /** Number of points; populated by [preparePointCloudContent]. */
    @Volatile var pointCount: Int = 0

    /** Flipped to true on the first successful [syncPointCloudContentGpu] call. */
    @Volatile var firstSyncDone: Boolean = false

    /** Tile-local-space bounding sphere — computed during [preparePointCloudContent]. */
    val localBoundingSphere: BoundingSphere = BoundingSphere()

    /** Vertex stride in bytes (28 = 7 floats). */
    val vertexStride: Int get() = VERTEX_STRIDE

    override var gpuByteCount: Int = 0
        internal set

    override fun release(dc: DrawContext) {
        interleavedVertices = null
        vboKey = null
        pointCount = 0
        gpuByteCount = 0
        // The GPU BufferObject is owned by the RR cache, not this content. The cache's
        // eviction queue calls BufferObject.release(dc) on its own schedule.
    }

    companion object {
        const val VERTEX_STRIDE = 28 // 3 + 4 floats
        const val POSITION_OFFSET = 0
        const val COLOR_OFFSET = 12
    }
}

/** Stable cache key for a point cloud's VBO under one content URI. */
internal data class PointCloudVboKey(val contentUri: String)

/**
 * Off-thread point-cloud preparation. Interleaves the parsed pnts positions + colours into
 * one FloatArray, computes the tile-local bounding sphere from the position extremes, and
 * attaches the result to the content. Runs from the parse coroutine — no render-context
 * access; uploads happen later in [syncPointCloudContentGpu].
 *
 * Idempotent: no-op when [PointCloudContent.interleavedVertices] is already set.
 */
internal fun PointCloudContent.preparePointCloudContent(payload: PntsPayload, contentUri: String) {
    if (interleavedVertices != null) return
    val count = payload.pointsLength
    if (count <= 0) return

    val floatsPerVertex = 7
    // Pool buffer may be oversized; pointCount bounds all reads. Released in syncPointCloudContentGpu.
    val interleaved = FloatArrayPool.acquire(count * floatsPerVertex)
    val invByte = 1f / 255f
    var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
    for (i in 0 until count) {
        val base = i * floatsPerVertex
        val px = payload.positions[i * 3 + 0]
        val py = payload.positions[i * 3 + 1]
        val pz = payload.positions[i * 3 + 2]
        interleaved[base + 0] = px
        interleaved[base + 1] = py
        interleaved[base + 2] = pz
        interleaved[base + 3] = (payload.colors[i * 4 + 0].toInt() and 0xFF) * invByte
        interleaved[base + 4] = (payload.colors[i * 4 + 1].toInt() and 0xFF) * invByte
        interleaved[base + 5] = (payload.colors[i * 4 + 2].toInt() and 0xFF) * invByte
        interleaved[base + 6] = (payload.colors[i * 4 + 3].toInt() and 0xFF) * invByte
        if (px < minX) minX = px; if (px > maxX) maxX = px
        if (py < minY) minY = py; if (py > maxY) maxY = py
        if (pz < minZ) minZ = pz; if (pz > maxZ) maxZ = pz
    }

    val cx = (minX + maxX) * 0.5; val cy = (minY + maxY) * 0.5; val cz = (minZ + maxZ) * 0.5
    val hx = (maxX - minX) * 0.5; val hy = (maxY - minY) * 0.5; val hz = (maxZ - minZ) * 0.5
    val r = sqrt(hx * hx + hy * hy + hz * hz)
    localBoundingSphere.set(Vec3(cx, cy, cz), r)

    interleavedVertices = interleaved
    vboKey = PointCloudVboKey(contentUri)
    pointCount = count
}

/**
 * Per-frame render-thread VBO sync. Mirrors [syncMeshContentGpu] for the single-VBO point
 * cloud layout: cache get-or-create, version-checked upload, store the resolved buffer in
 * [PointCloudContent.vertexBuffer].
 */
internal fun PointCloudContent.syncPointCloudContentGpu(rc: RenderContext) {
    val interleaved = interleavedVertices ?: return
    val key = vboKey ?: return
    val totalBytes = pointCount * PointCloudContent.VERTEX_STRIDE
    // Ensure the cache entry exists; the drawable resolves the live ref at enqueue time.
    rc.getBufferObject(key) { BufferObject(GL_ARRAY_BUFFER, totalBytes) }
    // Explicit byteCount because `interleaved` may be oversized (pool). onUploaded fires
    // exactly once — async after the GL write, or sync here when nothing was queued.
    rc.offerGLBufferUpload(key, 1, onUploaded = { FloatArrayPool.release(interleaved) }) {
        NumericArray.Floats(interleaved, totalBytes)
    }
    gpuByteCount = totalBytes
    firstSyncDone = true
    interleavedVertices = null
}

/** Pre-first-sync tiles ([interleavedVertices] populated) count as loaded — sync runs
 *  from `enqueuePointCloudDrawable` *after* the layer's touch loop. */
internal fun PointCloudContent.isResourcesLoaded(rc: RenderContext): Boolean {
    if (interleavedVertices != null) return true
    val key = vboKey ?: return false
    return rc.renderResourceCache.containsKey(key)
}

/** Strict ready check for the traverser: only the post-sync state counts. Otherwise the
 *  parent fallback gets dropped during the sync window and the area blinks empty. */
internal fun PointCloudContent.isReadyForRefinement(rc: RenderContext): Boolean {
    if (!firstSyncDone) return false
    val key = vboKey ?: return false
    return rc.renderResourceCache.containsKey(key)
}

internal fun PointCloudContent.touchCache(rc: RenderContext) {
    vboKey?.let { rc.renderResourceCache[it] }
}
