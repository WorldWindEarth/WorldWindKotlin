package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.layer.ogc3d.Ogc3dTilesLayer
import earth.worldwind.layer.ogc3d.openArchived3dTilesLayer
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.logMessage
import java.io.File
import javax.swing.Timer

/**
 * Renders a **Cesium 3D Tiles Archive** in place — both the `.3tz` (ZIP) and `.3dtiles` (SQLite)
 * container forms, auto-detected by magic bytes. [archivePathProvider] resolves the archive's
 * filesystem path: the bundled tutorials stage a moko-resource via [SharedStagedArchive], the default
 * reads `-Dworldwind.3dtiles.path`. Tile bytes are read straight from the archive at render time.
 *
 * The extent isn't known until `tileset.json` parses, so a Swing [Timer] (EDT-safe) polls
 * [Ogc3dTilesLayer.sector] and frames the camera once available; "Fly to archive" reframes on demand.
 */
class Ogc3dTilesArchiveTutorial(
    engine: WorldWind,
    private val archivePathProvider: () -> String? = {
        System.getProperty("worldwind.3dtiles.path")?.takeIf { it.isNotBlank() }
    },
) : AbstractTutorial(engine) {
    private var layer: Ogc3dTilesLayer? = null
    private var flyTimer: Timer? = null

    override val actions = arrayListOf(ACTION_FLY_TO)

    override fun start() {
        super.start()
        val file = archivePathProvider()?.let(::File)
        if (file == null || !file.isFile) {
            logMessage(
                Logger.WARN, "Ogc3dTilesArchiveTutorial", "start",
                "3D Tiles archive not available — set -Dworldwind.3dtiles.path=/path/to/dataset.3tz",
            )
            return
        }
        val newLayer = openArchived3dTilesLayer(file, displayName = file.name)
        engine.layers.addLayer(newLayer)
        layer = newLayer
        WorldWind.requestRedraw()

        // Poll for the parsed extent on the EDT; give up after ~20 s.
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
            it.shutdown() // closes the archive handle via the byte source
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
        private const val ACTION_FLY_TO = "Fly to archive"
    }
}
