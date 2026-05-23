package earth.worldwind.layer

import earth.worldwind.util.ContentManager
import kotlin.time.Instant

/**
 * Vector-feature counterpart to [CacheableImageLayer] / [earth.worldwind.globe.elevation.coverage.CacheableElevationCoverage].
 * Exposes the cache binding hooks a [WebFeatureLayer] needs to read its features from
 * a GeoPackage and (eventually) write fetched features back.
 */
interface CacheableFeatureLayer : Layer {
    /**
     * Cache binding for this layer's features, or null if no cache has been wired up.
     * The factory exposes both the content-key/path used to address the layer in the
     * underlying [ContentManager] and the hooks the layer uses to push fetched features
     * into the cache.
     */
    var cacheSourceFactory: FeatureCacheSourceFactory?

    val isCacheConfigured get() = cacheSourceFactory != null
    val contentKey get() = cacheSourceFactory?.contentKey
    val contentPath get() = cacheSourceFactory?.contentPath
    suspend fun lastModifiedDate(): Instant? = cacheSourceFactory?.lastModifiedDate()
    suspend fun cacheContentSize(): Long = cacheSourceFactory?.contentSize() ?: 0L

    /**
     * Configure this layer to use the cache provider exposed by [contentManager], writing
     * service metadata into the cache config when [setupWebLayer] is true.
     */
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    suspend fun configureCache(
        contentManager: ContentManager, contentKey: String, setupWebLayer: Boolean = true,
    ) {
        contentManager.setupFeatureLayerCache(this, contentKey, setupWebLayer)
    }

    /** Drop all cached features, optionally also removing the cache metadata row. */
    @Throws(IllegalStateException::class)
    suspend fun clearCache(deleteMetadata: Boolean = false) {
        cacheSourceFactory?.clearContent(deleteMetadata)
        if (deleteMetadata) disableCache()
    }

    fun disableCache() {
        cacheSourceFactory = null
    }
}

/**
 * Cache backing for a [CacheableFeatureLayer] — identifies the cache content row and
 * provides hooks to inspect / replace the cached feature payload.
 */
interface FeatureCacheSourceFactory {
    val contentType: String
    val contentKey: String
    val contentPath: String
    suspend fun lastModifiedDate(): Instant?
    suspend fun contentSize(): Long
    @Throws(IllegalStateException::class)
    suspend fun clearContent(deleteMetadata: Boolean)
}
