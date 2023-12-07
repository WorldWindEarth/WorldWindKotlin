package earth.worldwind.globe.elevation.coverage

import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.CacheSourceFactory
import earth.worldwind.util.ContentManager

interface CacheableElevationCoverage : ElevationCoverage {
    /**
     * Tile matrix which defines elevation coverage area
     */
    val tileMatrixSet: TileMatrixSet
    /**
     * Source factory responsible for cache elevation source generation and cache data management
     */
    var cacheSourceFactory: CacheSourceFactory?
    /**
     * Unique key of this coverage in the cache
     */
    var contentKey: String?
    /**
     * Checks if cache is successfully configured
     */
    val isCacheConfigured get() = cacheSourceFactory != null
    /**
     * Allows saving new content to cache
     */
    var isCacheWritable: Boolean
        get() = cacheSourceFactory?.isWritable ?: false
        set(value) { cacheSourceFactory?.isWritable = value }
    /**
     * Configures tiled elevation coverage to retrieve a cache source only
     */
    var isCacheOnly: Boolean

    /**
     * Configures elevation coverage to use specified cache provider
     *
     * @param contentManager Cache content manager
     * @param contentKey Content key inside the specified content manager
     * @param isFloat If true, then cache will be stored in Float32 format, else Int16
     *
     * @throws IllegalArgumentException In the case of incompatible matrix set configured in cache content
     * @throws IllegalStateException In the case of cache configuration requested on a read-only content
     */
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    suspend fun configureCache(contentManager: ContentManager, contentKey: String, isFloat: Boolean = false) {
        contentManager.setupElevationCoverageCache(this, contentKey, isFloat)
    }

    /**
     * Deletes all tiles from current cache storage
     *
     * @param deleteMetadata also delete cache metadata
     * @throws IllegalStateException In case of read-only database
     */
    @Throws(IllegalStateException::class)
    suspend fun clearCache(deleteMetadata: Boolean = false) = cacheSourceFactory?.clearContent(deleteMetadata).also {
        if (deleteMetadata) disableCache()
    }

    /**
     * Removes cache provider from current tiled elevation coverage
     */
    fun disableCache() {
        cacheSourceFactory = null
    }
}