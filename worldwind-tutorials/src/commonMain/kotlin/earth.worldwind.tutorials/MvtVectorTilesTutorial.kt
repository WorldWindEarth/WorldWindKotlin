package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Location
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.layer.mvt.MvtStyle
import earth.worldwind.layer.mvt.MvtVectorLayer
import earth.worldwind.layer.mvt.OpenTopoMapRules
import earth.worldwind.layer.mvt.UrlTemplateMvtTileSource
import earth.worldwind.util.LevelSet
import earth.worldwind.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Vector-tile demo: fetches OpenMapTiles-schema MVT data from Versatiles' public CDN and
 * styles it with [OpenTopoMapRules], producing a flatter rendition of
 * [OpenTopoMap](https://opentopomap.org/) directly on the globe.
 *
 * Differences from full OpenTopoMap:
 *   - **No elevation contours.** OpenTopoMap's brown contour lines come from SRTM, not OSM,
 *     so they don't appear in OpenMapTiles vector data.
 *   - **No hillshading.** Needs a terrain-relief pass against WorldWind's elevation coverage.
 *   - **No road casings.** Each road class is a single-tone line ([earth.worldwind.shape.Path]
 *     doesn't support two-tone outline/fill the way the OpenTopoMap Mapnik style does).
 *
 * Versatiles is currently free for non-commercial use without an API key — for production,
 * either subscribe to a Versatiles plan, point at a self-hosted OpenMapTiles server, or swap
 * to Maptiler/Mapbox by changing only the [UrlTemplateMvtTileSource]'s template.
 *
 * @param layerLoader full layer-creation override. JVM/Android pass a loader that opens a
 *   `ContentManager.openVectorTileStore`, decorates the network `UrlTemplateMvtTileSource`
 *   with `CachedTileSource(network, store)`, and hands the cached source to `MvtVectorLayer`.
 *   The default loader skips the cache and runs network-only.
 */
class MvtVectorTilesTutorial(
    engine: WorldWind,
    private val scope: CoroutineScope = MainScope(),
    private val layerLoader: suspend () -> MvtVectorLayer = ::defaultNetworkOnlyLoader,
) : AbstractTutorial(engine) {

    private var mvt: MvtVectorLayer? = null
    private var cacheJob: Job? = null

    override fun start() {
        super.start()
        cacheJob = scope.launch {
            try {
                val layer = layerLoader()
                mvt = layer
                engine.layers.addLayer(layer)
                // Force a redraw post-addLayer so doRender fires on the new layer — else it can sit
                // dormant when the camera-driven redraw landed before addLayer (fast cached layerLoader).
                WorldWind.requestRedraw()
                Logger.log(Logger.INFO, "MVT layer loaded")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // attachCache validation on re-open (existing vector-tile pyramid mismatch
                // vs requested LevelSet) surfaces here. Fire-and-forget would hide it and
                // the user would just see an empty layer.
                Logger.log(Logger.ERROR, "MVT layer failed to load", e)
            }
        }

        // Drop the camera on Innsbruck — dense road/landuse/water mix in a single tile, plus
        // the alpine setting was the OpenTopoMap project's original test bed so the palette
        // was tuned against terrain like this.
        engine.cameraFromLookAt(
            LookAt(
                position = Position(47.2692.degrees, 11.4041.degrees, 0.0),
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND,
                range = 25_000.0,
                heading = 0.0.degrees,
                tilt = 0.0.degrees,
                roll = 0.0.degrees,
            ),
        )
    }

    override fun stop() {
        super.stop()
        cacheJob?.cancel()
        cacheJob = null
        mvt?.let {
            engine.layers.removeLayer(it)
            it.close()
        }
        mvt = null
    }

    companion object {
        /** OpenStreetMap's official vector tiles (Shortbread schema, no API key). z0–14. */
        const val URL_TEMPLATE = "https://vector.openstreetmap.org/shortbread_v1/{z}/{x}/{y}.mvt"
        const val USER_AGENT = "WorldWindKotlin-Tutorials"
        const val MIN_ZOOM = 0
        // Versatiles' osm tileset tops out at z14 (z15+ return HTTP 404). The camera still zooms past it —
        // the layer overzooms (renders z14 tiles magnified), so building-level detail + house numbers
        // appear when zoomed in close. For true z15+ vector detail a deeper source would be needed.
        const val MAX_ZOOM = 14
        /** Rule-based OpenTopoMap palette with zoom-interpolated widths and labels. Swap for
         *  [earth.worldwind.layer.mvt.OpenTopoMapMvtStyle] for the imperative-DSL reference
         *  impl without zoom interpolation. */
        val STYLE: MvtStyle = OpenTopoMapRules

        /** Default loader: network-only [MvtVectorLayer] constructor (no cache wired). */
        @Suppress("RedundantSuspendModifier")
        suspend fun defaultNetworkOnlyLoader(): MvtVectorLayer = MvtVectorLayer(
            source = UrlTemplateMvtTileSource(
                urlTemplate = URL_TEMPLATE,
                headers = mapOf("User-Agent" to USER_AGENT),
            ),
            minZoom = MIN_ZOOM,
            maxZoom = MAX_ZOOM,
            style = STYLE,
        )

        /** Build the MVT level set (Web-Mercator, 256×256, 0..[MAX_ZOOM]). Used by cache
         *  wiring to open a vector-tile store with a matching tile pyramid. */
        fun buildMvtLevelSet(): LevelSet {
            val sector = MercatorSector()
            val tileOrigin = MercatorSector()
            return LevelSet(
                sector = sector,
                tileOrigin = tileOrigin,
                firstLevelDelta = Location(tileOrigin.deltaLatitude, tileOrigin.deltaLongitude),
                numLevels = MAX_ZOOM + 1,
                tileWidth = 256,
                tileHeight = 256,
                levelOffset = MIN_ZOOM,
            )
        }
    }
}
