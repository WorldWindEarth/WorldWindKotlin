package earth.worldwind.layer.ogc3d.content

import earth.worldwind.draw.DrawContext
import earth.worldwind.formats.ogc3d.BatchTable
import earth.worldwind.geom.BoundingSphere
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.RenderContext
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

    /** True iff every mandatory buffer is still RR-cache-resident. Empty submesh lists
     *  count as loaded — placeholder tiles with no renderable primitives would otherwise
     *  re-fetch forever. */
    fun isResourcesLoaded(rc: RenderContext): Boolean {
        val list = submeshes ?: return false
        for (submesh in list) if (!submesh.areBuffersLoaded(rc)) return false
        return true
    }

    override fun release(dc: DrawContext) {
        submeshes = null
        gpuByteCount = 0
    }
}

/** Per-glTF-primitive draw descriptor. RR-cache keys + draw metadata only. */
class MeshSubmesh internal constructor(
    val worldMatrix: Matrix4,
    val vboKey: Any,
    /** Null for non-indexed primitives. */
    val eboKey: Any?,
    /** Null when the material has no texture or the decoder failed. */
    val baseColorTextureKey: Any?,
    /** Null when the primitive has no `_BATCHID` — falls back to tile-level picking. */
    val batchIdKey: Any?,
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
        rc.renderResourceCache[vboKey]
        eboKey?.let { rc.renderResourceCache[it] }
        baseColorTextureKey?.let { rc.renderResourceCache[it] }
        batchIdKey?.let { rc.renderResourceCache[it] }
    }

    /** True iff VBO + EBO (if indexed) are still RR-cache-resident. Texture is optional
     *  (untextured photogrammetry tilesets use per-vertex COLOR_0 instead). Batch-id is
     *  optional (per-feature picking gracefully falls back to tile-level when absent). */
    fun areBuffersLoaded(rc: RenderContext): Boolean {
        if (!rc.renderResourceCache.containsKey(vboKey)) return false
        if (eboKey != null && !rc.renderResourceCache.containsKey(eboKey)) return false
        return true
    }
}
