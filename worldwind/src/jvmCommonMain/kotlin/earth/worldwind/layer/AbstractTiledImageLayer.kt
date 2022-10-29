package earth.worldwind.layer

import earth.worldwind.geom.Sector
import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.ogc.gpkg.GpkgContent
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.image.ImageTile
import earth.worldwind.shape.TiledSurfaceImage
import kotlinx.coroutines.*

actual abstract class AbstractTiledImageLayer actual constructor(name: String): RenderableLayer(name) {
    protected actual open val firstLevelOffset = 0
    protected actual var tiledSurfaceImage: TiledSurfaceImage? = null
        set(value) {
            field?.let { removeRenderable(it) }
            value?.let { addRenderable(it) }
            field = value
        }
    override var isPickEnabled = false
    /**
     * Configures tiled image layer to work only with cache source
     */
    var useCacheOnly: Boolean
        get() =  tiledSurfaceImage?.useCacheOnly ?: false
        set(value) { tiledSurfaceImage?.useCacheOnly = value }

    /**
     * Removes cache provider from current tiled image layer.
     */
    fun disableCache() { tiledSurfaceImage?.cacheTileFactory = null }

    protected open fun getOrSetupTilesContent(pathName: String, tableName: String, readOnly: Boolean, isWebp: Boolean): GpkgContent {
        val tiledSurfaceImage = tiledSurfaceImage ?: error("Surface image not defined")
        val levelSet = tiledSurfaceImage.levelSet
        val geoPackage = GeoPackage(pathName, readOnly)
        return geoPackage.content.firstOrNull { it.tableName == tableName }?.also {
            // Check if current layer fits cache content
            val config = geoPackage.buildLevelSetConfig(it)
            require(config.sector == levelSet.sector) { "Invalid sector" }
            require(config.tileOrigin == levelSet.tileOrigin) { "Invalid tile origin" }
            require(config.firstLevelDelta == levelSet.firstLevelDelta) { "Invalid level delta" }
            require(config.numLevels == levelSet.numLevels) { "Invalid number of levels" }
            require(config.tileWidth == levelSet.tileWidth && config.tileHeight == levelSet.tileHeight) { "Invalid tile size" }
            require(geoPackage.getTileMatrix(tableName)?.keys?.sorted()?.get(0) == firstLevelOffset) { "Invalid level offset" }
            if (isWebp) requireNotNull(geoPackage.extensions.firstOrNull { e ->
                e.tableName == tableName && e.columnName == "tile_data" && e.extensionName == "gpkg_webp"
            }) { "WEBP extension missed" }
        } ?: geoPackage.setupTilesContent(tableName, displayName ?: tableName, levelSet, firstLevelOffset, isWebp)
    }

    protected open fun launchBulkRetrieval(
        scope: CoroutineScope, sector: Sector, resolution: Double, onProgress: ((Int, Int) -> Unit)?,
        retrieveTile: suspend (imageSource: ImageSource, cacheSource: ImageSource, options: ImageOptions?) -> Unit
    ): Job? {
        val tiledSurfaceImage = tiledSurfaceImage ?: error("Surface image not defined")
        val cacheTileFactory = tiledSurfaceImage.cacheTileFactory ?: error("Cache not configured")
        return if (sector.intersect(tiledSurfaceImage.levelSet.sector)) {
            scope.launch(Dispatchers.IO) {
                val processingList = tiledSurfaceImage.assembleTilesList(sector, resolution)
                for ((current, tile) in processingList.withIndex()) {
                    ensureActive()
                    // No image source indicates an empty level or an image missing from the tiled data store
                    val imageSource = tile.imageSource ?: continue
                    // If cache tile factory is specified, then create cache source and store it in tile
                    val cacheSource = tile.cacheSource
                        ?: (cacheTileFactory.createTile(tile.sector, tile.level, tile.row, tile.column) as ImageTile)
                            .imageSource?.also { tile.cacheSource = it } ?: continue
                    try {
                        retrieveTile(imageSource, cacheSource, tiledSurfaceImage.imageOptions)
                    } catch (ignore: Throwable) {
                        // Ignore particular tile retrieval failure
                    }
                    onProgress?.invoke(current + 1, processingList.size)
                }
            }
        } else null
    }
}