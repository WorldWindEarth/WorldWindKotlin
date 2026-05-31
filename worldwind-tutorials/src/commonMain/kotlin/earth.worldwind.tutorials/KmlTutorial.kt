package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.formats.kml.KmlLayerFactory
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.layer.Layer
import earth.worldwind.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Loads the canonical Google KML Samples document via [KmlLayerFactory]. Demonstrates the
 * KML feature set the factory supports — `<Style>` blocks (icon / line / poly), inline
 * styles, `<Placemark>` / `<LineString>` / `<Polygon>` / `<NetworkLink>`-free Folders,
 * altitude modes, extrusions, etc.
 *
 * The camera is positioned over Mountain View, CA (the original document's sample area).
 * Each platform reads `MR.assets.kml_sample_kml` with its own moko-resources hook
 * (classpath on JVM, AssetManager on Android, NSBundle on iOS, `fetch` on JS) and supplies
 * the text via [kmlTextProvider].
 */
class KmlTutorial(
    engine: WorldWind,
    private val scope: CoroutineScope,
    private val density: Float = 1f,
    private val kmlTextProvider: suspend () -> String,
) : AbstractTutorial(engine) {

    private val installedLayers = mutableListOf<Layer>()
    private var job: Job? = null

    override fun start() {
        super.start()
        job = scope.launch {
            try {
                val text = kmlTextProvider()
                val layers = KmlLayerFactory.createLayers(
                    text = text,
                    options = KmlLayerFactory.Options(
                        density = density,
                        renderLabelsForShapes = true,
                    ),
                )
                if (!isActive) return@launch
                installedLayers.addAll(layers)
                engine.layers.addAllLayers(layers)
                WorldWind.requestRedraw()
            } catch (e: Throwable) {
                Logger.log(Logger.ERROR, "KML tutorial: ${e.message}", e)
            }
        }
        engine.cameraFromLookAt(
            LookAt(
                position = Position.fromDegrees(37.42228990140251, -122.0822035425683, 0.0),
                altitudeMode = AltitudeMode.ABSOLUTE,
                range = 2e2,
                heading = ZERO,
                tilt = 45.0.degrees,
                roll = ZERO,
            )
        )
    }

    override fun stop() {
        super.stop()
        job?.cancel()
        installedLayers.forEach { engine.layers.removeLayer(it) }
        installedLayers.clear()
    }
}
