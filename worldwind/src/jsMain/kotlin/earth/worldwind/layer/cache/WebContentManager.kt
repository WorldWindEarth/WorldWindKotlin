package earth.worldwind.layer.cache

import earth.worldwind.layer.CacheableFeatureLayer

/**
 * IndexedDB-backed counterpart to GpkgContentManager. Open once at app start; per-layer rows
 * share one IDB object store and are namespaced by `contentKey`.
 */
class WebContentManager private constructor(private val store: IdbFeatureStore) {

    /** Bind [layer] to an IndexedDB cache under [contentKey]. */
    suspend fun setupFeatureLayerCache(layer: CacheableFeatureLayer, contentKey: String) {
        layer.cacheSourceFactory = WebFeatureCacheFactory(store, contentKey)
    }

    /**
     * Register a URL prefix → cache mapping for image tiles. Pass the layer's `serviceAddress`
     * (template URL) or a static prefix that captures every tile request. Cached responses live
     * in the browser's Cache API store named [contentKey].
     */
    fun setupImageLayerCache(urlPrefix: String, contentKey: String) {
        WebTileCache.register(urlPrefix.substringBefore('{'), contentKey)
    }

    /** Same as [setupImageLayerCache] but with elevation-specific naming for symmetry. */
    fun setupElevationCoverageCache(urlPrefix: String, contentKey: String) {
        WebTileCache.register(urlPrefix.substringBefore('{'), contentKey)
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
suspend fun CacheableFeatureLayer.configureCache(contentManager: WebContentManager, contentKey: String) {
    contentManager.setupFeatureLayerCache(this, contentKey)
}
