package earth.worldwind.layer

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.ogc.gpkg.GpkgContent
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.image.ImageTile
import earth.worldwind.shape.TiledSurfaceImage
import kotlinx.coroutines.*
import java.io.FileNotFoundException
import kotlin.time.Duration.Companion.seconds

actual abstract class AbstractTiledImageLayer actual constructor(name: String): RenderableLayer(name) {
    actual var tiledSurfaceImage: TiledSurfaceImage? = null
        protected set(value) {
            field?.let { removeRenderable(it) }
            value?.let { addRenderable(it) }
            field = value
        }
    override var isPickEnabled = false
    /**
     * Determines how many levels to skip from retrieving texture during tile pyramid subdivision.
     */
    var levelOffset: Int
        get() = tiledSurfaceImage?.levelOffset ?: 0
        set(value) { tiledSurfaceImage?.levelOffset = value }
    /**
     * Checks if cache is successfully configured
     */
    val isCacheConfigured get() = tiledSurfaceImage?.cacheTileFactory != null
    /**
     * Configures tiled image layer to work with cache source only
     */
    var useCacheOnly: Boolean
        get() = tiledSurfaceImage?.useCacheOnly ?: false
        set(value) { tiledSurfaceImage?.useCacheOnly = value }
    /**
     * Number of reties of bulk tile retrieval before long timeout
     */
    var makeLocalRetries = 3
    /**
     * Short timeout on bulk tile retrieval failed
     */
    var makeLocalTimeoutShort = 5.seconds
    /**
     * Long timeout on bulk tile retrieval failed
     */
    var makeLocalTimeoutLong = 15.seconds

    protected var cacheContent: GpkgContent? = null

    /**
     * Removes cache provider from current tiled image layer.
     */
    fun disableCache() {
        cacheContent = null
        tiledSurfaceImage?.cacheTileFactory = null
    }

    /**
     * Delete all tiles from current cache storage
     *
     * @throws IllegalStateException In case of read-only database.
     */
    @Throws(IllegalStateException::class)
    suspend fun clearCache() = cacheContent?.run { container.deleteContent(tableName) }.also { disableCache() }

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    protected open suspend fun getOrSetupTilesContent(pathName: String, tableName: String, readOnly: Boolean, isWebp: Boolean): GpkgContent {
        val tiledSurfaceImage = tiledSurfaceImage ?: error("Surface image not defined")
        val levelSet = tiledSurfaceImage.levelSet
        val geoPackage = GeoPackage(pathName, readOnly)
        return geoPackage.content.firstOrNull { it.tableName == tableName }?.also {
            // Check if current layer fits cache content
            val config = geoPackage.buildLevelSetConfig(it)
            require(config.sector == levelSet.sector) { "Invalid sector" }
            require(config.tileOrigin == levelSet.tileOrigin) { "Invalid tile origin" }
            require(config.firstLevelDelta == levelSet.firstLevelDelta) { "Invalid first level delta" }
            require(config.tileWidth == levelSet.tileWidth && config.tileHeight == levelSet.tileHeight) { "Invalid tile size" }
            require(geoPackage.getTileMatrix(tableName)?.keys?.sorted()?.get(0) == 0) { "Invalid level offset" }
            if (isWebp) requireNotNull(geoPackage.extensions.firstOrNull { e ->
                e.tableName == tableName && e.columnName == "tile_data" && e.extensionName == "gpkg_webp"
            }) { "WEBP extension missed" }
            // Verify if all required tile matrices created
            if (config.numLevels < levelSet.numLevels) geoPackage.setupTileMatrices(tableName, levelSet)
        } ?: geoPackage.setupTilesContent(tableName, displayName ?: tableName, levelSet, isWebp)
    }

    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    protected open fun launchBulkRetrieval(
        scope: CoroutineScope, sector: Sector, resolution: Angle, onProgress: ((Int, Int, Int) -> Unit)?,
        retrieveTile: suspend (imageSource: ImageSource, cacheSource: ImageSource, options: ImageOptions?) -> Boolean
    ): Job {
        val tiledSurfaceImage = tiledSurfaceImage ?: error("Surface image not defined")
        val cacheTileFactory = tiledSurfaceImage.cacheTileFactory ?: error("Cache not configured")
        require(sector.intersect(tiledSurfaceImage.levelSet.sector)) { "Sector does not intersect tiled surface image sector" }
        return scope.launch(Dispatchers.IO) {
            // Prepare tile list for download, based on specified sector and resolution
            val processingList = tiledSurfaceImage.assembleTilesList(sector, resolution)
            var downloaded = 0
            var skipped = 0
            // Try to download each tile in a list
            for (tile in processingList) {
                var attempt = 0
                // Retry download attempts till success or 404 not fond or job cancelled
                while(true) {
                    // Check if job cancelled
                    ensureActive()
                    // No image source indicates an empty level or an image missing from the tiled data store
                    val imageSource = tile.imageSource ?: break
                    // If cache tile factory is specified, then create cache source and store it in tile
                    val cacheSource = tile.cacheSource
                        ?: (cacheTileFactory.createTile(tile.sector, tile.level, tile.row, tile.column) as ImageTile)
                            .imageSource?.also { tile.cacheSource = it } ?: break
                    // Attempt to download tile
                    try {
                        ++attempt
                        if (retrieveTile(imageSource, cacheSource, tiledSurfaceImage.imageOptions)) {
                            // Tile successfully downloaded
                            onProgress?.invoke(++downloaded, skipped, processingList.size)
                        } else {
                            // Received data can not be decoded as image
                            onProgress?.invoke(downloaded, ++skipped, processingList.size)
                        }
                        break // Continue downloading next tile
                    } catch (throwable: Throwable) {
                        when (throwable) {
                            is FileNotFoundException -> {
                                onProgress?.invoke(downloaded, ++skipped, processingList.size)
                                break // Skip missed tile and continue download next one
                            }
                            else -> delay(if(attempt % makeLocalRetries == 0) makeLocalTimeoutLong else makeLocalTimeoutShort)
                        }
                    }
                }
            }
        }
    }
}