package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.Angle
import earth.worldwind.layer.BulkFeatureLayer
import earth.worldwind.layer.Layer
import earth.worldwind.layer.TiledFeatureLayer
import earth.worldwind.ogc.wfs.WfsLayer
import earth.worldwind.render.Color
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Path
import earth.worldwind.shape.Polygon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Demonstrates viewport-driven WFS loading over a large layer (`opengeo:countries`) that would
 * OOM if fetched whole. Two strategies share this tutorial:
 *   * Default ([layerProvider] null): a network [WfsLayer] with `autoRefresh` — re-fetches the
 *     visible BBOX from the server each time the viewport moves. commonMain, every platform.
 *   * Cached ([layerProvider] supplied by JVM/Android): a [BulkFeatureLayer] over a GeoPackage-
 *     backed source with `autoRefreshViewport` — Strategy 1. The store is downloaded once, then
 *     every pan/zoom serves a BBOX query from its spatial index (bounded RAM, offline after the
 *     first download). The GeoPackage feature cache only exists on JVM/Android, hence the provider.
 *
 * @param layerProvider builds the layer to display. Defaults to the network [WfsLayer] path.
 */
class WfsAutoRefreshTutorial(
    engine: WorldWind,
    private val scope: CoroutineScope,
    private val layerProvider: (suspend () -> Layer)? = null,
    // Default: North America continental view (good for the global countries demos). The tiled
    // water demo overrides this to zoom onto the St. Lawrence River, where the data is dense.
    private val cameraLatitude: Double = 45.0,
    private val cameraLongitude: Double = -85.0,
    private val cameraAltitude: Double = 4_000_000.0,
) : AbstractTutorial(engine) {

    private var layer: Layer? = null
    private var job: Job? = null

    override fun start() {
        super.start()
        engine.camera.apply {
            position.setDegrees(cameraLatitude, cameraLongitude, cameraAltitude)
            heading = Angle.ZERO
            tilt = Angle.ZERO
            roll = Angle.ZERO
        }
        job = scope.launch {
            // Cached path's attachCache is suspend, so build the layer in a coroutine. No explicit
            // load(): both paths fetch the first viewport from their own doRender once a frame runs.
            val built = layerProvider?.invoke() ?: defaultNetworkLayer()
            if (!isActive) return@launch
            layer = built
            engine.layers.addLayer(built)
            WorldWind.requestRedraw()
        }
    }

    override fun stop() {
        super.stop()
        job?.cancel()
        layer?.let {
            engine.layers.removeLayer(it)
            when (it) {
                is WfsLayer -> it.cancel()
                is BulkFeatureLayer -> it.close()
                is TiledFeatureLayer -> it.close()
            }
        }
        layer = null
    }

    private fun defaultNetworkLayer() = WfsLayer(
        serviceAddress = SERVICE_ADDRESS,
        typeName = TYPE_NAME,
        displayName = DISPLAY_NAME,
        pageSize = PAGE_SIZE,
        customLogicToApplyProperties = countryStyling,
    ).apply {
        isPickEnabled = false
        autoRefresh = true
        autoRefreshMaxFeatures = MAX_FEATURES_PER_FETCH // memory guard
    }

    companion object {
        const val SERVICE_ADDRESS = "https://ahocevar.com/geoserver/wfs"
        const val TYPE_NAME = "opengeo:countries"
        const val DISPLAY_NAME = "Countries (WFS, viewport-loaded)"
        /** Small pages so a single fetch streams in promptly; pagination accumulates them. */
        const val PAGE_SIZE = 50
        /** Upper bound on features per viewport fetch — the memory guard for the network path. */
        const val MAX_FEATURES_PER_FETCH = 1000

        private val countryFill = Color.fromHexString("#2A9D8F").apply { alpha = 0.25f }
        private val countryOutline = Color.fromHexString("#E9F5F3")

        /** Translucent fill + light outline so overlapping country polygons stay readable. */
        val countryStyling: Renderable.(LinkedHashMap<String, Any?>) -> Unit = {
            when (this) {
                is Polygon -> attributes.apply {
                    isDrawInterior = true
                    interiorColor.copy(countryFill)
                    outlineColor.copy(countryOutline)
                    outlineWidth = 1f
                }
                is Path -> attributes.apply {
                    outlineColor.copy(countryOutline)
                    outlineWidth = 1f
                }
            }
        }

        // Strategy-2 (tiled cache-through) demo dataset: a dense GLOBAL line layer that must NOT be
        // fetched whole — each viewport fetches and caches only its tiles. Natural Earth 10m roads
        // (~57k features worldwide) exercises the batched line/outline path with many features per tile.
        const val ROADS_TYPE_NAME = "ne:ne_10m_roads"
        const val ROADS_DISPLAY_NAME = "Roads (WFS, tiled cache)"
        /** Per-tile fetch cap (server COUNT). Bounds a dense tile's feature count — and so its line VBO
         *  — to keep GPU memory in check (a level-4 tile over a dense region would otherwise hold a
         *  continental road network in one ~MB buffer). 3000 still draws a rich network per tile. */
        val ROADS_MAX_FEATURES_PER_TILE: Int? = 3000
        /** RDP simplify tolerance in tile texels (≈ screen px at the tile's level). Higher than the 1.0
         *  default to shrink each tile's line VBO — the GPU-memory driver — by dropping sub-pixel road
         *  detail that isn't visible at the rendered zoom. */
        const val ROADS_SIMPLIFY_PX = 3.0
        /** Quadtree band floor for [TiledFeatureLayer]: levels coarser than this aren't fetched. Level 4
         *  ≈ 11° tiles — keeps per-tile geometry small so reads/assembly stay cheap (a coarser floor like
         *  level 2 holds continental road networks per tile and stalls the workers). The layer blanks if
         *  the camera tilts far enough that the viewed ground recedes below this floor. */
        const val ROADS_LEVEL_OFFSET = 4

        /** Camera for the tiled demo: central Europe (dense `ne:ne_10m_roads`). Altitude sits inside the
         *  level-4 band so tiles load immediately; the layer is global, so panning anywhere loads roads. */
        const val ROADS_CAMERA_LAT = 48.5
        const val ROADS_CAMERA_LON = 9.5
        const val ROADS_CAMERA_ALT = 250_000.0

        private val roadMajor = Color.fromHexString("#E8743B") // major highways / freeways
        private val roadMinor = Color.fromHexString("#D7DBE0") // everything else

        /** Outline-only styling for road lines; major highways drawn brighter/thicker than minor roads. */
        val roadsStyling: Renderable.(LinkedHashMap<String, Any?>) -> Unit = { props ->
            if (this is Path) attributes.apply {
                val type = props["type"] as? String
                val major = type == "Major Highway" || type == "Freeway"
                outlineColor.copy(if (major) roadMajor else roadMinor)
                outlineWidth = if (major) 2f else 1f
            }
        }
    }
}
