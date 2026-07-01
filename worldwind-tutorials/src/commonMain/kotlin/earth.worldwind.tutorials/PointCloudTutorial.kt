package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.formats.las.LasDecoderRegistry
import earth.worldwind.formats.las.laszip.LaszipDecoder
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.geom.coords.Hemisphere
import earth.worldwind.geom.coords.UTMCoord
import earth.worldwind.layer.pointcloud.PointCloudLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Loads a standalone LAZ LiDAR point cloud with [PointCloudLayer] and renders it via the
 * engine's `GL_POINTS` splat path. The sample is the public **Autzen Stadium** scan (Eugene,
 * Oregon) — ~694k RGB points, compressed `.laz`, served with permissive CORS so the same code
 * runs on every shell including web.
 *
 * The file is genuinely georeferenced (EPSG:26910, NAD83 UTM 10N), which the loader auto-detects
 * — so the cloud lands on its **real location and real elevation** with no projection hook. It
 * rests on the globe's terrain and overlays the matching satellite imagery; that overlay is the
 * correctness check. (The scan's absolute heights only meet the ground when a terrain model is
 * loaded, so [start] makes sure elevation is enabled.)
 *
 * `.laz` decoding needs a `LazChunkDecoder`; [start] installs [LaszipDecoder] once (idempotent).
 */
class PointCloudTutorial(engine: WorldWind) : AbstractTutorial(engine) {
    private val layer = PointCloudLayer("LAS/LAZ Point Cloud").apply { pointSize = 2.5f }

    private var scope = newScope()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun start() {
        super.start()
        if (LasDecoderRegistry.lazDecoder == null) LasDecoderRegistry.lazDecoder = LaszipDecoder()

        // The scan carries real elevations (~130 m), so keep terrain on for it to rest on the ground.
        engine.globe.elevationModel.forEach { it.isEnabled = true }

        engine.layers.addLayer(layer)

        val center = UTMCoord.fromUTM(UTM_ZONE, Hemisphere.N, CENTER_EASTING, CENTER_NORTHING)
        engine.cameraFromLookAt(
            LookAt(
                position = Position(center.latitude, center.longitude, 155.0),
                altitudeMode = AltitudeMode.ABSOLUTE,
                range = 650.0,
                heading = 25.0.degrees,
                tilt = 60.0.degrees,
                roll = 0.0.degrees,
            )
        )

        scope.launch {
            try {
                layer.loadLas(SAMPLE_URL)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Network / parse failure: leave the globe framed on the empty scene.
            }
        }
        WorldWind.requestRedraw()
    }

    override fun stop() {
        super.stop()
        scope.cancel()
        scope = newScope() // a cancelled scope can't relaunch on a later start()
        engine.layers.removeLayer(layer)
    }

    companion object {
        /** Public PDAL sample: Autzen Stadium, ~694k points, format 3 (RGB), LAZ, ~2.7 MB. */
        const val SAMPLE_URL = "https://media.githubusercontent.com/media/PDAL/data/main/autzen/stadium-utm.laz"

        // EPSG:26910 (NAD83 / UTM 10N). Centroid from the file header, used to frame the camera.
        private const val UTM_ZONE = 10
        private const val CENTER_EASTING = 494518.645  // (minX + maxX) / 2
        private const val CENTER_NORTHING = 4878354.775 // (minY + maxY) / 2
    }
}
