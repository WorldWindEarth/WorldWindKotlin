package earth.worldwind.formats.gpkg

import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.layer.cache.CachePolicy
import earth.worldwind.layer.cache.CachedBulkFeatureSource
import earth.worldwind.layer.cache.CacheEntry
import earth.worldwind.layer.cache.CachedTileSource
import earth.worldwind.layer.cache.GpkgFeatureStore
import earth.worldwind.layer.cache.GpkgTileStore
import earth.worldwind.layer.cache.TileSourceFactoryAdapter
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.layer.mercator.MercatorTiledImageLayer
import earth.worldwind.layer.mercator.MercatorTiledSurfaceImage
import earth.worldwind.layer.mvt.MvtVectorLayer
import earth.worldwind.layer.BulkFeatureLayer
import earth.worldwind.ogc.Wcs100ElevationCoverage
import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.LevelSet
import earth.worldwind.util.LevelSetConfig

/**
 * GPKG-native content reconstruction: handles `gpkg_contents` rows that don't have a
 * `gpkg_web_service` partner (e.g. an externally-imported .gpkg file). The dispatch is
 * by [CacheEntry.DataType] only — no `WebServiceInfo.type` is consulted because
 * none exists.
 *
 * Called from [GpkgContentManager.tryOpenNativeContent] which routes here when an entry
 * has [CacheEntry.service] = `null`.
 */
internal suspend fun GpkgContentManager.openGpkgNative(entry: CacheEntry): Any? {
    val content = geoPackage.getContent(entry.contentKey) ?: return null
    return when (entry.dataType) {
        CacheEntry.DataType.TILES -> openGpkgTileLayer(content)
        CacheEntry.DataType.VECTOR_TILES -> openGpkgVectorTileLayer(content)
        CacheEntry.DataType.COVERAGE -> openGpkgCoverage(content)
        CacheEntry.DataType.FEATURES -> openGpkgFeatureLayer(content)
    }
}

private suspend fun GpkgContentManager.openGpkgTileLayer(content: GpkgContent): TiledImageLayer? {
    val config = runCatching { geoPackage.buildLevelSetConfig(content) }.getOrNull() ?: return null
    val isMercator = content.srs?.organizationCoordsysId == GeoPackage.EPSG_3857
    val levelSet = if (isMercator) levelSetWithMercatorSector(config) else LevelSet(config)
    val store = GpkgTileStore(geoPackage, content, CachePolicy.UNBOUNDED)
    val tileFactory = TileSourceFactoryAdapter(CachedTileSource(inner = null, store = store))
    val name = content.identifier ?: content.tableName
    return if (isMercator) {
        MercatorTiledImageLayer(name, MercatorTiledSurfaceImage(tileFactory, levelSet))
    } else {
        TiledImageLayer(name, TiledSurfaceImage(tileFactory, levelSet))
    }
}

private suspend fun GpkgContentManager.openGpkgVectorTileLayer(content: GpkgContent): MvtVectorLayer? {
    val config = runCatching { geoPackage.buildLevelSetConfig(content) }.getOrNull() ?: return null
    val isMercator = content.srs?.organizationCoordsysId == GeoPackage.EPSG_3857
    val levelSet = if (isMercator) levelSetWithMercatorSector(config) else LevelSet(config)
    val store = GpkgTileStore(geoPackage, content, CachePolicy.UNBOUNDED)
    return MvtVectorLayer(
        source = CachedTileSource(inner = null, store = store),
        minZoom = levelSet.firstLevel.levelNumber,
        maxZoom = levelSet.firstLevel.levelNumber + levelSet.numLevels - 1,
        displayName = content.identifier ?: content.tableName,
    )
}

private suspend fun GpkgContentManager.openGpkgCoverage(content: GpkgContent): TiledElevationCoverage? {
    val tms = runCatching { geoPackage.buildTileMatrixSet(content) }.getOrNull() ?: return null
    val ancillary = geoPackage.getGriddedCoverage(content)
    val storedIsFloat = ancillary?.dataType == GeoPackage.FLOAT
    // Network outputFormat is irrelevant here — networkSource=null means the factory
    // never decodes a network blob. Keep a placeholder so existing toString() stays sane.
    val outputFormat = if (storedIsFloat) "image/tiff" else "application/bil16"
    val factory = createElevationSourceFactory(
        contentKey = content.tableName,
        tileMatrixSet = tms,
        networkSource = null,
        outputFormat = outputFormat,
        isFloat = storedIsFloat,
        displayName = content.identifier ?: content.tableName,
    )
    return Wcs100ElevationCoverage(
        serviceAddress = "", coverageName = content.tableName,
        outputFormat = outputFormat, tileMatrixSet = tms,
        elevationSourceFactory = factory,
    ).apply { displayName = content.identifier ?: content.tableName }
}

private suspend fun GpkgContentManager.openGpkgFeatureLayer(content: GpkgContent): BulkFeatureLayer? {
    val store = GpkgFeatureStore(geoPackage, content, CachePolicy.UNBOUNDED)
    val source = CachedBulkFeatureSource(inner = null, store = store)
    val layer = BulkFeatureLayer(source = source, displayName = content.identifier ?: content.tableName)
    layer.load()
    return layer
}

/**
 * Build a [LevelSet] whose [LevelSet.sector] and [LevelSet.tileOrigin] are
 * [MercatorSector] instances. The renderer's tile-type dispatch (in
 * [TileSourceFactoryAdapter.createTile]) requires `MercatorSector` for EPSG:3857
 * pyramids to emit [earth.worldwind.layer.mercator.MercatorImageTile] — plain `Sector`
 * skips the cubemap reprojection and the user sees "everything enabled, nothing
 * renders" on cached-only re-open.
 */
private fun levelSetWithMercatorSector(config: LevelSetConfig): LevelSet = LevelSet(
    sector = MercatorSector.fromSector(config.sector),
    tileOrigin = MercatorSector.fromSector(config.tileOrigin),
    firstLevelDelta = config.firstLevelDelta,
    numLevels = config.numLevels,
    tileWidth = config.tileWidth,
    tileHeight = config.tileHeight,
    levelOffset = config.levelOffset,
    firstLevelNumber = config.firstLevelNumber,
)
