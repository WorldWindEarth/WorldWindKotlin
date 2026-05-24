package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.layer.mvt.MvtVectorLayer
import earth.worldwind.layer.mvt.OpenTopoMapRules
import earth.worldwind.layer.mvt.UrlTemplateMvtTileSource

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
 */
class MvtVectorTilesTutorial(engine: WorldWind) : AbstractTutorial(engine) {

    private var mvt: MvtVectorLayer? = null

    override fun start() {
        super.start()
        val layer = MvtVectorLayer(
            source = UrlTemplateMvtTileSource(
                // Versatiles' public CDN. OpenMapTiles v3 schema.
                urlTemplate = "https://tiles.versatiles.org/tiles/osm/{z}/{x}/{y}",
                // Versatiles doesn't require it but sending a User-Agent is good citizenship.
                headers = mapOf("User-Agent" to "WorldWindKotlin-Tutorials"),
            ),
            // minZoom/maxZoom bracket what the server serves; Versatiles publishes up to z14.
            // Zoom is auto-selected per render from camera altitude.
            minZoom = 2,
            maxZoom = 14,
            tileRadius = 3,
            // Rule-based OpenTopoMap palette with zoom-interpolated widths and labels. Swap
            // to [OpenTopoMapMvtStyle] for the imperative-DSL reference impl without zoom
            // interpolation.
            style = OpenTopoMapRules,
        ).also { mvt = it }
        engine.layers.addLayer(layer)

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
        mvt?.let {
            engine.layers.removeLayer(it)
            it.close()
        }
        mvt = null
    }
}
