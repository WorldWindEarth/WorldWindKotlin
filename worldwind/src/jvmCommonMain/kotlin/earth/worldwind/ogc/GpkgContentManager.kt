package earth.worldwind.ogc

import earth.worldwind.globe.elevation.coverage.CacheableElevationCoverage
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.globe.elevation.coverage.WebElevationCoverage
import earth.worldwind.layer.CacheableImageLayer
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.layer.WebImageLayer
import earth.worldwind.layer.mercator.MercatorTiledSurfaceImage
import earth.worldwind.layer.mercator.WebMercatorImageLayer
import earth.worldwind.layer.mercator.WebMercatorLayerFactory
import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.ogc.gpkg.GeoPackage.Companion.COVERAGE
import earth.worldwind.ogc.gpkg.GeoPackage.Companion.EPSG_3857
import earth.worldwind.ogc.gpkg.GeoPackage.Companion.FEATURES
import earth.worldwind.ogc.gpkg.GeoPackage.Companion.FLOAT
import earth.worldwind.ogc.gpkg.GeoPackage.Companion.INTEGER
import earth.worldwind.ogc.gpkg.GeoPackage.Companion.TILES
import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.CacheTileFactory
import earth.worldwind.util.ContentManager
import earth.worldwind.util.LevelSet
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mil.nga.geopackage.extension.WebPExtension
import mil.nga.geopackage.tiles.user.TileTable
import java.io.File
import kotlin.time.Instant

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

    override suspend fun contentSize() = withContext(Dispatchers.IO) { File(pathName).length() }

    override suspend fun lastModifiedDate() = withContext(Dispatchers.IO) {
        val file = File(pathName)
        if (file.exists()) Instant.fromEpochMilliseconds(file.lastModified()) else null
    }

    override suspend fun getImageLayersCount() = geoPackage.countContent(TILES).toInt()

    override suspend fun getImageLayers(contentKeys: List<String>?) =
        geoPackage.getContent(TILES, contentKeys).mapNotNull { content ->
            // Try to build the level set. It may fail due to unsupported projection or other requirements.
            runCatching { geoPackage.buildLevelSetConfig(content) }.onFailure {
                logMessage(WARN, "GpkgContentManager", "getImageLayers", it.message!!)
            }.getOrNull()?.let { config ->
                // Check if valid WEB service config available and try to create a Web Layer. May fail on big metadata.
                runCatching { geoPackage.getWebService(content) }.getOrNull()?.let { service ->
                    // Try to create WEB layer. May fail on invalid capabilities response or server unreachable.
                    runCatching {
                        when (service.type) {
                            WmsImageLayer.SERVICE_TYPE -> {
                                val layerNames = service.layerName?.split(",") ?: error("Layer not specified")
                                try {
                                    WmsLayerFactory.createLayer(
                                        service.address, layerNames, service.metadata, content.identifier
                                    )
                                } catch (e: Exception) {
                                    // If metadata was not null, try to request online metadata and replace layer cache
                                    if (service.metadata == null) throw e else WmsLayerFactory.createLayer(
                                        service.address, layerNames, serviceMetadata = null, content.identifier
                                    ).also {
                                        if (it is CacheableImageLayer) setupImageLayerCache(it, content.tableName)
                                    }
                                }
                            }

                            WmtsImageLayer.SERVICE_TYPE -> {
                                val layerName = service.layerName ?: error("Layer not specified")
                                try {
                                    WmtsLayerFactory.createLayer(
                                        service.address, layerName, service.metadata, content.identifier
                                    )
                                } catch (e: Exception) {
                                    // If metadata was not null, try to request online metadata and replace layer cache
                                    if (service.metadata == null) throw e else WmtsLayerFactory.createLayer(
                                        service.address, layerName, serviceMetadata = null, content.identifier
                                    ).also {
                                        if (it is CacheableImageLayer) setupImageLayerCache(it, content.tableName)
                                    }
                                }
                            }

                            WebMercatorImageLayer.SERVICE_TYPE -> WebMercatorLayerFactory.createLayer(
                                service.address, service.outputFormat, service.isTransparent,
                                content.identifier, config.numLevels, config.tileHeight, config.levelOffset
                            )

                            else -> null // It is not a known Web Layer type
                        }?.apply {
                            // Apply bounding sector from content
                            tiledSurfaceImage?.levelSet?.sector?.copy(config.sector)
                            // Configure cache for Web Layer
                            tiledSurfaceImage?.cacheTileFactory = GpkgTileFactory(geoPackage, content, service.outputFormat)
                        }
                    }.onFailure {
                        logMessage(WARN, "GpkgContentManager", "getImageLayers", it.message!!)
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
            require(content.tileMatrix?.minOf { it.zoomLevel } == 0L) { "Invalid level offset" }
            if (imageFormat.equals("image/webp", true)) requireNotNull(geoPackage.getExtension(
                tableName = contentKey, TileTable.COLUMN_TILE_DATA, WebPExtension.EXTENSION_NAME
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
                val factory = GpkgElevationSourceFactory(geoPackage, content, metadata.dataType == FLOAT)
                val service = runCatching { geoPackage.getWebService(content) }.getOrNull()
                when (service?.type) {
                    Wcs100ElevationCoverage.SERVICE_TYPE -> Wcs100ElevationCoverage(
                        service.address, service.layerName ?: error("Coverage not specified"),
                        service.outputFormat, matrixSet.sector, matrixSet.maxResolution
                    ).apply { cacheSourceFactory = factory }

                    Wcs201ElevationCoverage.SERVICE_TYPE -> {
                        val layerName = service.layerName ?: error("Coverage not specified")
                        try {
                            Wcs201ElevationCoverage.createCoverage(
                                service.address, layerName, service.outputFormat, service.metadata
                            )
                        } catch (e: Exception) {
                            // If metadata was not null, try to request online metadata and replace layer cache
                            if (service.metadata == null) throw e else Wcs201ElevationCoverage.createCoverage(
                                service.address, layerName, service.outputFormat
                            ).also { setupElevationCoverageCache(it, content.tableName) }
                        }.apply { cacheSourceFactory = factory }
                    }

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
                logMessage(WARN, "GpkgContentManager", "getElevationCoverages", it.message!!)
            }.getOrNull()
        }

    override suspend fun setupElevationCoverageCache(
        coverage: CacheableElevationCoverage, contentKey: String, setupWebCoverage: Boolean, isFloat: Boolean
    ) {
        val content = geoPackage.getContent(contentKey)?.also { content ->
            // Check if the current layer fits cache content
            val matrixSet = geoPackage.buildTileMatrixSet(content)
            require(matrixSet.sector.equals(coverage.tileMatrixSet.sector, TOLERANCE)) { "Invalid sector" }
            val dataType = if (isFloat) FLOAT else INTEGER
            requireNotNull(geoPackage.getGriddedCoverage(content)?.dataType == dataType) { "Invalid data type" }
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

    suspend fun getFeatureLayersCount() = geoPackage.countContent(FEATURES).toInt()

    suspend fun getFeatureLayers(contentKeys: List<String>? = null) = geoPackage.getContent(FEATURES, contentKeys)
        .mapNotNull { content ->
            runCatching {
                RenderableLayer(geoPackage.getRenderables(content)).apply {
                    displayName = content.identifier
                    isPickEnabled = false
                    putUserProperty(FEATURE_CONTENT_KEY, content.tableName)
                    content.lastChange?.let { putUserProperty(FEATURE_LAST_CHANGE_KEY, Instant.fromEpochMilliseconds(it.time)) }
                    geoPackage.getBoundingSector(content)?.let { putUserProperty(FEATURE_BOUNDING_SECTOR_KEY, it) }
                }
            }.onFailure {
                logMessage(WARN, "GpkgContentManager", "getFeatureLayers", it.message!!)
            }.getOrNull()
        }

    suspend fun getFeatureLayerSize(contentKey: String) = geoPackage.readFeaturesDataSize(contentKey)

    companion object {
        const val FEATURE_CONTENT_KEY = "featureContentKey"
        const val FEATURE_LAST_CHANGE_KEY = "featureLastChange"
        const val FEATURE_BOUNDING_SECTOR_KEY = "featureBoundingSector"
        private const val TOLERANCE = 1e-6
    }
}