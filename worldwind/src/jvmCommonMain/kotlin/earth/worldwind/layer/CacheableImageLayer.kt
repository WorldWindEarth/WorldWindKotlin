package earth.worldwind.layer

import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.ContentManager

interface CacheableImageLayer : Layer {
    /**
     * Main tiled surface image used to represent this layer
     */
    val tiledSurfaceImage: TiledSurfaceImage?
    /**
     * Configures tiled image layer to retrieve a cache source only
     */
    var isCacheOnly: Boolean
        get() = tiledSurfaceImage?.isCacheOnly ?: false
        set(value) { tiledSurfaceImage?.isCacheOnly = value }
    /**
     * Checks if cache is successfully configured
     */
    val isCacheConfigured get() = tiledSurfaceImage?.cacheTileFactory != null
    /**
     * Unique key of this layer in the cache or null, if cache is not configured
     */
    val contentKey get() = tiledSurfaceImage?.cacheTileFactory?.contentKey
    /**
     * Path to cache content storage root
     */
    val contentPath get() = tiledSurfaceImage?.cacheTileFactory?.contentPath
    /**
     * Bounding sector of cache content or null, if cache is not configured or bounding measures are not specified
     */
    val boundingSector get() = tiledSurfaceImage?.levelSet?.sector

    /**
     * Last modified date of cache content or null, if cache is not configured
     */
    suspend fun lastModifiedDate() = tiledSurfaceImage?.cacheTileFactory?.lastModifiedDate()

    /**
     * Configures image layer to use specified cache provider
     *
     * @param contentManager Cache content manager
     * @param contentKey Content key inside the specified content manager
     * @param setupWebLayer Add online source metadata into the cache config to be able to download additional tiles
     *
     * @throws IllegalArgumentException In case of incompatible level set configured in cache content
     * @throws IllegalStateException In the case of cache configuration requested on a read-only content
     */
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    suspend fun configureCache(
        contentManager: ContentManager, contentKey: String, setupWebLayer: Boolean = true
    ) {
        contentManager.setupImageLayerCache(this, contentKey, setupWebLayer)
    }

    /**
     * Estimated cache content size in bytes
     */
    suspend fun cacheContentSize() = tiledSurfaceImage?.cacheTileFactory?.contentSize() ?: 0L

    /**
     * Deletes all tiles from current cache storage
     *
     * @param deleteMetadata also delete cache metadata
     * @throws IllegalStateException In case of read-only database
     */
    @Throws(IllegalStateException::class)
    suspend fun clearCache(deleteMetadata: Boolean = false) = tiledSurfaceImage?.cacheTileFactory?.clearContent(deleteMetadata).also {
        if (deleteMetadata) disableCache()
    }

    /**
     * Removes cache provider from the current tiled image layer
     */
    fun disableCache() {
        tiledSurfaceImage?.cacheTileFactory = null
    }
}