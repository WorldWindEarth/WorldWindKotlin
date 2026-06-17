package earth.worldwind.layer.ogc3d.content

import earth.worldwind.draw.DrawContext

/**
 * GPU-resident content for a single 3D Tiles tile. Sealed so the dispatcher + traverser
 * can switch on payload type without an open-class hierarchy. Concrete subtypes are
 * filled in by later phases:
 *
 *  - Phase 2: [MeshContent] for b3dm / i3dm / cmpt (glTF wrapped)
 *  - Phase 3: [PointCloudContent] for pnts
 *  - Phase 4: [GaussianContent] skeleton for future Gaussian-splat codecs
 *
 * Implementations are responsible for tracking their own GPU footprint (so the
 * `earth.worldwind.layer.ogc3d.content.TileContentCache` LRU can charge them) and for
 * releasing their GPU resources when evicted.
 */
sealed interface TileContent {
    /** Approximate GPU footprint of this content in bytes, page-aligned by the cache. */
    val gpuByteCount: Int

    /** Drops GPU resources. Idempotent. */
    fun release(dc: DrawContext)
}
