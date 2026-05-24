package earth.worldwind.layer.cache

import earth.worldwind.layer.CacheableFeatureLayer

/**
 * IndexedDB-backed counterpart to GpkgContentManager. Open once at app start; per-layer rows
 * share one IDB object store and are namespaced by `contentKey`.
 */
class WebContentManager private constructor(private val store: IdbFeatureStore) {

    private val tilePolicies = mutableMapOf<String, CacheEvictionPolicy>()

    /** Bind [layer] to an IndexedDB cache under [contentKey], optionally with an eviction cap. */
    suspend fun setupFeatureLayerCache(
        layer: CacheableFeatureLayer, contentKey: String,
        evictionPolicy: CacheEvictionPolicy = CacheEvictionPolicy.UNBOUNDED,
    ) {
        val factory = WebFeatureCacheFactory(store, contentKey)
        factory.evictionPolicy = evictionPolicy
        if (!evictionPolicy.isUnbounded) runCatching { factory.evict() }
        layer.cacheSourceFactory = factory
    }

    /**
     * Register a URL prefix → cache mapping for image tiles. Pass the layer's `serviceAddress`
     * (template URL) or a static prefix that captures every tile request. Cached responses live
     * in the browser's Cache API store named [contentKey]. [evictionPolicy] is sweep-on-bind +
     * applied whenever [evictTiles] is called.
     */
    suspend fun setupImageLayerCache(
        urlPrefix: String, contentKey: String,
        evictionPolicy: CacheEvictionPolicy = CacheEvictionPolicy.UNBOUNDED,
    ) {
        WebTileCache.register(urlPrefix.substringBefore('{'), contentKey)
        tilePolicies[contentKey] = evictionPolicy
        if (!evictionPolicy.isUnbounded) runCatching { WebTileCache.evictByPolicy(contentKey, evictionPolicy) }
    }

    /** Same as [setupImageLayerCache] but with elevation-specific naming for symmetry. */
    suspend fun setupElevationCoverageCache(
        urlPrefix: String, contentKey: String,
        evictionPolicy: CacheEvictionPolicy = CacheEvictionPolicy.UNBOUNDED,
    ) = setupImageLayerCache(urlPrefix, contentKey, evictionPolicy)

    /**
     * Apply the registered eviction policy to every tile-cache store. Call periodically
     * (e.g. on visibility-change) for sessions that load many tiles.
     */
    suspend fun evictTiles() {
        for ((contentKey, policy) in tilePolicies) {
            if (policy.isUnbounded) continue
            runCatching { WebTileCache.evictByPolicy(contentKey, policy) }
        }
    }

    /** Drop the named cache (works for features stored in IDB and tiles stored via Cache API). */
    suspend fun deleteContent(contentKey: String) {
        store.deleteByContent(contentKey)
        WebTileCache.deleteStore(contentKey)
        WebTileCache.unregister(contentKey)
    }

    companion object {
        suspend fun open(): WebContentManager = WebContentManager(IdbFeatureStore.open())
    }
}

/** JS counterpart to the JVM `configureCache(ContentManager, ...)` extension. */
suspend fun CacheableFeatureLayer.configureCache(
    contentManager: WebContentManager, contentKey: String,
    evictionPolicy: CacheEvictionPolicy = CacheEvictionPolicy.UNBOUNDED,
) {
    contentManager.setupFeatureLayerCache(this, contentKey, evictionPolicy)
}
