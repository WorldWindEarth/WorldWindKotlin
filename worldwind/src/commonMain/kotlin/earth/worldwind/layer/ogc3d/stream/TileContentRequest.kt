package earth.worldwind.layer.ogc3d.stream

import earth.worldwind.layer.ogc3d.tileset.Tile3d
import kotlin.concurrent.Volatile

/**
 * One in-flight (or queued) fetch for a tile's content payload. Carries enough context for
 * the queue to prioritize, cancel, and dispatch the result back to the layer.
 *
 * The fetch is keyed by [contentUri] post-auth-rewrite, so two tiles that happen to share
 * the same content (e.g. an external tileset reused under multiple parents) hit the same
 * cache entry.
 */
class TileContentRequest internal constructor(
    val tile: Tile3d,
    val contentUri: String,
    /** Descending sort key — higher means fetch sooner. Currently the tile's screen-space error. */
    val priority: Double,
) {
    @Volatile var cancelled: Boolean = false
}
