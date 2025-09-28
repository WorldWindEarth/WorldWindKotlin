package earth.worldwind.util

import earth.worldwind.globe.elevation.coverage.CacheableElevationCoverage
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.layer.CacheableImageLayer
import earth.worldwind.layer.TiledImageLayer
import kotlin.time.Instant

interface ContentManager {
    /**
     * Get the size of all content manager content
     */
    suspend fun contentSize(): Long

    /**
     * Last modification date of any content in this manager
     */
    suspend fun lastModifiedDate(): Instant?

    /**
     * Number of image layers in content manager
     */
    suspend fun getImageLayersCount(): Int

    /**
     * Get image layers available in this content manager
     *
     * @param contentKeys Optional list of layer cache content keys, if empty, than all layers will be returned
     * @return List of tiled image layers
     */
    suspend fun getImageLayers(contentKeys: List<String>? = null): List<TiledImageLayer>

    /**
     * Setup image layer to store cache in this content manager
     *
     * @param layer Image layer to set up cache
     * @param contentKey Unique key of this layer in cache content
     * @param setupWebLayer Add online source metadata into the cache config to be able to download additional tiles
     */
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    suspend fun setupImageLayerCache(layer: CacheableImageLayer, contentKey: String, setupWebLayer: Boolean = true)

    /**
     * Number of elevation coverages in content manager
     */
    suspend fun getElevationCoveragesCount(): Int

    /**
     * Get elevation coverages available in this content manager
     *
     * @param contentKeys Optional list of coverage cache content keys, if empty, than all coverages will be returned
     * @return List of tiled elevation coverages
     */
    suspend fun getElevationCoverages(contentKeys: List<String>? = null): List<TiledElevationCoverage>

    /**
     * Setup elevation coverage to store cache in this content manager
     *
     * @param coverage Elevation coverage to set up cache
     * @param contentKey Unique key of this coverage in cache content
     * @param setupWebCoverage Add online source metadata into the cache config to be able to download additional tiles
     * @param isFloat If true, then elevation data would be stored in Float32 format
     */
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    suspend fun setupElevationCoverageCache(
        coverage: CacheableElevationCoverage, contentKey: String, setupWebCoverage: Boolean = true, isFloat: Boolean = false
    )

    /**
     * Delete content from cache
     *
     * @param contentKey Unique key of cache content
     */
    suspend fun deleteContent(contentKey: String)
}