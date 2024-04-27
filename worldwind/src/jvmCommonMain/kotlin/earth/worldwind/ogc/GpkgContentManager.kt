package earth.worldwind.ogc

import earth.worldwind.globe.elevation.coverage.CacheableElevationCoverage
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.globe.elevation.coverage.WebElevationCoverage
import earth.worldwind.layer.CacheableImageLayer
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.layer.WebImageLayer
import earth.worldwind.layer.mercator.MercatorTiledSurfaceImage
import earth.worldwind.layer.mercator.WebMercatorLayerFactory
import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.ogc.gpkg.GeoPackage.Companion.COVERAGE
import earth.worldwind.ogc.gpkg.GeoPackage.Companion.EPSG_3857
import earth.worldwind.ogc.gpkg.GeoPackage.Companion.TILES
import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.CacheTileFactory
import earth.worldwind.util.ContentManager
import earth.worldwind.util.LevelSet
import earth.worldwind.util.Logger
import kotlinx.datetime.Instant
import java.io.File

class GpkgContentManager(val pathName: String, val isReadOnly: Boolean = false): ContentManager {
    private val geoPackage by lazy { GeoPackage(pathName, isReadOnly) }

    /**
     * Returns database connection state. If true, then the Content Manager cannot be used anymore.
     */
    val isShutdown get() = geoPackage.isShutdown

    /**
     * Shutdown GPKG database connection forever for this Content Manager instance.
     */
    fun shutdown() = geoPackage.shutdown()

    override suspend fun contentSize() = File(pathName).length()

    override suspend fun lastModifiedDate() = Instant.fromEpochMilliseconds(File(pathName).lastModified())

    override suspend fun getImageLayersCount() = geoPackage.countContent(TILES).toInt()

    override suspend fun getImageLayers(contentKeys: List<String>?) =
        geoPackage.getContent(TILES, contentKeys).mapNotNull { content ->
            // Try to build the level set. It may fail due to unsupported projection or other requirements.
            runCatching { geoPackage.buildLevelSetConfig(content) }.onFailure {
                Logger.logMessage(Logger.WARN, "GpkgContentManager", "getImageLayers", it.message!!)
            }.getOrNull()?.let { config ->
                // Check if WEB service config available and try to create a Web Layer
                geoPackage.getWebService(content)?.let { service ->
                    runCatching {
                        when (service.type) {
                            WmsLayerFactory.SERVICE_TYPE -> WmsLayerFactory.createLayer(
                                service.address, service.layerName?.split(",") ?: error("Layer not specified"),
                                service.metadata, content.identifier
                            )

                            WmtsLayerFactory.SERVICE_TYPE -> WmtsLayerFactory.createLayer(
                                service.address, service.layerName ?: error("Layer not specified"),
                                service.metadata, content.identifier
                            )

                            WebMercatorLayerFactory.SERVICE_TYPE -> WebMercatorLayerFactory.createLayer(
                                service.address, service.outputFormat, service.isTransparent,
                                content.identifier, config.numLevels, config.tileHeight, config.levelOffset
                            )

                            else -> null // It is not a known Web Layer type
                        }?.apply {
                            // Configure cache for Web Layer
                            tiledSurfaceImage?.cacheTileFactory = GpkgTileFactory(geoPackage, content, service.outputFormat)
                        }
                    }.onFailure {
                        Logger.logMessage(Logger.WARN, "GpkgContentManager", "getImageLayers", it.message!!)
                    }.getOrNull()
                } ?: TiledImageLayer(
                    content.identifier, if (content.srs?.id == EPSG_3857) {
                        MercatorTiledSurfaceImage(GpkgTileFactory(geoPackage, content), LevelSet(config))
                    } else {
                        TiledSurfaceImage(GpkgTileFactory(geoPackage, content), LevelSet(config))
                    }
                ).apply {
                    // Set cache factory to be able to use cacheale layer interface
                    tiledSurfaceImage?.cacheTileFactory = tiledSurfaceImage?.tileFactory as? CacheTileFactory
                }
            }
        }

    override suspend fun setupImageLayerCache(
        layer: CacheableImageLayer, contentKey: String, setupWebLayer: Boolean
    ) {
        val tiledSurfaceImage = layer.tiledSurfaceImage ?: error("Surface image not defined")
        val levelSet = tiledSurfaceImage.levelSet
        val imageFormat = (layer as? WebImageLayer)?.imageFormat ?: "image/png"
        val content = geoPackage.getContent(contentKey)?.also { content ->
            // Check if the current layer fits cache content
            val config = geoPackage.buildLevelSetConfig(content)
            require(config.tileOrigin.equals(levelSet.tileOrigin, TOLERANCE)) { "Invalid tile origin" }
            require(config.firstLevelDelta.equals(levelSet.firstLevelDelta, TOLERANCE)) { "Invalid first level delta" }
            require(config.tileWidth == levelSet.tileWidth && config.tileHeight == levelSet.tileHeight) { "Invalid tile size" }
            require(content.tileMatrices?.map { it.zoomLevel }?.sorted()?.get(0) == 0) { "Invalid level offset" }
            if (imageFormat.equals("image/webp", true)) requireNotNull(geoPackage.getExtension(
                tableName = contentKey, columnName = "tile_data", extensionName = "gpkg_webp"
            )) { "WEBP extension missed" }
            // Check and update web service config
            if (layer is WebImageLayer) {
                val webService = geoPackage.getWebService(content)
                val serviceType = webService?.type
                require(serviceType == null || serviceType == layer.serviceType) { "Invalid service type" }
                val outputFormat = webService?.outputFormat
                require(outputFormat == null || outputFormat == layer.imageFormat) { "Invalid image format" }
                if (setupWebLayer && !geoPackage.isReadOnly) geoPackage.setupWebLayer(layer, content)
            }
            // Verify if all required tile matrices created
            if (!geoPackage.isReadOnly && config.numLevels < levelSet.numLevels) {
                geoPackage.setupTileMatrices(content, levelSet)
            }
            // Update content metadata
            geoPackage.updateTilesContent(layer, contentKey, levelSet, content)
        } ?: geoPackage.setupTilesContent(layer, contentKey, levelSet, setupWebLayer)

        layer.tiledSurfaceImage?.cacheTileFactory = GpkgTileFactory(geoPackage, content, imageFormat)
    }

    override suspend fun getElevationCoveragesCount() = geoPackage.countContent(COVERAGE).toInt()

    override suspend fun getElevationCoverages(contentKeys: List<String>?) =
        geoPackage.getContent(COVERAGE, contentKeys).mapNotNull { content ->
            runCatching {
                val metadata = geoPackage.getGriddedCoverage(content)
                requireNotNull(metadata) { "Missing gridded coverage metadata for '${content.tableName}'" }
                val matrixSet = geoPackage.buildTileMatrixSet(content)
                val factory = GpkgElevationSourceFactory(geoPackage, content, metadata.datatype == "float")
                val service = geoPackage.getWebService(content)
                when (service?.type) {
                    Wcs100ElevationCoverage.SERVICE_TYPE -> Wcs100ElevationCoverage(
                        service.address, service.layerName ?: error("Coverage not specified"),
                        service.outputFormat, matrixSet.sector, matrixSet.maxResolution
                    ).apply { cacheSourceFactory = factory }

                    Wcs201ElevationCoverage.SERVICE_TYPE -> Wcs201ElevationCoverage(
                        service.address, service.layerName ?: error("Coverage not specified"),
                        service.outputFormat, service.metadata
                    ).apply { cacheSourceFactory = factory }

                    WmsElevationCoverage.SERVICE_TYPE -> WmsElevationCoverage(
                        service.address, service.layerName ?: error("Coverage not specified"),
                        service.outputFormat, matrixSet.sector, matrixSet.maxResolution
                    ).apply { cacheSourceFactory = factory }

                    else -> TiledElevationCoverage(matrixSet, factory).apply {
                        // Configure cache to be able to use cacheable coverage interface
                        cacheSourceFactory = factory
                    }
                }.apply {
                    displayName = content.identifier
                    // Apply bounding sector from content
                    geoPackage.getBoundingSector(content)?.let { sector.copy(it) }
                }
            }.onFailure {
                Logger.logMessage(Logger.WARN, "GpkgContentManager", "getElevationCoverages", it.message!!)
            }.getOrNull()
        }

    override suspend fun setupElevationCoverageCache(
        coverage: CacheableElevationCoverage, contentKey: String, setupWebCoverage: Boolean, isFloat: Boolean
    ) {
        val content = geoPackage.getContent(contentKey)?.also { content ->
            // Check if the current layer fits cache content
            val matrixSet = geoPackage.buildTileMatrixSet(content)
            require(matrixSet.sector.equals(coverage.tileMatrixSet.sector, TOLERANCE)) { "Invalid sector" }
            requireNotNull(geoPackage.getGriddedCoverage(content)?.datatype == if (isFloat) "float" else "integer") { "Invalid data type" }
            // Check and update web service config
            if (coverage is WebElevationCoverage) {
                val serviceType = geoPackage.getWebService(content)?.type
                require(serviceType == null || serviceType == coverage.serviceType) { "Invalid service type" }
                if (setupWebCoverage && !geoPackage.isReadOnly) geoPackage.setupWebCoverage(coverage, content)
            }
            // Verify if all required tile matrices created
            if (!geoPackage.isReadOnly && matrixSet.entries.size < coverage.tileMatrixSet.entries.size) {
                geoPackage.setupTileMatrices(content, coverage.tileMatrixSet)
            }
            // Update content metadata
            geoPackage.updateGriddedCoverageContent(coverage, contentKey, content)
        } ?: geoPackage.setupGriddedCoverageContent(coverage, contentKey, setupWebCoverage, isFloat)

        coverage.cacheSourceFactory = GpkgElevationSourceFactory(geoPackage, content, isFloat)
    }

    override suspend fun deleteContent(contentKey: String) = geoPackage.deleteContent(contentKey)

    companion object {
        private const val TOLERANCE = 1e-6
    }
}