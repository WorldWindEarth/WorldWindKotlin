package earth.worldwind.layer.ogc3d.content

import earth.worldwind.draw.DrawContext
import earth.worldwind.formats.ogc3d.BatchTable
import earth.worldwind.geom.BoundingSphere
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import earth.worldwind.render.buffer.BufferObject
import kotlin.concurrent.Volatile

/**
 * Mesh content descriptor for one tile (b3dm / i3dm / cmpt-of-meshes / raw glTF). Thin
 * descriptors only — bulk data (VBOs, EBOs, textures, batch-id buffers) lives in
 * [RenderContext.renderResourceCache] keyed by stable strings.
 */
class MeshContent internal constructor(
    /** b3dm `RTC_CENTER`. */
    val rtcCenter: DoubleArray?,
    /** i3dm per-instance transforms. Null for b3dm. */
    val instancePositions: FloatArray? = null,
    val instanceScales: FloatArray? = null,
    val instancesLength: Int = 0,
    /** Skip the standard Y-up→Z-up rotation at draw time. Default false applies the
     *  spec's `Rx(+90°)`; set true for content whose vertices are already in the tile-
     *  local frame (3D Tiles 1.1 / Google Photorealistic). */
    val skipYUpToZUp: Boolean = false,
    /** b3dm `BATCH_LENGTH`. 0 for un-batched. */
    val batchLength: Int = 0,
    /** Parsed b3dm batch table for per-feature picking. */
    val batchTable: BatchTable? = null,
) : TileContent {

    /** Populated by [uploadMeshContent] on the render thread. */
    @Volatile var submeshes: List<MeshSubmesh>? = null

    /** Tile-local bounding sphere; populated by [prepareMeshPrep]. */
    val localBoundingSphere: BoundingSphere = BoundingSphere()

    override var gpuByteCount: Int = 0
        internal set

    /** Pin every owned RR-cache resource at the head of the LRU. */
    fun touchCache(rc: RenderContext) {
        submeshes?.forEach { it.touchCache(rc) }
    }

    /** True iff every submesh is GPU-resident — drawing performs no upload work (see
     *  [MeshSubmesh.isGpuResident]). Empty submesh lists count as loaded — placeholder
     *  tiles with no renderable primitives would otherwise re-fetch forever. NOT an
     *  eviction probe (see [isCacheResident]): freshly-installed content awaiting its
     *  budgeted uploads reports false here yet must NOT be discarded. */
    fun isResourcesLoaded(rc: RenderContext): Boolean {
        val list = submeshes ?: return false
        for (submesh in list) if (!submesh.isGpuResident()) return false
        return true
    }

    /** True iff every submesh's resources are still RR-cache-resident (uploaded or not).
     *  False means eviction took the data — content must be re-fetched. */
    fun isCacheResident(rc: RenderContext): Boolean {
        val list = submeshes ?: return false
        for (submesh in list) if (!submesh.isCacheResident(rc)) return false
        return true
    }

    override fun release(dc: DrawContext) {
        submeshes = null
        gpuByteCount = 0
    }
}

/** Per-glTF-primitive draw descriptor. Holds a single RR-cache key for the combined
 *  vertices+indices+batchIds buffer plus a separate texture key. Draw metadata only. */
class MeshSubmesh internal constructor(
    val worldMatrix: Matrix4,
    /** Combined buffer: vertices | indices | batchIds. Bound to GL_ARRAY_BUFFER for the
     *  vertex attribs and (where [elementBufferKey] is null) to GL_ELEMENT_ARRAY_BUFFER for
     *  `drawElements` via [BufferObject.bindBufferAs]. Single mmap per primitive instead of
     *  three. */
    val bufferKey: Any,
    /** Separate index buffer key, non-null only on backends that forbid binding one buffer to
     *  two targets (WebGL — see [earth.worldwind.util.kgl.Kgl.supportsSharedElementArrayBuffer]).
     *  When null, indices live inside [bufferKey]'s combined buffer at [elementOffset]. */
    val elementBufferKey: Any? = null,
    /** Null when the material has no texture or the decoder failed. */
    val baseColorTextureKey: Any?,
    /** Byte offset of the index section. Passed to `glDrawElements` as the `indices` argument.
     *  Into the combined buffer (shared path) or 0 into the dedicated [elementBuffer] (split). */
    val elementOffset: Int,
    /** Byte offset of the batchId section, or -1 when the primitive has no `_BATCHID`. */
    val batchIdOffset: Int,
    /** RR-cache refs resolved once by [uploadMeshContent]; per-frame drawable enqueue
     *  reads them direct — no HashMap.getNode. Cache still owns lifetime; post-eviction
     *  the GL handles invalidate and bindBuffer/bindTexture returns false. */
    var buffer: BufferObject? = null,
    /** Resolved dedicated index buffer (split path); null on the shared path, where the
     *  combined [buffer] doubles as the element buffer. */
    var elementBuffer: BufferObject? = null,
    var baseColorTexture: Texture? = null,
    val vertexCount: Int,
    val elementCount: Int,
    val vertexStride: Int,
    val positionOffset: Int,
    val normalOffset: Int,
    val texCoordOffset: Int,
    /** Byte offset of per-vertex `COLOR_0` in the interleaved buffer; -1 when absent. */
    val colorOffset: Int,
    /** 3 (RGB) or 4 (RGBA); 0 when absent. */
    val colorComponents: Int,
    val isInt32Indices: Boolean,
    /** RGBA, premultiplied. Multiplied with the sampled texel when a texture is bound. */
    val baseColor: FloatArray,
    val alphaBlend: Boolean,
    val alphaMask: Boolean,
    val alphaCutoff: Float,
    val doubleSided: Boolean,
    /** glTF primitive mode (4=TRIANGLES, 1=LINES, 0=POINTS). */
    val mode: Int,
) {
    fun touchCache(rc: RenderContext) {
        rc.renderResourceCache[bufferKey]
        elementBufferKey?.let { rc.renderResourceCache[it] }
        baseColorTextureKey?.let { rc.renderResourceCache[it] }
    }

    /** True iff every mandatory buffer (combined VBO + any split EBO) is GL-uploaded and the
     *  base-color texture (when the material has one) has its GL texture created — i.e.
     *  drawing this submesh performs no GPU upload work. Gating tile selection on this keeps
     *  texture uploads on the deadline-budgeted eager-bind path instead of stalling the GL
     *  thread at first draw. Reads only the refs resolved at install time — pure field
     *  reads, no cache lookups on this per-tile-per-frame path; eviction is still caught
     *  because release() invalidates the GL handle these checks test. NOT an eviction probe
     *  — a queued-but-not-yet-uploaded resource also reports false; use [isCacheResident]
     *  to distinguish "gone, must re-fetch" from "still uploading". */
    fun isGpuResident(): Boolean {
        val buffer = this.buffer
        if (buffer == null || !buffer.isLoaded()) return false
        if (elementBufferKey != null) {
            val elementBuffer = this.elementBuffer
            if (elementBuffer == null || !elementBuffer.isLoaded()) return false
        }
        if (baseColorTextureKey != null) {
            val texture = this.baseColorTexture
            if (texture == null || !texture.isTextureCreated) return false
        }
        return true
    }

    /** True iff every mandatory resource is still RR-cache-resident, GL-uploaded or not.
     *  This is the eviction probe: false means the data is gone and the tile must re-fetch;
     *  a submesh awaiting its budgeted GL upload still reports true. */
    fun isCacheResident(rc: RenderContext): Boolean =
        rc.renderResourceCache.containsKey(bufferKey) &&
            (elementBufferKey == null || rc.renderResourceCache.containsKey(elementBufferKey)) &&
            (baseColorTextureKey == null || rc.renderResourceCache.containsKey(baseColorTextureKey))
}
