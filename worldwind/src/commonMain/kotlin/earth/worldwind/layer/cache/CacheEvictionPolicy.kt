package earth.worldwind.layer.cache

import kotlin.time.Duration

/**
 * LRU/TTL/size caps applied by [earth.worldwind.layer.FeatureCacheSourceFactory] and the
 * image/elevation tile factories. Defaults are unbounded — callers opt in to each axis.
 *
 * Caches sweep on bind (so a freshly-opened content is brought into bounds immediately) and
 * via explicit `evict()` calls. There is no per-write trigger — high-frequency writers must
 * call `evict()` themselves on a schedule that fits their workload.
 *
 * - [maxEntries] caps the number of cached rows / tiles.
 * - [maxAge] expires entries by write time. NULL `last_modified` columns are treated as
 *   epoch 0 (always expired) — that's the right behavior for rows imported from external
 *   tools that don't populate the column.
 * - [maxBytes] is a best-effort byte budget. Cache API can't always report response sizes
 *   (servers may omit `Content-Length`), so this is an over-estimate at worst.
 *
 * Bulk-refresh sources (WFS, Shapefile) only meaningfully honor [maxAge] — the snapshot is
 * atomic, so capping by entries or bytes would corrupt it. Tile-pyramid sources honor all three.
 */
data class CacheEvictionPolicy(
    val maxEntries: Long = Long.MAX_VALUE,
    val maxAge: Duration = Duration.INFINITE,
    val maxBytes: Long = Long.MAX_VALUE,
) {
    val isUnbounded: Boolean get() =
        maxEntries == Long.MAX_VALUE && maxAge == Duration.INFINITE && maxBytes == Long.MAX_VALUE

    companion object {
        val UNBOUNDED = CacheEvictionPolicy()
    }
}
