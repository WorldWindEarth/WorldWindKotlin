@file:OptIn(LowLevelCacheApi::class)

package earth.worldwind.util

import earth.worldwind.formats.shapefile.ShapefileBulkFeatureSource
import earth.worldwind.layer.source.GeoJsonBulkFeatureSource
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.coverage.ElevationCoverage
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.layer.buildings.OsmBuildingsLayer
import earth.worldwind.layer.buildings.OverpassBuildingsSource
import earth.worldwind.layer.cache.CachedBulkFeatureSource
import earth.worldwind.layer.cache.CacheCategory
import earth.worldwind.layer.cache.CacheEntry
import earth.worldwind.layer.cache.CachedTileSource
import earth.worldwind.layer.cache.CachedTiledFeatureSource
import earth.worldwind.layer.source.TileSource
import earth.worldwind.layer.cache.TileSourceFactoryAdapter
import earth.worldwind.layer.cache.UrlTemplateImageTileSource
import earth.worldwind.layer.cache.WebServiceInfo
import earth.worldwind.layer.Layer
import earth.worldwind.layer.cache.CachePolicy
import earth.worldwind.layer.cache.attachWcs100ElevationCoverageCache
import earth.worldwind.layer.cache.attachWcs201ElevationCoverageCache
import earth.worldwind.layer.cache.attachWmsElevationCoverageCache
import earth.worldwind.layer.cache.attachWmsImageLayerCache
import earth.worldwind.layer.cache.attachWmtsImageLayerCache
import earth.worldwind.globe.elevation.TileSourceElevationSourceFactory
import earth.worldwind.ogc.WmsElevationTileSource
import earth.worldwind.ogc.Wcs100ElevationTileSource
import earth.worldwind.ogc.Wcs201ElevationTileSource
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.layer.mercator.MercatorTiledSurfaceImage
import earth.worldwind.layer.mercator.WebMercatorImageLayer
import earth.worldwind.layer.mvt.MvtVectorLayer
import earth.worldwind.layer.mvt.UrlTemplateMvtTileSource
import earth.worldwind.ogc.Wcs100ElevationCoverage
import earth.worldwind.ogc.Wcs201ElevationCoverage
import earth.worldwind.ogc.WmsElevationCoverage
import earth.worldwind.ogc.WmsImageLayer
import earth.worldwind.ogc.WmsLayerFactory
import earth.worldwind.ogc.WmtsImageLayer
import earth.worldwind.ogc.WmtsLayerFactory
import earth.worldwind.ogc.wfs.WfsBulkFeatureSource
import earth.worldwind.layer.BulkFeatureLayer
import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.geom.Location
import earth.worldwind.layer.cache.LowLevelCacheApi

/**
 * One reconstruction recipe — given a [ContentManager] and a [CacheEntry], produce a
 * layer/coverage/null. Implementations open the appropriate store on `contentManager`,
 * wrap a fresh network source in the matching cache decorator, and return the assembled
 * layer/coverage. To open in strict cache-only mode, call
 * [setOfflineOnly][earth.worldwind.layer.cache.setOfflineOnly] on the returned object.
 */
typealias CachedLayerOpener = suspend (
    contentManager: ContentManager,
    entry: CacheEntry,
    cachePolicy: CachePolicy,
) -> Any?

/**
 * Registry of `(dataType, serviceType) → CachedLayerOpener`. The same key pair the
 * 2.x cache surface uses for dispatch — `WmsImageLayer` and `WmsElevationCoverage` both
 * advertise `"WMS"` but are disambiguated by the `data_type` discriminator.
 *
 * Third-party layer types can [register] their own openers at startup; the registry is
 * append-only and not thread-safe (intended use is one-shot init).
 */
object CachedLayerRegistry {
    private val openers = mutableMapOf<Pair<CacheEntry.DataType, String>, CachedLayerOpener>()

    // Built-in openers for the standard layer types, registered in this object's init block.
    // It runs lazily on the first access to the registry (register / opener) on BOTH JVM and
    // JS. A top-level `val` initializer worked on JVM (the file class's <clinit> ran it) but
    // Kotlin/JS dead-code elimination drops an unreferenced top-level `val`, leaving the
    // registry empty on JS — the cause of the JS-only reopen-from-cache failure. Apps add
    // custom types via register(...) (which also triggers this init first).
    init {
        val tiles = CacheEntry.DataType.TILES
        val vectorTiles = CacheEntry.DataType.VECTOR_TILES
        val coverage = CacheEntry.DataType.COVERAGE
        val features = CacheEntry.DataType.FEATURES

        register(tiles, WmsImageLayer.SERVICE_TYPE) { cm, h, eviction ->
            cm.openWmsImageLayer(h, h.service!!, eviction)
        }
        register(tiles, WmtsImageLayer.SERVICE_TYPE) { cm, h, eviction ->
            cm.openWmtsImageLayer(h, h.service!!, eviction)
        }
        register(tiles, WebMercatorImageLayer.SERVICE_TYPE) { cm, h, eviction ->
            cm.openWebMercatorImageLayer(
                h, h.service!!.address, h.service.outputFormat ?: "image/png", h.service.isTransparent, eviction,
            )
        }

        register(vectorTiles, MvtVectorLayer.SERVICE_TYPE) { cm, h, eviction ->
            cm.openMvtVectorLayer(h, h.service!!.address, eviction)
        }

        register(coverage, Wcs100ElevationCoverage.SERVICE_TYPE) { cm, h, eviction ->
            cm.openWcs100ElevationCoverage(h, h.service!!, eviction)
        }
        register(coverage, Wcs201ElevationCoverage.SERVICE_TYPE) { cm, h, eviction ->
            cm.openWcs201ElevationCoverage(h, h.service!!, eviction)
        }
        // WmsElevationCoverage shares "WMS" with WmsImageLayer; the COVERAGE data_type
        // disambiguates here.
        register(coverage, WmsElevationCoverage.SERVICE_TYPE) { cm, h, eviction ->
            cm.openWmsElevationCoverage(h, h.service!!, eviction)
        }

        register(features, WfsBulkFeatureSource.SERVICE_TYPE) { cm, h, eviction ->
            cm.openWfsLayer(h, h.service!!, eviction)
        }
        register(features, ShapefileBulkFeatureSource.SERVICE_TYPE) { cm, h, eviction ->
            cm.openShapefileLayer(h, h.service!!.address, eviction)
        }
        register(features, GeoJsonBulkFeatureSource.SERVICE_TYPE) { cm, h, eviction ->
            cm.openGeoJsonLayer(h, eviction)
        }
        register(features, OverpassBuildingsSource.SERVICE_TYPE) { cm, h, eviction ->
            cm.openOsmBuildingsLayer(h, h.service!!.address, eviction)
        }
    }

    fun register(
        dataType: CacheEntry.DataType,
        serviceType: String,
        opener: CachedLayerOpener,
    ) {
        openers[dataType to serviceType] = opener
    }

    internal fun opener(
        dataType: CacheEntry.DataType,
        serviceType: String,
    ): CachedLayerOpener? = openers[dataType to serviceType]
}

/**
 * Reconstruct a layer / coverage from a [CacheEntry]. Internal — the public
 * surface is the reified [openLayer] / [openElevationCoverage] helpers below. Used by
 * [CachedLayerRegistry] opener callbacks and the cache dispatcher.
 *
 * Returns `null` when:
 *   * The entry has no registered web service AND
 *     [ContentManager.tryOpenNativeContent] doesn't recognise it.
 *   * The (data type, service type) pair isn't in [CachedLayerRegistry].
 */
@PublishedApi
internal suspend fun ContentManager.dispatchCachedEntry(
    entry: CacheEntry,
    cachePolicy: CachePolicy = CachePolicy.UNBOUNDED,
): Any? {
    val service = entry.service
    if (service == null) return tryOpenNativeContent(entry)
    val opener = CachedLayerRegistry.opener(entry.dataType, service.type) ?: return null
    return opener(this, entry, cachePolicy)
}

/** Untyped key-based dispatch. Marked [PublishedApi] internal so the reified
 *  [openLayer]/[openElevationCoverage] inline functions can call it at their inline sites,
 *  but not part of the public API surface. */
@PublishedApi
internal suspend fun ContentManager.dispatchCachedKey(
    contentKey: String,
    cachePolicy: CachePolicy = CachePolicy.UNBOUNDED,
): Any? = findEntry(contentKey)?.let { dispatchCachedEntry(it, cachePolicy) }

// ============================================================================
// Typed retrieval — layers vs elevation coverages.
// Layers are stacked into engine.layers; coverages contribute to globe.elevationModel.
// The two categories are split into separate function families so call sites can't
// accidentally pass a coverage to engine.layers.addLayer.
// ============================================================================

/**
 * Open [contentKey] as a layer narrowed to [T]. Returns `null` when the key is absent,
 * unregistered, backed by an elevation coverage (use [openElevationCoverage]), or the
 * materialized type doesn't match [T].
 *
 * Typical usage:
 * ```
 * val wms: TiledImageLayer? = cm.openLayer("WMS_Neo")          // any image-type
 * val mvt: MvtVectorLayer?  = cm.openLayer("MVT_Versatiles")   // exact subtype
 * val any: Layer?           = cm.openLayer("SomeKey")          // any layer
 * ```
 *
 * To open in strict cache-only mode, chain
 * [setOfflineOnly][earth.worldwind.layer.cache.setOfflineOnly]:
 * `cm.openLayer<TiledImageLayer>(key)?.also { it.setOfflineOnly() }`.
 *
 * For bulk-open-by-category use [open] with a [CacheCategory] key.
 */
suspend inline fun <reified T : Layer> ContentManager.openLayer(
    contentKey: String,
    cachePolicy: CachePolicy = CachePolicy.UNBOUNDED,
): T? = dispatchCachedKey(contentKey, cachePolicy) as? T

/**
 * Open [contentKey] as an elevation coverage narrowed to [T]. Returns `null` when the
 * key is absent, registered as a layer (use [openLayer]), or the materialized type
 * doesn't match [T]. Bound is the broad [ElevationCoverage] interface for symmetry with
 * [openLayer]'s [Layer] bound; today only [TiledElevationCoverage] subtypes are reachable
 * from cache, so non-tiled-typed callers always get `null`.
 *
 * Typical usage:
 * ```
 * val anyDem: TiledElevationCoverage?  = cm.openElevationCoverage("DEM_3DEP")
 * val wcs:    Wcs100ElevationCoverage? = cm.openElevationCoverage("WCS_3DEP")
 * ```
 */
suspend inline fun <reified T : ElevationCoverage> ContentManager.openElevationCoverage(
    contentKey: String,
    cachePolicy: CachePolicy = CachePolicy.UNBOUNDED,
): T? = dispatchCachedKey(contentKey, cachePolicy) as? T

/**
 * Bulk-open every cached entry whose [CacheEntry.dataType] matches [category]. Return
 * type follows the category's phantom witness:
 *
 * ```
 * val tiles:     List<TiledImageLayer>        = cm.open(CacheCategory.Tiles)
 * val mvt:       List<VectorLayer>            = cm.open(CacheCategory.VectorTiles)
 * val features:  List<VectorLayer>            = cm.open(CacheCategory.Features)
 * val coverages: List<TiledElevationCoverage> = cm.open(CacheCategory.Coverage)
 * ```
 *
 * Entries the dispatcher can't materialize (missing service info, no registered opener,
 * type cast mismatch) are silently dropped — the return list contains only successfully
 * reconstructed objects.
 *
 * For per-key opens use [openLayer] / [openElevationCoverage]; for narrower-than-category
 * filtering (e.g. WMS only) chain `.filterIsInstance<WmsImageLayer>()` on the result.
 */
@Suppress("UNCHECKED_CAST")
suspend fun <T : Any> ContentManager.open(
    category: CacheCategory<T>,
    cachePolicy: CachePolicy = CachePolicy.UNBOUNDED,
): List<T> = listEntries()
    .filter { it.dataType == category.dataType }
    .mapNotNull { dispatchCachedEntry(it, cachePolicy) as? T }

// Built-in openers are registered in CachedLayerRegistry's init block (above), which runs
// lazily on first registry access on every platform — see the note there for why a top-level
// `val` initializer was unsafe on Kotlin/JS.

// ============================================================================
// TILES (image) helpers
// ============================================================================

private suspend fun ContentManager.openWmsImageLayer(
    entry: CacheEntry, service: WebServiceInfo,
    cachePolicy: CachePolicy,
): TiledImageLayer? {
    val layerNames = service.layerName?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: return null
    // Offline-first: the factory reuses the persisted capabilities XML when present and
    // parseable (no network round-trip), and only issues GetCapabilities online when the
    // stored metadata is missing, blank, or undecodable. Cache is bound locally afterward.
    val layer = WmsLayerFactory.createLayer(
        serviceAddress = service.address,
        layerNames = layerNames,
        displayName = entry.displayName,
        serviceMetadata = service.metadata,
    )
    // The factory builds the layer with the service's declared extent. Override with
    // the gpkg's persisted bbox so the level-set sector reflects the actual cached-data
    // extent (which bulk-download may have narrowed below the service extent).
    entry.boundingSector?.let { layer.tiledSurfaceImage?.levelSet?.sector?.copy(it) }
    attachWmsImageLayerCache(layer, entry.contentKey, cachePolicy)
    return layer
}

private suspend fun ContentManager.openWmtsImageLayer(
    entry: CacheEntry, service: WebServiceInfo,
    cachePolicy: CachePolicy,
): TiledImageLayer? {
    val name = service.layerName ?: return null
    // Offline-first: persisted capabilities are reused when present and parseable; only a
    // missing / blank / undecodable metadata triggers an online GetCapabilities fetch.
    val layer = WmtsLayerFactory.createLayer(
        serviceAddress = service.address,
        layerName = name,
        displayName = entry.displayName,
        serviceMetadata = service.metadata,
    )
    entry.boundingSector?.let { layer.tiledSurfaceImage?.levelSet?.sector?.copy(it) }
    attachWmtsImageLayerCache(layer, entry.contentKey, cachePolicy)
    return layer
}

private suspend fun ContentManager.openWebMercatorImageLayer(
    entry: CacheEntry,
    urlTemplate: String,
    imageFormat: String,
    isTransparent: Boolean,

    cachePolicy: CachePolicy,
): WebMercatorImageLayer {
    val levelSet = tryRecoverLevelSet(entry.contentKey)
        ?: buildSlippyLevelSet(maxZoom = 21, tileSize = 256)
    val store = openImageTileStore(
        entry.contentKey, levelSet, imageFormat, isTransparent, cachePolicy, displayName = entry.displayName,
    )
    val network: TileSource? = UrlTemplateImageTileSource(urlTemplate)
    val source = CachedTileSource(network, store)
    val tileFactory = TileSourceFactoryAdapter(source, imageFormat)
    val tiledSurfaceImage = MercatorTiledSurfaceImage(tileFactory, levelSet)
    return WebMercatorImageLayer(urlTemplate, imageFormat, isTransparent, entry.displayName, tiledSurfaceImage)
}

private fun buildSlippyLevelSet(maxZoom: Int, tileSize: Int): LevelSet {
    val origin = MercatorSector()
    return LevelSet(
        sector = MercatorSector(),
        tileOrigin = origin,
        firstLevelDelta = Location(origin.deltaLatitude, origin.deltaLongitude),
        numLevels = maxZoom + 1,
        tileWidth = tileSize,
        tileHeight = tileSize,
    )
}

// ============================================================================
// VECTOR_TILES (MVT)
// ============================================================================

private suspend fun ContentManager.openMvtVectorLayer(
    entry: CacheEntry,
    urlTemplate: String,

    cachePolicy: CachePolicy,
): MvtVectorLayer {
    val levelSet = tryRecoverLevelSet(entry.contentKey)
        ?: buildSlippyLevelSet(maxZoom = 22, tileSize = 256)
    // tryRecoverLevelSet derives sector from gpkg bbox; the fallback slippy level-set is
    // global. If the gpkg has a persisted narrower bbox (bulk-download extent), apply it.
    entry.boundingSector?.let { levelSet.sector.copy(it) }
    val store = openVectorTileStore(entry.contentKey, levelSet, cachePolicy, displayName = entry.displayName)
    val network: TileSource? = UrlTemplateMvtTileSource(urlTemplate)
    val source = CachedTileSource(network, store)
    return MvtVectorLayer(source = source, displayName = entry.displayName)
}

// ============================================================================
// COVERAGE (elevation) helpers — all delegate to the createCoverage factories
// (TileMatrixSet overload) so reopen and creation share one cache-wiring path.
// ============================================================================

private suspend fun ContentManager.openWcs100ElevationCoverage(
    entry: CacheEntry, service: WebServiceInfo,
    cachePolicy: CachePolicy,
): TiledElevationCoverage {
    val coverageName = service.layerName.orEmpty()
    val outputFormat = service.outputFormat ?: "geotiff"
    val tms = resolveCoverageMatrixSet(entry)
    val coverage = Wcs100ElevationCoverage(
        service.address, coverageName, outputFormat, tms,
        TileSourceElevationSourceFactory(
            Wcs100ElevationTileSource(service.address, coverageName, outputFormat, tms),
            outputFormat,
        ),
    ).apply { displayName = entry.displayName }
    attachWcs100ElevationCoverageCache(
        coverage, entry.contentKey, cachePolicy,
        isFloat = entry.isFloat,
    )
    return coverage.applyPersistedSector(entry)
}

private suspend fun ContentManager.openWmsElevationCoverage(
    entry: CacheEntry, service: WebServiceInfo,
    cachePolicy: CachePolicy,
): TiledElevationCoverage {
    val coverageName = service.layerName.orEmpty()
    val outputFormat = service.outputFormat ?: "application/bil16"
    val tms = resolveCoverageMatrixSet(entry)
    val coverage = WmsElevationCoverage(
        service.address, coverageName, outputFormat, tms,
        TileSourceElevationSourceFactory(
            WmsElevationTileSource(service.address, coverageName, outputFormat, tms),
            outputFormat,
        ),
    ).apply { displayName = entry.displayName }
    attachWmsElevationCoverageCache(
        coverage, entry.contentKey, cachePolicy,
        isFloat = entry.isFloat,
    )
    return coverage.applyPersistedSector(entry)
}

private suspend fun ContentManager.openWcs201ElevationCoverage(
    entry: CacheEntry, service: WebServiceInfo,
    cachePolicy: CachePolicy,
): TiledElevationCoverage {
    // Persisted coverage-description XML is mandatory for WCS 2.0.1 reopen — the dispatcher
    // hands it to createCoverage as `serviceMetadata` so DescribeCoverage never fires.
    val coverage = Wcs201ElevationCoverage.createCoverage(
        serviceAddress = service.address,
        coverageName = service.layerName.orEmpty(),
        outputFormat = service.outputFormat ?: "image/tiff",
        serviceMetadata = service.metadata,
        displayName = entry.displayName,
    )
    attachWcs201ElevationCoverageCache(
        coverage, entry.contentKey, cachePolicy,
        isFloat = entry.isFloat,
    )
    return coverage.applyPersistedSector(entry)
}

/** Apply the gpkg's persisted bbox onto the coverage's `sector`. The coverage starts
 *  with the tileMatrixSet's full sector by default; this narrows it to the actual
 *  cached-data extent (which bulk-download may have set below the matrix). */
private fun TiledElevationCoverage.applyPersistedSector(entry: CacheEntry): TiledElevationCoverage =
    apply { entry.boundingSector?.let { sector.copy(it) } }

/**
 * Recover a [TileMatrixSet] for a coverage entry. Uses the platform's
 * [ContentManager.tryRecoverTileMatrixSet] hook (GeoPackage reads the stored pyramid;
 * JS / iOS fall through). When no hook returns a value, builds a default from the
 * entry's bounding sector.
 */
private suspend fun ContentManager.resolveCoverageMatrixSet(
    entry: CacheEntry,
): TileMatrixSet {
    tryRecoverTileMatrixSet(entry.contentKey)?.let { return it }
    val sector = entry.boundingSector ?: Sector().setFullSphere()
    return TileMatrixSet.fromTilePyramid(
        sector = sector,
        matrixWidth = if (sector.isFullSphere) 2 else 1,
        matrixHeight = 1,
        tileWidth = 256,
        tileHeight = 256,
        resolution = Angle.fromSeconds(1.0 / 3.0),
    )
}

// ============================================================================
// FEATURES helpers
// ============================================================================

private suspend fun ContentManager.openWfsLayer(
    entry: CacheEntry, service: WebServiceInfo,
    cachePolicy: CachePolicy,
): BulkFeatureLayer {
    val store = openFeatureStore(entry.contentKey, cachePolicy, displayName = entry.displayName)
    val network: WfsBulkFeatureSource? = WfsBulkFeatureSource(
        serviceAddress = service.address,
        layerName = service.layerName ?: "",
        serviceMetadata = service.metadata,
    )
    val source = CachedBulkFeatureSource(network, store)
    return BulkFeatureLayer(source = source, displayName = entry.displayName).also { it.load() }
}

private suspend fun ContentManager.openShapefileLayer(
    entry: CacheEntry, shpUrl: String,
    cachePolicy: CachePolicy,
): BulkFeatureLayer {
    val store = openFeatureStore(entry.contentKey, cachePolicy, displayName = entry.displayName)
    val network = ShapefileBulkFeatureSource(shpUrl)
    val source = CachedBulkFeatureSource(network, store)
    return BulkFeatureLayer(source = source, displayName = entry.displayName).also { it.load() }
}

/** GeoJSON has no reopen-time upstream: the document text was supplied inline at
 *  attachCache time and isn't persisted. The cache-only [CachedBulkFeatureSource] replays
 *  the stored rows; if the cache has been evicted the layer comes back empty. Callers that
 *  want to refresh must re-attach with the original text. */
private suspend fun ContentManager.openGeoJsonLayer(
    entry: CacheEntry,
    cachePolicy: CachePolicy,
): BulkFeatureLayer {
    val store = openFeatureStore(entry.contentKey, cachePolicy, displayName = entry.displayName)
    val source = CachedBulkFeatureSource(inner = null, store = store)
    return BulkFeatureLayer(source = source, displayName = entry.displayName).also { it.load() }
}

private suspend fun ContentManager.openOsmBuildingsLayer(
    entry: CacheEntry, endpoint: String,
    cachePolicy: CachePolicy,
): OsmBuildingsLayer {
    val store = openFeatureStore(entry.contentKey, cachePolicy, displayName = entry.displayName)
    val network = OverpassBuildingsSource(endpoint)
    val source = CachedTiledFeatureSource(network, store)
    return OsmBuildingsLayer(source = source, displayName = entry.displayName)
}
