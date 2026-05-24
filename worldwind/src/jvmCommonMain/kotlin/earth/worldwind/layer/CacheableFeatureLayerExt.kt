package earth.worldwind.layer

import earth.worldwind.layer.cache.CacheEvictionPolicy
import earth.worldwind.util.ContentManager

/** JVM/Android `configureCache`; JS has its own flavor over WebContentManager in jsMain. */
@Throws(IllegalArgumentException::class, IllegalStateException::class)
suspend fun CacheableFeatureLayer.configureCache(
    contentManager: ContentManager, contentKey: String, setupWebLayer: Boolean = true,
    evictionPolicy: CacheEvictionPolicy = CacheEvictionPolicy.UNBOUNDED,
) {
    contentManager.setupFeatureLayerCache(this, contentKey, setupWebLayer, evictionPolicy)
}
