package earth.worldwind.ogc.wfs

import earth.worldwind.geom.Sector
import earth.worldwind.layer.source.CachedFeatureRow
import earth.worldwind.layer.source.TiledFeatureSource
import earth.worldwind.ogc.WfsLayerFactory
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.HttpClientConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [TiledFeatureSource] that fetches WFS features per slippy-map tile — Strategy 2 (cache-through).
 * Each [fetchTile] issues a GetFeature bounded by the tile's [Sector] (so the server returns only
 * that tile's features, capped at [maxFeaturesPerTile]) and decodes the response via
 * [WfsFeatureDecoder]. Wrap in [earth.worldwind.layer.cache.CachedTiledFeatureSource] for the
 * GeoPackage-backed coverage cache; drive with [earth.worldwind.layer.TiledFeatureLayer].
 *
 * Unlike [WfsBulkFeatureSource] (one GetFeature for the whole layer) this never downloads more than
 * one viewport's worth of tiles, so it suits layers too large to fetch whole (e.g. global OSM
 * water polygons).
 */
class WfsTiledFeatureSource(
    val serviceAddress: String,
    val typeName: String,
    val serviceMetadata: String? = null,
    val cqlFilter: String? = null,
    /** Server-side `COUNT`/`MAXFEATURES` cap per tile request. */
    val maxFeaturesPerTile: Int? = null,
    /** Sortable property (typically the feature id) enabling strictly-stable `STARTINDEX` paging when
     *  [pageSize] is set. Omit for a single unpaged request per tile (relies on the server's order). */
    val sortBy: String? = null,
    /** When set, fetch a dense tile in stable pages of this size instead of one request (use with
     *  [sortBy]). Null = single request per tile. */
    val pageSize: Int? = null,
    val clientConfig: HttpClientConfig<*>.() -> Unit = {},
) : TiledFeatureSource {

    // One client for every tile request, created on first fetch and closed in close().
    private val clientDelegate = lazy {
        DefaultHttpClient(WfsLayerFactory.CONNECT_TIMEOUT_MS, WfsLayerFactory.REQUEST_TIMEOUT_MS, clientConfig)
    }

    // The GetCapabilities document, fetched once and reused for every tile so a viewport's worth of
    // tiles doesn't re-download it (which can overwhelm the server into timing it out). Seeded by the
    // optional constructor [serviceMetadata]; otherwise fetched lazily under [metadataMutex] on first use.
    private val metadataMutex = Mutex()
    private var cachedMetadata: String? = serviceMetadata

    private suspend fun resolveMetadata(): String? {
        cachedMetadata?.let { return it }
        return metadataMutex.withLock {
            // NonCancellable: the capabilities fetch is shared across all tiles, so aborting the one
            // tile that happened to trigger it (abort-on-view-change) must NOT cancel it for everyone.
            cachedMetadata ?: withContext(NonCancellable) {
                WfsLayerFactory.retrieveServiceMetadata(serviceAddress, clientDelegate.value)
            }?.also { cachedMetadata = it }
        }
    }

    override suspend fun fetchTile(z: Int, x: Int, y: Int, sector: Sector): Flow<CachedFeatureRow>? {
        // Fetch capabilities once and reuse; if unavailable, throw so the tile backs off and RETRIES
        // (returning null would cache it as a permanent empty tile) rather than letting createLayer
        // re-fetch the capabilities document per tile and storm the server.
        val metadata = resolveMetadata()
            ?: error("WfsTiledFeatureSource: WFS capabilities unavailable for $serviceAddress")
        // createLayer captures raw page bodies via onResponseBody; we only want the decoded rows.
        // An empty result returns an empty flow → the cache records it as a negative (no-features) tile.
        val rows = mutableListOf<CachedFeatureRow>()
        WfsLayerFactory.createLayer(
            serviceAddress = serviceAddress,
            typeName = typeName,
            serviceMetadata = metadata,
            sector = sector,
            maxFeatures = maxFeaturesPerTile,
            cqlFilter = cqlFilter,
            sortBy = sortBy,
            pageSize = pageSize,
            httpClient = clientDelegate.value,
            customLogicToApplyProperties = {},
            onResponseBody = { body, isGml -> rows += WfsFeatureDecoder.decode(body, isGml) },
        )
        return rows.asFlow()
    }

    override fun close() {
        if (clientDelegate.isInitialized()) clientDelegate.value.close()
    }

    companion object {
        /** Cache service-identity tag for tiled WFS layers (distinct from bulk [WfsBulkFeatureSource]). */
        const val SERVICE_TYPE = "WFSTiled"
    }
}
