package earth.worldwind.ogc.wfs

import earth.worldwind.geom.Sector
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.layer.ViewportRefreshDriver
import earth.worldwind.ogc.WfsLayerFactory
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Renderable
import earth.worldwind.util.http.LazyHttpClient
import io.ktor.client.HttpClientConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A [RenderableLayer] whose contents are sourced from an OGC WFS endpoint and can be
 * re-fetched at will. Use this when the underlying feature set is too large to fetch
 * once — call [refresh] yourself with the current viewport [Sector], or set
 * [autoRefresh] and let the layer re-fetch the visible extent automatically during
 * render (BBOX-bounded, so only features inside the visible globe extent are loaded).
 *
 * Each fetch internally invokes [WfsLayerFactory.createLayer] (so capabilities
 * negotiation, output-format selection, OWS exception handling, and GeoJSON/GML
 * decoding all flow through the same code path) and atomically swaps the renderables.
 *
 * Auto-refresh is render-driven (the same pattern as [earth.worldwind.layer.mvt.MvtVectorLayer]
 * and [earth.worldwind.layer.buildings.OsmBuildingsLayer]) rather than wired to a
 * navigator-change listener: [doRender] hands `rc.terrain.sector` to a [ViewportRefreshDriver],
 * which debounces and runs the fetch off the render thread, then swaps the result in. This
 * always hits the network — for a download-once, read-by-viewport cache use a [BulkFeatureLayer]
 * with a GeoPackage-backed source and `autoRefreshViewport`.
 */
class WfsLayer(
    private val serviceAddress: String,
    private val typeName: String,
    displayName: String? = null,
    private val cqlFilter: String? = null,
    private val customLogicToApplyProperties: Renderable.(LinkedHashMap<String, Any?>) -> Unit = {},
    private val pageSize: Int? = null,
    private val clientConfig: HttpClientConfig<*>.() -> Unit = {},
    private val driver: ViewportRefreshDriver = ViewportRefreshDriver(),
) : RenderableLayer(displayName ?: typeName) {

    /** When `true`, [doRender] re-fetches features for the visible viewport automatically.
     *  Always pair with [autoRefreshMaxFeatures] to bound memory on dense layers. */
    var autoRefresh = false
    /** Per-fetch server-side feature cap applied during [autoRefresh] (WFS `COUNT`/`MAXFEATURES`).
     *  `null` lets the server return every feature inside the BBOX — leave set on large layers. */
    var autoRefreshMaxFeatures: Int? = null
    /** Wait this long after the viewport stops changing before firing an auto-refresh. */
    var autoRefreshDebounceMillis: Long
        get() = driver.debounceMillis
        set(value) { driver.debounceMillis = value }
    /** Fraction the visible sector is padded by on each side before fetching (hysteresis). */
    var autoRefreshMargin: Double
        get() = driver.margin
        set(value) { driver.margin = value }

    /**
     * Fetch features for the given [sector] (or the feature type's full WGS84 bounding
     * box if null) and atomically replace this layer's renderables. Suspends until the
     * fetch completes; drive it yourself from a navigator handler, or use [autoRefresh].
     */
    suspend fun refresh(sector: Sector? = null, maxFeatures: Int? = null) {
        val newRenderables = fetch(sector, maxFeatures)
        clearRenderables()
        addAllRenderables(newRenderables)
    }

    // One client reused across every refresh (and the one-time capabilities fetch) instead of the
    // fresh client-per-fetch that WfsLayerFactory.createLayer would otherwise create — keeps the
    // engine's connection pool warm across a pan/zoom's worth of refreshes.
    private val clientDelegate = LazyHttpClient(config = clientConfig)

    // GetCapabilities fetched once and reused for every refresh, so an auto-refreshing viewport
    // doesn't re-download the (large) capabilities document on every pan — which can overwhelm a
    // server into timing the capabilities request out. Fetched lazily under [metadataMutex].
    private val metadataMutex = Mutex()
    private var cachedMetadata: String? = null

    private suspend fun resolveMetadata(): String? {
        cachedMetadata?.let { return it }
        return metadataMutex.withLock {
            // NonCancellable: the capabilities fetch is shared across all refreshes, so aborting the
            // one refresh that happened to trigger it must NOT cancel it for everyone.
            cachedMetadata ?: withContext(NonCancellable) {
                WfsLayerFactory.retrieveServiceMetadata(serviceAddress, clientDelegate.value)
            }?.also { cachedMetadata = it }
        }
    }

    /** Cancel any in-flight auto-refresh, tear down the driver's scope, and release the HTTP client.
     *  Call when discarding the layer so a background fetch doesn't outlive it. */
    fun cancel() {
        driver.cancel()
        clientDelegate.close()
    }

    override fun doRender(rc: RenderContext) {
        // Skip pick mode — its tiny cursor frustum isn't the visible extent.
        if (autoRefresh && !rc.isPickMode) {
            driver.onRender(
                rc.terrain.sector,
                apply = { clearRenderables(); addAllRenderables(it) },
                fetch = { fetch(it, autoRefreshMaxFeatures) },
            )
        }
        super.doRender(rc)
    }

    private suspend fun fetch(sector: Sector?, maxFeatures: Int?): List<Renderable> = WfsLayerFactory.createLayer(
        serviceAddress = serviceAddress,
        typeName = typeName,
        // Reuse the cached capabilities document and the long-lived client across refreshes; a null
        // metadata (transient GetCapabilities failure) lets createLayer re-resolve and surface the error.
        serviceMetadata = resolveMetadata(),
        displayName = displayName,
        sector = sector,
        maxFeatures = maxFeatures,
        cqlFilter = cqlFilter,
        customLogicToApplyProperties = customLogicToApplyProperties,
        pageSize = pageSize,
        httpClient = clientDelegate.value,
    ).toList()
}
