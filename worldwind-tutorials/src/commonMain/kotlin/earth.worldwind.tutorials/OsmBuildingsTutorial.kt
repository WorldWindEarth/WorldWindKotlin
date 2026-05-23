package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.layer.buildings.OsmBuildingsLayer
import earth.worldwind.layer.buildings.OverpassBuildingsSource

/**
 * Schematic 3D buildings from OpenStreetMap. Demonstrates [OsmBuildingsLayer] hitting the
 * public Overpass API to fetch footprints + height tags around the camera, extruded locally
 * into gray boxes — a fully-open equivalent of Cesium OSM Buildings.
 *
 * Drops the camera onto midtown Manhattan because it's densely and well-tagged. The first
 * frame is empty: tiles arrive asynchronously and the layer redraws each time a tile completes.
 *
 * Network failures are deliberately silent — Overpass mirrors rate-limit aggressively, and the
 * layer retries on the next render pass. If nothing appears after ~15 seconds, check the
 * console for Overpass errors or point the layer at a self-hosted endpoint.
 *
 * A fresh [OsmBuildingsLayer] is created on every [start] (and closed on [stop]) so re-entering
 * the tutorial after navigating away gets a clean coroutine scope and an empty tile cache.
 */
class OsmBuildingsTutorial(engine: WorldWind) : AbstractTutorial(engine) {

    private var buildings: OsmBuildingsLayer? = null

    override fun start() {
        super.start()
        // overpass-api.de saturates its TCP connection queue frequently (ERR_CONNECTION_REFUSED
        // at the socket layer, not an HTTP 429/403 — so not a ban, just operator capacity).
        // kumi.systems is an independent community mirror with the same API. The library default
        // still points at the canonical endpoint; production users should run their own Overpass
        // instance anyway, per the source's KDoc.
        val layer = OsmBuildingsLayer(
            source = OverpassBuildingsSource(endpoint = "https://overpass.kumi.systems/api/interpreter"),
            // Library default is 2 (conservative for the canonical overpass-api.de instance);
            // bump for the demo so 81 tiles finish in reasonable time. Stays within fair-use
            // guidance for public mirrors (Overpass etiquette suggests <= 6 in flight per IP).
            maxConcurrentFetches = 4,
            useOsmColors = true,
        ).also { buildings = it }
        engine.layers.addLayer(layer)
        engine.cameraFromLookAt(
            LookAt(
                position = Position(40.7484.degrees, (-73.9857).degrees, 0.0),
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND,
                range = 3500.0,
                heading = 0.0.degrees,
                tilt = 65.0.degrees,
                roll = 0.0.degrees,
            )
        )
    }

    override fun stop() {
        super.stop()
        buildings?.let {
            engine.layers.removeLayer(it)
            it.close()
        }
        buildings = null
    }
}
