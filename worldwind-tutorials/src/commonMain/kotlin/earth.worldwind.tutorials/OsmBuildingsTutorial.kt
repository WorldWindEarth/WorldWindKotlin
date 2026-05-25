package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.layer.buildings.OsmBuildingsLayer
import earth.worldwind.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
 *
 * [layerLoader] lets JVM/Android/JS/iOS inject an OsmBuildingsLayer over a cache-wrapped source
 * (CachedTiledFeatureSource + GpkgFeatureStore). The loader runs inside a coroutine and
 * `attachCache` completes before the layer is added to the engine — this avoids the
 * "first batch of tiles bypasses cache" race the synchronous factory had.
 */
class OsmBuildingsTutorial(
    engine: WorldWind,
    private val scope: CoroutineScope,
    private val layerLoader: suspend () -> OsmBuildingsLayer = ::defaultNetworkOnlyLoader,
) : AbstractTutorial(engine) {

    private var buildings: OsmBuildingsLayer? = null
    private var job: Job? = null

    override fun start() {
        super.start()
        job = scope.launch {
            try {
                val layer = layerLoader()
                if (isActive) {
                    buildings = layer
                    engine.layers.addLayer(layer)
                    WorldWind.requestRedraw()
                }
            } catch (e: Throwable) {
                Logger.log(Logger.ERROR, "OSM Buildings layer creation failed", e)
            }
        }
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
        job?.cancel()
        job = null
        buildings?.let {
            engine.layers.removeLayer(it)
            it.close()
        }
        buildings = null
    }

    companion object {
        /** Default loader: network-only [OsmBuildingsLayer] (no cache wired). */
        @Suppress("RedundantSuspendModifier")
        suspend fun defaultNetworkOnlyLoader(): OsmBuildingsLayer =
            OsmBuildingsLayer(useOsmColors = true)
    }
}
