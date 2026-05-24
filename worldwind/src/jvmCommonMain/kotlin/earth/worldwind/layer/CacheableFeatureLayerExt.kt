package earth.worldwind.layer

import earth.worldwind.util.ContentManager

/** JVM/Android `configureCache`; JS has its own flavor over WebContentManager in jsMain. */
@Throws(IllegalArgumentException::class, IllegalStateException::class)
suspend fun CacheableFeatureLayer.configureCache(
    contentManager: ContentManager, contentKey: String, setupWebLayer: Boolean = true,
) {
    contentManager.setupFeatureLayerCache(this, contentKey, setupWebLayer)
}
