package earth.worldwind.ogc

import earth.worldwind.globe.elevation.coverage.CacheableElevationCoverage
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.globe.elevation.coverage.WebElevationCoverage
import earth.worldwind.layer.CacheableImageLayer
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.layer.WebImageLayer
import earth.worldwind.layer.mercator.MercatorLayerFactory
import earth.worldwind.layer.mercator.MercatorTiledSurfaceImage
import earth.worldwind.ogc.gpkg.AbstractGeoPackage
import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.ContentManager
import earth.worldwind.util.LevelSet
import earth.worldwind.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GpkgContentManager(pathName: String, readOnly: Boolean = false): ContentManager {
    private val geoPackage by lazy { GeoPackage(pathName, readOnly) }

    override suspend fun getImageLayers(contentKeys: List<String>?) = withContext(Dispatchers.IO) {
        geoPackage.content.values
            .filter { it.dataType.equals("tiles", true) && contentKeys?.contains(it.tableName) != false }
            .mapNotNull { content ->
                runCatching {
                    val service = geoPackage.webServices[content.tableName]
                    val config = geoPackage.buildLevelSetConfig(content)
                    when (service?.type) {
                        WmsLayerFactory.SERVICE_TYPE -> WmsLayerFactory.createLayer(
                            service.address, service.layerName?.split(",") ?: error("Layer name is absent")
                        ).apply {
                            tiledSurfaceImage?.cacheTileFactory = GpkgTileFactory(content, service.outputFormat)
                        }

                        WmtsLayerFactory.SERVICE_TYPE -> WmtsLayerFactory.createLayer(
                            service.address, service.layerName ?: error("Layer name is absent")
                        ).apply {
                            tiledSurfaceImage?.cacheTileFactory = GpkgTileFactory(content, service.outputFormat)
                        }

                        MercatorLayerFactory.SERVICE_TYPE -> MercatorLayerFactory.createLayer(
                            content.identifier, service.address, service.outputFormat, service.isTransparent,
                            config.numLevels, config.tileHeight
                        ).apply {
                            tiledSurfaceImage?.cacheTileFactory = GpkgTileFactory(content, service.outputFormat)
                        }

                        else -> TiledImageLayer(content.identifier, if (content.srsId == AbstractGeoPackage.EPSG_3857) {
                            MercatorTiledSurfaceImage(GpkgTileFactory(content), LevelSet(config))
                        } else {
                            TiledSurfaceImage(GpkgTileFactory(content), LevelSet(config))
                        })
                    }.also { if (it is CacheableImageLayer) it.contentKey = content.tableName }
                }.onFailure {
                    Logger.logMessage(Logger.WARN, "GpkgContentManager", "getTiledImageLayers", it.message!!)
                }.getOrNull()
            }
    }

    override suspend fun setupImageLayerCache(layer: CacheableImageLayer, contentKey: String) = withContext(Dispatchers.IO) {
        val tiledSurfaceImage = layer.tiledSurfaceImage ?: error("Surface image not defined")
        val levelSet = tiledSurfaceImage.levelSet
        val imageFormat = (layer as? WebImageLayer)?.imageFormat ?: "image/png"
        val content = geoPackage.content[contentKey]?.also {
            // Check if the current layer fits cache content
            val config = geoPackage.buildLevelSetConfig(it)
            require(config.sector.equals(levelSet.sector, TOLERANCE)) { "Invalid sector" }
            require(config.tileOrigin.equals(levelSet.tileOrigin, TOLERANCE)) { "Invalid tile origin" }
            require(config.firstLevelDelta.equals(levelSet.firstLevelDelta, TOLERANCE)) { "Invalid first level delta" }
            require(config.tileWidth == levelSet.tileWidth && config.tileHeight == levelSet.tileHeight) { "Invalid tile size" }
            require(geoPackage.tileMatrix[contentKey]?.keys?.sorted()?.get(0) == 0) { "Invalid level offset" }
            if (imageFormat.equals("image/webp", true)) requireNotNull(geoPackage.extensions.firstOrNull { e ->
                e.tableName == contentKey && e.columnName == "tile_data" && e.extensionName == "gpkg_webp"
            }) { "WEBP extension missed" }
            // Check and update web service config
            if (layer is WebImageLayer) {
                val serviceType = geoPackage.webServices[contentKey]?.type
                require(serviceType == null || serviceType == layer.serviceType) { "Invalid service type" }
                val outputFormat = geoPackage.webServices[contentKey]?.outputFormat
                require(outputFormat == null || outputFormat == layer.imageFormat) { "Invalid image format" }
                if (!geoPackage.isReadOnly) geoPackage.setupWebLayer(layer, contentKey)
            }
            // Verify if all required tile matrices created
            if (!geoPackage.isReadOnly && config.numLevels < levelSet.numLevels) geoPackage.setupTileMatrices(contentKey, levelSet)
        } ?: geoPackage.setupTilesContent(layer, contentKey, levelSet)

        layer.contentKey = contentKey
        layer.tiledSurfaceImage?.cacheTileFactory = GpkgTileFactory(content, imageFormat)
    }

    override suspend fun getElevationCoverages(contentKeys: List<String>?) = withContext(Dispatchers.IO) {
        geoPackage.content.values
            .filter { it.dataType.equals("2d-gridded-coverage", true) && contentKeys?.contains(it.tableName) != false }
            .mapNotNull { content ->
                runCatching {
                    val metadata = geoPackage.griddedCoverages[content.tableName]
                    requireNotNull(metadata) { "Missing gridded coverage metadata for '${content.tableName}'" }
                    val matrixSet = geoPackage.buildTileMatrixSet(content)
                    val factory = GpkgElevationSourceFactory(content, metadata.datatype == "float")
                    val service = geoPackage.webServices[content.tableName]
                    when (service?.type) {
                        Wcs100ElevationCoverage.SERVICE_TYPE -> Wcs100ElevationCoverage(
                            service.address,
                            service.layerName ?: error("Coverage not specified"),
                            service.outputFormat,
                            matrixSet.sector, matrixSet.maxResolution
                        ).apply { cacheSourceFactory = factory }

                        Wcs201ElevationCoverage.SERVICE_TYPE -> Wcs201ElevationCoverage(
                            service.address,
                            service.layerName ?: error("Coverage not specified"),
                            service.outputFormat
                        ).apply { cacheSourceFactory = factory }

                        WmsElevationCoverage.SERVICE_TYPE -> WmsElevationCoverage(
                            service.address,
                            service.layerName ?: error("Coverage not specified"),
                            service.outputFormat,
                            matrixSet.sector, matrixSet.maxResolution
                        ).apply { cacheSourceFactory = factory }

                        else -> TiledElevationCoverage(matrixSet, factory)
                    }.apply { contentKey = content.tableName }
                }.onFailure {
                    Logger.logMessage(Logger.WARN, "GpkgContentManager", "getTiledElevationCoverages", it.message!!)
                }.getOrNull()
            }
    }

    override suspend fun setupElevationCoverageCache(
        coverage: CacheableElevationCoverage, contentKey: String, isFloat: Boolean
    ) = withContext(Dispatchers.IO) {
        val content = geoPackage.content[contentKey]?.also {
            // Check if the current layer fits cache content
            val matrixSet = geoPackage.buildTileMatrixSet(it)
            require(matrixSet.sector.equals(coverage.tileMatrixSet.sector, TOLERANCE)) { "Invalid sector" }
            requireNotNull(geoPackage.griddedCoverages[contentKey]?.datatype == if (isFloat) "float" else "integer") { "Invalid data type" }
            // Check and update web service config
            if (coverage is WebElevationCoverage) {
                val serviceType = geoPackage.webServices[contentKey]?.type
                require(serviceType == null || serviceType == coverage.serviceType) { "Invalid service type" }
                if (!geoPackage.isReadOnly) geoPackage.setupWebElevationCoverage(coverage, contentKey)
            }
            // Verify if all required tile matrices created
            if (!geoPackage.isReadOnly && matrixSet.entries.size < coverage.tileMatrixSet.entries.size) {
                geoPackage.setupTileMatrices(contentKey, coverage.tileMatrixSet)
            }
        } ?: geoPackage.setupGriddedCoverageContent(coverage, contentKey, isFloat)

        coverage.contentKey = contentKey
        coverage.cacheSourceFactory = GpkgElevationSourceFactory(content, isFloat)
    }

    companion object {
        private const val TOLERANCE = 1e-6
    }
}