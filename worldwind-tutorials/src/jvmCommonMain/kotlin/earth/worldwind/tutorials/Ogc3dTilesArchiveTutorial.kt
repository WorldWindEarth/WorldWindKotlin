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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Renders a **Cesium 3D Tiles Archive** in place — both the `.3tz` (ZIP) and `.3dtiles` (SQLite)
 * container forms, auto-detected by magic bytes. [archivePathProvider] stages the bundled dragon
 * moko-resource to a filesystem path through platform-specific channels (classpath stream + temp
 * file on JVM, `assets.open` + cache dir on Android). Tile bytes are read straight from the
 * archive at render time — nothing is extracted.
 *
 * The extent isn't known until `tileset.json` parses, so a coroutine polls
 * [Ogc3dTilesLayer.sector] and frames the camera once available; "Fly to archive" reframes on demand.
 */
class Ogc3dTilesArchiveTutorial(
    engine: WorldWind,
    private val scope: CoroutineScope,
    private val archivePathProvider: suspend () -> String?,
) : AbstractTutorial(engine) {
    private var layer: Ogc3dTilesLayer? = null
    private var job: Job? = null

    override val actions = arrayListOf(ACTION_FLY_TO)

    override fun start() {
        super.start()
        job = scope.launch {
            try {
                val file = archivePathProvider()?.let(::File)
                if (file == null || !file.isFile) {
                    logMessage(
                        Logger.WARN, "Ogc3dTilesArchiveTutorial", "start",
                        "3D Tiles archive not available",
                    )
                    return@launch
                }
                // Magic sniff + container open touch the file - keep them off the main thread.
                val newLayer = withContext(Dispatchers.IO) {
                    openArchived3dTilesLayer(file, displayName = file.name)
                }
                if (!isActive) { newLayer.shutdown(); return@launch }
                engine.layers.addLayer(newLayer)
                layer = newLayer
                WorldWind.requestRedraw()
                // Poll for the parsed extent; give up after ~20 s.
                repeat(66) {
                    delay(300)
                    if (frameToDataset()) return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.log(Logger.ERROR, "3D Tiles archive open failed", e)
            }
        }
    }

    override fun stop() {
        super.stop()
        job?.cancel()
        job = null
        layer?.let {
            engine.layers.removeLayer(it)
            it.shutdown() // closes the archive handle via the byte source
        }
        layer = null
    }

    override fun runAction(actionName: String) {
        if (actionName == ACTION_FLY_TO) frameToDataset()
    }

    /** Frame the camera on the dataset's geographic extent. Returns false until the sector parses. */
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
