package earth.worldwind.layer.cache

import kotlin.time.Duration

/**
 * One per-content cache policy with **two independent facets**, threaded into every [TileStore] /
 * [FeatureStore]. Defaults are unbounded — callers opt in to each axis.
 *
 * 1. **Eviction (capacity)** — [maxEntries] *deletes* data to keep the cache within bounds.
 *    Eviction sweeps on bind (a freshly-opened content is brought into bounds immediately) and
 *    via explicit `evict()`. There is no per-write trigger — high-frequency writers must call
 *    `evict()` themselves on a schedule that fits their workload.
 * 2. **Revalidation (freshness)** — [staleAfter] is the stale-while-revalidate threshold. It **never
 *    deletes**; it only decides when an already-served tile is re-checked against the network in the
 *    background (see CachedTileSource / CachedTiledFeatureSource / CachedElevationSourceFactory).
 *
 * [staleAfter] lives in the same bundle as the capacity cap because the store is the single
 * component that owns both a cache's size and its age — but note it is a *revalidation* knob, not
 * an evictor: it began as a TTL-eviction setting and was repurposed as the SWR trigger when the
 * cache moved to stale-while-revalidate, so it never deletes despite sitting beside [maxEntries].
 *
 * - [maxEntries] caps the number of cached rows / tiles. **Evicts.** This is the only capacity
 *   cap — a byte budget was removed because the byte-accounting paths needed SUM(size) scans,
 *   couldn't be kept transactional under concurrent writes, and dominated startup time when the
 *   cache was even mildly over-budget. Entry count is a close-enough proxy for raster tiles (~50
 *   KB each, fairly uniform) and 3D-Tiles content (~5–50 KB, ~10× variance). Pick a row cap that
 *   matches your target footprint at the dataset's typical row size.
 * - [staleAfter] revalidation staleness threshold, by write time. **Never evicts.** A missing
 *   freshness stamp is treated as epoch 0 (immediately stale) so the first read kicks one
 *   revalidation pass that stamps it, rather than freezing the tile as un-refreshable.
 *
 * Bulk-refresh sources (WFS, Shapefile) only meaningfully honor [staleAfter] — the snapshot is
 * atomic, so capping by entries would corrupt it. Tile-pyramid sources honor both.
 */
data class CachePolicy(
    val maxEntries: Long = Long.MAX_VALUE,
    val staleAfter: Duration = Duration.INFINITE,
) {
    val isUnbounded: Boolean get() =
        maxEntries == Long.MAX_VALUE && staleAfter == Duration.INFINITE

    /** Whether write-time freshness is tracked — i.e. [staleAfter] gates stale-while-revalidate.
     *  When false the cache never revalidates and stores skip stamping/reading the freshness time. */
    val tracksFreshness: Boolean get() = staleAfter != Duration.INFINITE

    companion object {
        val UNBOUNDED = CachePolicy()
    }
}
