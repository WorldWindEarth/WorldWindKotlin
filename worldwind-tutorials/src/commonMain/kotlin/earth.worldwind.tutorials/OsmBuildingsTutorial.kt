package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.layer.buildings.OsmBuildingsLayer

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
        val layer = OsmBuildingsLayer(useOsmColors = true).also { buildings = it }
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
