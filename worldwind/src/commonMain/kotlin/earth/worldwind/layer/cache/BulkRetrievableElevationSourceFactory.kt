package earth.worldwind.layer.cache

/**
 * Mixin for elevation source factories that can fetch one `(z, x, y)` tile through the
 * cache-then-network pipeline with cache write-through. Implemented by every cached
 * elevation factory — `GpkgCachedElevationSourceFactory` on JVM,
 * `CachedElevationSourceFactory` on JS / iOS — so [TiledElevationCoverage.launchBulkRetrieval]
 * can drive a bulk download without knowing the concrete factory type.
 *
 * The "(layer-level network source) + CachedTileSource" wiring that the pre-Option-B
 * `TileSourceElevationSourceFactory` used is no longer in play for cache-aware elevation,
 * so the helper's dispatch is solely through this interface.
 */
interface BulkRetrievableElevationSourceFactory {
    /**
     * Fetch the tile at `(z, x, y)` through the cache-then-network pipeline, write the
     * result through to the cache on network success, and report whether the tile is now
     * in the cache.
     *
     * Returns `true` when the tile is in the cache (either already cached or fetched and
     * written); `false` on permanent miss (no such tile) or unrecoverable network
     * failure. Cancellation propagates by re-throwing the cancellation exception.
     *
     * @param overrideCache when `true`, skip the cache lookup and force a fresh network
     *   fetch + write-through for every tile. Mirrors pre-refactor's `makeLocal`
     *   `overrideCache` for the image-tile case.
     */
    suspend fun fetchAndCacheTile(z: Int, x: Int, y: Int, overrideCache: Boolean = false): Boolean
}
