package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.layer.CompassLayer
import earth.worldwind.layer.Layer
import earth.worldwind.layer.ViewControlsLayer
import earth.worldwind.layer.atmosphere.AtmosphereLayer
import earth.worldwind.layer.cache.BlobStore
import earth.worldwind.layer.cache.NoOpBlobStore
import earth.worldwind.layer.cache.WebServiceInfo
import earth.worldwind.layer.ogc3d.Ogc3dTilesLayer
import earth.worldwind.layer.ogc3d.TilesetSource
import earth.worldwind.layer.ogc3d.auth.Ogc3dAuthCredentials
import earth.worldwind.layer.starfield.StarFieldLayer
import earth.worldwind.layer.ogc3d.auth.NoAuthProvider
import earth.worldwind.layer.ogc3d.auth.TilesetAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Streams an OGC 3D Tiles 1.0 photogrammetry dataset. Default points at the **ArcGIS
 * Online Utrecht Integrated Mesh** (no auth, public). The [cacheProvider] hook lets the
 * platform-specific entry point hand in a persistent `BlobStore` (GeoPackage on
 * JVM/Android, IndexedDB on web, filesystem on iOS) so tile bytes survive across
 * sessions.
 *
 * Alternative datasets:
 *  - **Aarhus, Denmark** photogrammetry (no auth):
 *    `https://sdfe-hosting.virtualcitymap.de/Aarhus/datasource-data/Aarhus_Mesh/tileset.json`
 *  - **Cesium Ion** assets via `BearerTokenAuthProvider(ionToken)`.
 *  - **Google Photorealistic 3D Tiles** via `GoogleTilesAuthProvider(apiKey)` — auto-enables
 *    the Google-specific worldMatrix transpose + skip-rotation pair.
 *  - **Farsight Vision drone tilesets** via `CustomHeadersAuthProvider("X-API-Key" to ...)`
 *    + `installDracoDecoder()` before the layer is built.
 */
open class Ogc3dTilesTutorial(
    engine: WorldWind,
    /** Optional persistent cache for tile content. The platform-specific entry point
     *  supplies a `GpkgContentManager` / `WebContentManager` / `IosContentManager` backing
     *  store; when null the layer falls back to network-only. The lambda receives a
     *  [WebServiceInfo] describing the source so it can persist registration metadata via
     *  `ContentManager.registerWebService` — that's what makes the cache visible in
     *  `listEntries()` and reopenable from a foreign GPKG. Invoked once during start(). */
    private val cacheProvider: (suspend (info: WebServiceInfo) -> BlobStore)? = null,
) : AbstractTutorial(engine) {

    protected open val tilesetUri: String =
        "https://tiles.arcgis.com/tiles/V6ZHFr6zdgNZuVG0/arcgis/rest/services/" +
            "Utrecht_3D_Tiles_Integrated_Mesh/3DTilesServer/tileset.json"

    protected open val layerDisplayName: String = "OGC 3D Tiles"

    /** Auth handler for the tileset endpoint. Subclasses override for Bearer / Google /
     *  custom-header auth; the default [NoAuthProvider] is correct for public datasets. */
    protected open val authProvider: TilesetAuthProvider = NoAuthProvider

    /** Initial camera position. Subclasses override for dataset-specific framing. */
    protected open val cameraLookAt: LookAt = LookAt(
        position = Position(52.0907.degrees, 5.1214.degrees, 5.0),
        altitudeMode = AltitudeMode.ABSOLUTE,
        range = 3000.0,
        heading = 0.0.degrees,
        tilt = 60.0.degrees,
        roll = 0.0.degrees,
    )

    /** Target projected pixel error. Lower = sharper at higher fetch / memory cost. */
    protected open val maxScreenSpaceError: Double = 16.0

    /** Soft GPU-byte budget for the 3D Tiles working set. Drives the SSE-pressure feedback. */
    protected open val maxMemoryFootprintBytes: Long = Ogc3dTilesLayer.DEFAULT_MAX_MEMORY_FOOTPRINT_BYTES

    /** Per-host concurrent fetch + parse cap. Default 32 is fine for cheap meshes but
     *  thrashes for SPZ where each in-flight parse holds ~70 MB transient (inflate body +
     *  decode FloatArrays). Gaussian tutorials should override to 2-4. */
    protected open val fetchConcurrency: Int = 32

    /** Replace base imagery + terrain with a minimal sky/UI setup. Default false keeps
     *  the existing scene as a backdrop; override to true for globe-coverage photogrammetry
     *  (e.g. Google Photorealistic) where the 3D Tiles layer is the visible ground. */
    protected open val replaceBaseLayers: Boolean = false

    private var layer: Ogc3dTilesLayer? = null

    private var initScope: CoroutineScope = newInitScope()
    private var initJob: Job? = null

    private fun newInitScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Snapshot of the layers that were present when [start] ran. Restored verbatim in
     *  [stop] so the host fragment / activity / tutorial chain doesn't see any mutation
     *  side-effects from running this tutorial. */
    private var savedLayers: List<Layer> = emptyList()

    /** Guards against a re-entrant [start] (lifecycle race calling start() twice without
     *  a [stop] between) overwriting the snapshots with the already-minimised scene. */
    private var running: Boolean = false

    override val sunAzimuthDegrees: Double = 180.0
    override val sunElevationDegrees: Double = 50.0

    override fun start() {
        super.start()
        if (running) return
        running = true
        if (replaceBaseLayers) {
            // Snapshot the existing scene so stop() can put everything back the way it was.
            savedLayers = engine.layers.toList()
            // Replace with a minimal scene: sky + atmosphere + UI overlays. No base imagery
            // — globe-scale gaps between regional photoreal tilesets are accepted.
            engine.layers.clearLayers()
            engine.layers.addLayer(StarFieldLayer())
            engine.layers.addLayer(AtmosphereLayer())
            engine.layers.addLayer(CompassLayer())
            engine.layers.addLayer(ViewControlsLayer())
        }
        // Open the persistent cache off the render thread; the layer is added once the
        // store is ready so the first tile fetch already sees it. Falls back to network-
        // only when no [cacheProvider] is configured or the store fails to open.
        initJob = initScope.launch {
            // Source descriptor persisted via ContentManager.registerWebService so the cache
            // appears in listEntries() and can be reopened. layerName carries the auth-provider
            // class name as a discriminator (NoAuthProvider / CesiumIonAuthProvider / …);
            // metadata carries the serialized credentials so a single ContentManager call can
            // re-instantiate the layer offline. Same local-storage tradeoff as keeping creds
            // in Room / Keychain / SharedPreferences.
            val info = WebServiceInfo(
                type = "3DTiles",
                address = tilesetUri,
                layerName = authProvider.discriminator,
                metadata = Ogc3dAuthCredentials.encode(authProvider),
            )
            val blobStore = cacheProvider?.let { provider ->
                try {
                    provider(info)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    null
                }
            } ?: NoOpBlobStore
            val newLayer = Ogc3dTilesLayer(
                source = TilesetSource(
                    rootUri = tilesetUri,
                    displayName = layerDisplayName,
                    authProvider = authProvider,
                ),
                blobStore = blobStore,
                fetchConcurrency = fetchConcurrency,
            ).apply {
                maxScreenSpaceError = this@Ogc3dTilesTutorial.maxScreenSpaceError
                maxMemoryFootprintBytes = this@Ogc3dTilesTutorial.maxMemoryFootprintBytes
            }
            configureLayer(newLayer)
            engine.layers.addLayer(newLayer)
            layer = newLayer
            WorldWind.requestRedraw()
        }
        engine.cameraFromLookAt(cameraLookAt)
        WorldWind.requestRedraw()
    }

    override fun stop() {
        super.stop()
        if (!running) return
        running = false
        initJob?.cancel()
        initJob = null
        // Recreate so the next start() can launch again — a cancelled scope stays cancelled.
        initScope.cancel()
        initScope = newInitScope()
        layer?.let {
            engine.layers.removeLayer(it)
            it.shutdown()
        }
        layer = null
        if (replaceBaseLayers) {
            // Restore the original scene snapshotted in [start].
            engine.layers.clearLayers()
            for (l in savedLayers) engine.layers.addLayer(l)
            savedLayers = emptyList()
        }
    }

    /** Subclass hook for layer-level config not covered by the protected vals — e.g.
     *  `altitudeOffset`, `gaussianLoader`. Runs after construction, before the layer joins
     *  the engine. Codecs install once at each platform's entry point, not per tutorial. */
    protected open fun configureLayer(layer: Ogc3dTilesLayer) {}
}
