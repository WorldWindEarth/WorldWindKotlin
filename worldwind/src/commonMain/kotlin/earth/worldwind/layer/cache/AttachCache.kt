@file:OptIn(LowLevelCacheApi::class)

package earth.worldwind.layer.cache

import earth.worldwind.formats.shapefile.ShapefileBulkFeatureSource
import earth.worldwind.globe.elevation.coverage.BasicElevationCoverage
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.layer.VectorLayer
import earth.worldwind.layer.buildings.OsmBuildingsLayer
import earth.worldwind.layer.buildings.OverpassBuildingsSource
import earth.worldwind.layer.mercator.WebMercatorImageLayer
import earth.worldwind.layer.mvt.MvtVectorLayer
import earth.worldwind.layer.mvt.UrlTemplateMvtTileSource
import earth.worldwind.layer.source.GeoJsonBulkFeatureSource
import earth.worldwind.layer.source.TileSource
import earth.worldwind.ogc.Wcs100ElevationCoverage
import earth.worldwind.ogc.Wcs100ElevationTileSource
import earth.worldwind.ogc.Wcs201ElevationCoverage
import earth.worldwind.ogc.Wcs201ElevationTileSource
import earth.worldwind.ogc.WmsElevationCoverage
import earth.worldwind.ogc.WmsElevationTileSource
import earth.worldwind.ogc.WmsImageLayer
import earth.worldwind.ogc.WmsTileSource
import earth.worldwind.ogc.WmtsImageLayer
import earth.worldwind.ogc.WmtsTileSource
import earth.worldwind.ogc.wfs.WfsBulkFeatureSource
import earth.worldwind.ogc.wfs.WfsTiledFeatureSource
import earth.worldwind.layer.BulkFeatureLayer
import earth.worldwind.layer.TiledFeatureLayer
import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.ContentManager
import earth.worldwind.geom.Location
import earth.worldwind.globe.elevation.coverage.WebElevationCoverage
import earth.worldwind.layer.cache.LowLevelCacheApi
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.util.LevelSet

/**
 * Common semantics for every `register` overload below.
 *
 * The flow:
 *   1. Open the matching store (image / vector-tile / feature / coverage) under [contentKey].
 *   2. Wrap the target's network source in the appropriate cache decorator, swapping it in
 *      place (`tileFactory` for image / MVT, `source` for feature, `elevationSourceFactory`
 *      for coverage).
 *   3. Record a `gpkg_web_service`-shaped service-identity row so the target can be reopened
 *      from cache on next launch via [earth.worldwind.util.openLayer] / [earth.worldwind.util.openElevationCoverage].
 *
 * **Rebind-friendly.** Registering a target whose source is already cache-attached (e.g. a
 * [earth.worldwind.layer.TiledImageLayer.clone] of a displayed, cached layer being bound to
 * a different content manager — the bulk-download flow) peels the upstream network source
 * back off the existing [CachedTileSource] and re-wraps it in the new store, rather than
 * nesting a cache inside a cache.
 *
 * Each layer family has its own overload so the compiler rejects uncacheable types like
 * `BackgroundLayer` or `CompassLayer` at the call site.
 */

/** Register a tiled image layer (WMS / WMTS / WebMercator). */
suspend fun ContentManager.attachCache(
    layer: TiledImageLayer,
    contentKey: String,
    cachePolicy: CachePolicy = CachePolicy.UNBOUNDED,
) = when (layer) {
    is WmsImageLayer -> attachWmsImageLayerCache(layer, contentKey, cachePolicy)
    is WmtsImageLayer -> attachWmtsImageLayerCache(layer, contentKey, cachePolicy)
    is WebMercatorImageLayer -> attachWebMercatorImageLayerCache(layer, contentKey, cachePolicy)
    else -> error("ContentManager.attachCache: unsupported TiledImageLayer subtype ${layer::class.simpleName}")
}

/**
 * Register a vector layer. Dispatches by concrete subtype to record the right
 * service-identity row:
 *   * [MvtVectorLayer] — source must be a fresh [UrlTemplateMvtTileSource].
 *   * [BulkFeatureLayer] — wrapped `BulkFeatureSource` determines whether the
 *     service-identity row is `"WFS"` or `"Shapefile"`.
 *   * [OsmBuildingsLayer] — source must be a fresh [OverpassBuildingsSource].
 */
suspend fun ContentManager.attachCache(
    layer: VectorLayer,
    contentKey: String,
    cachePolicy: CachePolicy = CachePolicy.UNBOUNDED,
) = when (layer) {
    is MvtVectorLayer -> attachMvtVectorLayerCache(layer, contentKey, cachePolicy)
    is BulkFeatureLayer -> attachBulkFeatureLayerCache(layer, contentKey, cachePolicy)
    is OsmBuildingsLayer -> attachOsmBuildingsLayerCache(layer, contentKey, cachePolicy)
    is TiledFeatureLayer -> attachTiledFeatureLayerCache(layer, contentKey, cachePolicy)
    else -> error("ContentManager.attachCache: unsupported VectorLayer subtype ${layer::class.simpleName}")
}

/**
 * Register a tiled elevation coverage.
 *
 * @param isFloat First-creation hint for the gpkg `dataType` column (Int16 vs Float32).
 *   Defaults to `false` (Int16). On reopen of an existing cache row the stored value
 *   always wins; this hint is silently ignored. Cf. [CacheEntry.isFloat].
 */
suspend fun ContentManager.attachCache(
    coverage: TiledElevationCoverage,
    contentKey: String,
    cachePolicy: CachePolicy = CachePolicy.UNBOUNDED,
    isFloat: Boolean = false,
) = when (coverage) {
    is BasicElevationCoverage -> attachBasicElevationCoverageCache(coverage, contentKey, cachePolicy, isFloat)
    is WmsElevationCoverage -> attachWmsElevationCoverageCache(coverage, contentKey, cachePolicy, isFloat)
    is Wcs100ElevationCoverage -> attachWcs100ElevationCoverageCache(coverage, contentKey, cachePolicy, isFloat)
    is Wcs201ElevationCoverage -> attachWcs201ElevationCoverageCache(coverage, contentKey, cachePolicy, isFloat)
    else -> error("ContentManager.attachCache: unsupported TiledElevationCoverage subtype ${coverage::class.simpleName}")
}

// ============================================================================
// Internal per-type cache-attach entry points used by the public `attachCache`
// overloads above and by the reopen dispatcher in OpenCachedLayer.kt. App code
// that wants strict cache-only reads should `setOfflineOnly()` on the returned
// layer/coverage after `attachCache` or `openLayer` / `openElevationCoverage`.
// ============================================================================

internal suspend fun ContentManager.attachWebMercatorImageLayerCache(
    layer: WebMercatorImageLayer, contentKey: String,
    cachePolicy: CachePolicy,
) {
    val tsi = layer.tiledSurfaceImage ?: return
    // No fresh-factory guard needed: the network source is rebuilt from serviceAddress below,
    // so a clone whose factory already wraps a CachedTileSource (rebind to a different content
    // manager — the bulk-download flow) is overwritten cleanly, not nested.
    val store = openImageTileStore(
        contentKey, tsi.levelSet, layer.imageFormat, layer.isTransparent, cachePolicy,
        displayName = layer.displayName,
    )
    val networkSource = UrlTemplateImageTileSource(layer.serviceAddress)
    tsi.tileFactory = TileSourceFactoryAdapter(CachedTileSource(networkSource, store), layer.imageFormat)
    registerWebService(contentKey, WebServiceInfo(
        type = WebMercatorImageLayer.SERVICE_TYPE,
        address = layer.serviceAddress,
        outputFormat = layer.imageFormat,
        isTransparent = layer.isTransparent,
    ))
}

internal suspend fun ContentManager.attachWmsImageLayerCache(
    layer: WmsImageLayer, contentKey: String,
    cachePolicy: CachePolicy,
) {
    val tsi = layer.tiledSurfaceImage ?: return
    val networkSource = requireFreshTileFactorySource<WmsTileSource>(tsi, "WmsImageLayer")
    val store = openImageTileStore(
        contentKey, tsi.levelSet, layer.imageFormat, layer.isTransparent, cachePolicy,
        displayName = layer.displayName,
    )
    tsi.tileFactory = TileSourceFactoryAdapter(
        CachedTileSource(networkSource, store),
        layer.imageFormat,
    )
    registerWebService(contentKey, WebServiceInfo(
        type = WmsImageLayer.SERVICE_TYPE,
        address = layer.serviceAddress,
        layerName = layer.layerName,
        outputFormat = layer.imageFormat,
        metadata = layer.serviceMetadata,
        isTransparent = layer.isTransparent,
    ))
}

internal suspend fun ContentManager.attachWmtsImageLayerCache(
    layer: WmtsImageLayer, contentKey: String,
    cachePolicy: CachePolicy,
) {
    val tsi = layer.tiledSurfaceImage ?: return
    val networkSource = requireFreshTileFactorySource<WmtsTileSource>(tsi, "WmtsImageLayer")
    val store = openImageTileStore(
        contentKey, tsi.levelSet, layer.imageFormat, layer.isTransparent, cachePolicy,
        displayName = layer.displayName,
    )
    tsi.tileFactory = TileSourceFactoryAdapter(
        CachedTileSource(networkSource, store),
        layer.imageFormat,
    )
    registerWebService(contentKey, WebServiceInfo(
        type = WmtsImageLayer.SERVICE_TYPE,
        address = layer.serviceAddress,
        layerName = layer.layerName,
        outputFormat = layer.imageFormat,
        metadata = layer.serviceMetadata,
    ))
}

internal suspend fun ContentManager.attachMvtVectorLayerCache(
    layer: MvtVectorLayer, contentKey: String,
    cachePolicy: CachePolicy,
) {
    // Peel the upstream source off an already-cache-attached layer (rebind to a different
    // content manager) so we re-wrap the bare source, not a cache inside a cache.
    val current = layer.source
    val network = (current as? CachedTileSource)?.networkSource as? UrlTemplateMvtTileSource
        ?: current as? UrlTemplateMvtTileSource
        ?: error("MvtVectorLayer wraps a non-URL-template source: ${current::class.simpleName}")
    val levelSet = buildMvtLevelSet(layer.minZoom, layer.maxZoom)
    val store = openVectorTileStore(contentKey, levelSet, cachePolicy, displayName = layer.displayName)
    layer.source = CachedTileSource(network, store)
    registerWebService(contentKey, WebServiceInfo(
        type = MvtVectorLayer.SERVICE_TYPE,
        address = network.urlTemplate,
        outputFormat = "application/vnd.mapbox-vector-tile",
    ))
}

private fun buildMvtLevelSet(minZoom: Int, maxZoom: Int): LevelSet {
    val origin = MercatorSector()
    return LevelSet(
        sector = origin,
        tileOrigin = origin,
        firstLevelDelta = Location(origin.deltaLatitude, origin.deltaLongitude),
        numLevels = maxZoom + 1,
        tileWidth = 256,
        tileHeight = 256,
        levelOffset = minZoom,
    )
}

internal suspend fun ContentManager.attachBulkFeatureLayerCache(
    layer: BulkFeatureLayer, contentKey: String,
    cachePolicy: CachePolicy,
) {
    // Peel the upstream source off an already-cache-attached layer (rebind to a different
    // content manager — the bulk-download flow) so we re-wrap the bare source, not a cache
    // inside a cache. A freshly-built layer has the bare source directly.
    val current = (layer.source as? CachedBulkFeatureSource)?.networkSource ?: layer.source
    val info: WebServiceInfo = when (current) {
        is ShapefileBulkFeatureSource -> WebServiceInfo(
            type = ShapefileBulkFeatureSource.SERVICE_TYPE,
            address = current.shpUrl,
        )
        is WfsBulkFeatureSource -> WebServiceInfo(
            type = WfsBulkFeatureSource.SERVICE_TYPE,
            address = current.serviceAddress,
            layerName = current.layerName,
            metadata = current.serviceMetadata,
        )
        // GeoJSON has no upstream URL — the text was supplied inline. Across launches the
        // cache replays via [CachedBulkFeatureSource] with `inner = null`; on reopen the
        // opener constructs a cache-only layer from the persisted rows. `address` is a
        // placeholder so the gpkg row is well-formed; the opener doesn't read it.
        is GeoJsonBulkFeatureSource -> WebServiceInfo(
            type = GeoJsonBulkFeatureSource.SERVICE_TYPE,
            address = "inline",
        )
        else -> error("BulkFeatureLayer wraps unknown BulkFeatureSource: ${current::class.simpleName}")
    }
    val store = openFeatureStore(contentKey, cachePolicy, displayName = layer.displayName)
    layer.source = CachedBulkFeatureSource(current, store)
    registerWebService(contentKey, info)
}

internal suspend fun ContentManager.attachOsmBuildingsLayerCache(
    layer: OsmBuildingsLayer, contentKey: String,
    cachePolicy: CachePolicy,
) {
    // Peel the upstream source off an already-cache-attached layer (rebind to a different
    // content manager) so we re-wrap the bare source, not a cache inside a cache.
    val current = layer.source
    val network = (current as? CachedTiledFeatureSource)?.networkSource as? OverpassBuildingsSource
        ?: current as? OverpassBuildingsSource
        ?: error("OsmBuildingsLayer wraps a non-Overpass source: ${current::class.simpleName}")
    val store = openFeatureStore(contentKey, cachePolicy, displayName = layer.displayName)
    layer.source = CachedTiledFeatureSource(network, store)
    registerWebService(contentKey, WebServiceInfo(
        type = OverpassBuildingsSource.SERVICE_TYPE,
        address = network.endpoint,
        metadata = OsmBuildingsLayer.encodeCacheConfig(layer),
    ))
}

internal suspend fun ContentManager.attachTiledFeatureLayerCache(
    layer: TiledFeatureLayer, contentKey: String,
    cachePolicy: CachePolicy,
) {
    // Peel the upstream source off an already-cache-attached layer so we re-wrap the bare source.
    val current = layer.source
    val network = (current as? CachedTiledFeatureSource)?.networkSource as? WfsTiledFeatureSource
        ?: current as? WfsTiledFeatureSource
        ?: error("TiledFeatureLayer wraps a non-WFS tiled source: ${current::class.simpleName}")
    val store = openFeatureStore(contentKey, cachePolicy, displayName = layer.displayName)
    layer.source = CachedTiledFeatureSource(network, store)
    registerWebService(contentKey, WebServiceInfo(
        type = WfsTiledFeatureSource.SERVICE_TYPE,
        address = network.serviceAddress,
        layerName = network.typeName,
        metadata = network.serviceMetadata,
    ))
}

// ============================================================================
// Elevation coverages
// ============================================================================

internal suspend fun ContentManager.attachBasicElevationCoverageCache(
    coverage: BasicElevationCoverage, contentKey: String,
    cachePolicy: CachePolicy, isFloat: Boolean,
) {
    val tms = BasicElevationCoverage.buildTileMatrixSet()
    val network: TileSource? = WmsElevationTileSource(
        serviceAddress = BasicElevationCoverage.SERVICE_ADDRESS,
        coverageName = BasicElevationCoverage.COVERAGE_NAME,
        outputFormat = BasicElevationCoverage.OUTPUT_FORMAT,
        tileMatrixSet = tms,
    )
    coverage.elevationSourceFactory = createElevationSourceFactory(
        contentKey = contentKey,
        tileMatrixSet = tms,
        networkSource = network,
        outputFormat = BasicElevationCoverage.OUTPUT_FORMAT,
        isFloat = resolveIsFloat(contentKey, isFloat),
        displayName = coverage.displayName,
        cachePolicy = cachePolicy,
    )
    registerWebService(contentKey, WebServiceInfo(
        type = WmsElevationCoverage.SERVICE_TYPE,
        address = BasicElevationCoverage.SERVICE_ADDRESS,
        layerName = BasicElevationCoverage.COVERAGE_NAME,
        outputFormat = BasicElevationCoverage.OUTPUT_FORMAT,
    ))
}

internal suspend fun ContentManager.attachWmsElevationCoverageCache(
    coverage: WmsElevationCoverage, contentKey: String,
    cachePolicy: CachePolicy, isFloat: Boolean,
) = attachElevationCoverageCache(
    coverage, contentKey, cachePolicy, isFloat,
    network = { WmsElevationTileSource(it.serviceAddress, it.coverageName, it.outputFormat, it.tileMatrixSet) },
    serviceType = WmsElevationCoverage.SERVICE_TYPE,
    serviceMetadata = null,
)

internal suspend fun ContentManager.attachWcs100ElevationCoverageCache(
    coverage: Wcs100ElevationCoverage, contentKey: String,
    cachePolicy: CachePolicy, isFloat: Boolean,
) = attachElevationCoverageCache(
    coverage, contentKey, cachePolicy, isFloat,
    network = { Wcs100ElevationTileSource(it.serviceAddress, it.coverageName, it.outputFormat, it.tileMatrixSet) },
    serviceType = Wcs100ElevationCoverage.SERVICE_TYPE,
    serviceMetadata = null,
)

internal suspend fun ContentManager.attachWcs201ElevationCoverageCache(
    coverage: Wcs201ElevationCoverage, contentKey: String,
    cachePolicy: CachePolicy, isFloat: Boolean,
) = attachElevationCoverageCache(
    coverage, contentKey, cachePolicy, isFloat,
    network = { Wcs201ElevationTileSource(it.serviceAddress, it.coverageName, it.outputFormat, it.tileMatrixSet) },
    serviceType = Wcs201ElevationCoverage.SERVICE_TYPE,
    serviceMetadata = coverage.serviceMetadata,
)

/** Shared elevation registration body: wraps the network source in a cache-aware
 *  ElevationSourceFactory, swaps it into the coverage, and writes the service-identity row. */
private suspend fun <C> ContentManager.attachElevationCoverageCache(
    coverage: C,
    contentKey: String,
    cachePolicy: CachePolicy,
    isFloat: Boolean,

    network: (C) -> TileSource,
    serviceType: String,
    serviceMetadata: String?,
) where C : TiledElevationCoverage,
        C : WebElevationCoverage {
    val networkSource: TileSource? = network(coverage)
    coverage.elevationSourceFactory = createElevationSourceFactory(
        contentKey = contentKey,
        tileMatrixSet = coverage.tileMatrixSet,
        networkSource = networkSource,
        outputFormat = coverage.outputFormat,
        isFloat = resolveIsFloat(contentKey, isFloat),
        displayName = coverage.displayName,
        cachePolicy = cachePolicy,
    )
    registerWebService(contentKey, WebServiceInfo(
        type = serviceType,
        address = coverage.serviceAddress,
        layerName = coverage.coverageName,
        outputFormat = coverage.outputFormat,
        metadata = serviceMetadata,
    ))
}

// ============================================================================
// Internals
// ============================================================================

/** Reconcile the caller-supplied `isFloat` creation hint against any persisted gpkg
 *  `dataType`. When a cache row already exists, the stored value is the source of truth.
 *  The hint only matters on first creation. */
private suspend fun ContentManager.resolveIsFloat(
    contentKey: String, isFloatHint: Boolean,
): Boolean = findEntry(contentKey)?.isFloat ?: isFloatHint

/** Extract the layer's upstream network source as a [T].
 *
 *  When the layer is already cache-attached — e.g. a [TiledImageLayer.clone] of a displayed,
 *  cached layer being rebound to a different content manager (the bulk-download flow) — the
 *  tile factory's source is a [CachedTileSource] bound to the *previous* store. Peel its
 *  [CachedTileSource.networkSource] back off so we re-wrap the bare upstream source in the
 *  new store rather than nesting a cache inside a cache. A freshly-built layer has the bare
 *  source directly and is used as-is. */
private inline fun <reified T : TileSource> requireFreshTileFactorySource(
    tsi: TiledSurfaceImage, layerName: String,
): T {
    val rawSource = (tsi.tileFactory as? TileSourceFactoryAdapter)?.source
    val networkSource = (rawSource as? CachedTileSource)?.networkSource ?: rawSource
    return networkSource as? T
        ?: error("$layerName: expected ${T::class.simpleName} as factory source, got ${networkSource?.let { it::class.simpleName }}")
}
