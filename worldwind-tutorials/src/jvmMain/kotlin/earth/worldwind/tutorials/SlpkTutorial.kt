package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.layer.ogc3d.SlpkLayer
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.logMessage
import java.io.File
import javax.swing.Timer

/**
 * Exercises the in-place ArcGIS **SLPK** (I3S) loader on JVM. Point it at a local Scene Layer
 * Package via the `worldwind.slpk.path` system property (or edit [DEFAULT_PATH]); nothing is
 * copied or extracted — [SlpkLayer] reads node bytes straight from the archive.
 *
 * The dataset extent isn't known until the scene-layer document + node pages parse, so a Swing
 * [Timer] (EDT-safe) polls the layer's [SlpkLayer.sector] and frames the camera on
 * it once available. The "Fly to SLPK" action reframes on demand.
 */
class SlpkTutorial(engine: WorldWind) : AbstractTutorial(engine) {
    private val slpkPath: String =
        System.getProperty("worldwind.slpk.path")?.takeIf { it.isNotBlank() } ?: DEFAULT_PATH

    private var layer: SlpkLayer? = null
    private var flyTimer: Timer? = null

    override val actions = arrayListOf(ACTION_FLY_TO)

    override fun start() {
        super.start()
        val file = File(slpkPath)
        if (!file.isFile) {
            logMessage(
                Logger.WARN, "SlpkTutorial", "start",
                "SLPK not found at '$slpkPath' — set -Dworldwind.slpk.path=/path/to/dataset.slpk",
            )
            return
        }
        val newLayer = SlpkLayer.open(file.absolutePath, displayName = file.name)
        engine.layers.addLayer(newLayer)
        layer = newLayer
        WorldWind.requestRedraw()

        // Poll for the parsed extent on the EDT; give up after ~20 s (a huge SLPK may still be
        // reading node pages, but the user can use the action button then).
        var ticks = 0
        flyTimer = Timer(300) {
            ticks++
            if (frameToDataset() || ticks > 66) flyTimer?.stop()
        }.apply { isRepeats = true; start() }
    }

    override fun stop() {
        super.stop()
        flyTimer?.stop()
        flyTimer = null
        layer?.let {
            engine.layers.removeLayer(it)
            it.shutdown() // closes the SLPK archive handle via the byte source
        }
        layer = null
    }

    override fun runAction(actionName: String) {
        if (actionName == ACTION_FLY_TO) frameToDataset()
    }

    /** Frame the camera on the dataset's geographic extent. Returns false until [sector] parses. */
    private fun frameToDataset(): Boolean {
        val sector = layer?.sector ?: return false
        val spanDeg = maxOf(
            sector.maxLatitude.inDegrees - sector.minLatitude.inDegrees,
            sector.maxLongitude.inDegrees - sector.minLongitude.inDegrees,
        )
        val range = (spanDeg * 111_320.0 * 1.2).coerceIn(1_500.0, 2_000_000.0)
        engine.cameraFromLookAt(
            LookAt(
                position = Position(sector.centroidLatitude, sector.centroidLongitude, 0.0),
                altitudeMode = AltitudeMode.ABSOLUTE,
                range = range,
                heading = 0.0.degrees,
                tilt = 55.0.degrees,
                roll = 0.0.degrees,
            )
        )
        WorldWind.requestRedraw()
        return true
    }

    companion object {
        private const val ACTION_FLY_TO = "Fly to SLPK"
        /** Default lookup path; override with `-Dworldwind.slpk.path=…`. */
        private val DEFAULT_PATH = File(System.getProperty("user.home"), "slpk-sample.slpk").absolutePath
    }
}
