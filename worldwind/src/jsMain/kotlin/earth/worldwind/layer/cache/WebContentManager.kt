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

    /** Drop every cached row for [contentKey]. */
    suspend fun deleteContent(contentKey: String) {
        store.deleteByContent(contentKey)
    }

    companion object {
        suspend fun open(): WebContentManager = WebContentManager(IdbFeatureStore.open())
    }
}

/** JS counterpart to the JVM `configureCache(ContentManager, ...)` extension. */
suspend fun CacheableFeatureLayer.configureCache(contentManager: WebContentManager, contentKey: String) {
    contentManager.setupFeatureLayerCache(this, contentKey)
}
