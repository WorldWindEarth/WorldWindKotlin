package earth.worldwind.tutorials

import earth.worldwind.BasicWorldWindowController
import earth.worldwind.WorldWind
import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.globe.elevation.coverage.BasicElevationCoverage
import earth.worldwind.layer.BackgroundLayer
import earth.worldwind.layer.atmosphere.AtmosphereLayer
import earth.worldwind.layer.mercator.WebMercatorLayerFactory
import earth.worldwind.layer.ogc3d.SlpkLayer
import earth.worldwind.layer.starfield.StarFieldLayer
import java.awt.BorderLayout
import java.io.File
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants

/**
 * Minimal standalone launcher for the in-place SLPK (I3S) loader — a JavaFX-free alternative to the
 * full tutorials app (whose video tutorials pin a JavaFX build that needs a newer JDK). Opens one
 * SLPK, drops it on a basic globe, and frames the camera on it once the tree parses.
 *
 * Path from `-Dworldwind.slpk.path=…` or the first program argument.
 */
fun main(args: Array<String>) {
    val path = args.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: System.getProperty("worldwind.slpk.path")?.takeIf { it.isNotBlank() }
        ?: error("Set -Dworldwind.slpk.path=/path/to/dataset.slpk (or pass it as the first argument)")
    require(File(path).isFile) { "SLPK not found: $path" }
    installPermissiveSslForTutorials()

    SwingUtilities.invokeLater {
        val window = WorldWindow { engine ->
            engine.layers.addLayer(BackgroundLayer())
            engine.layers.addLayer(
                WebMercatorLayerFactory.createLayer(
                    urlTemplate = "https://mt.google.com/vt/lyrs=s&x={x}&y={y}&z={z}&hl={lang}",
                    imageFormat = "image/jpeg",
                    name = "Google Satellite",
                )
            )
            engine.layers.addLayer(StarFieldLayer())
            engine.layers.addLayer(AtmosphereLayer().apply {
                // Fixed sun angle relative to the camera; without it rc.lightDirection stays at its
                // (0,0,1) default and the Lambertian shader renders the mesh dark.
                lightDirectionProvider = { rc ->
                    computeSceneLightDirection(rc.camera.position, 290.0, 50.0, rc.lightDirection)
                }
            })
            engine.globe.elevationModel.addCoverage(BasicElevationCoverage())

            val layer = SlpkLayer.open(path, File(path).name)
            // Optional vertical nudge (metres), e.g. to compensate the EGM96 geoid datum:
            //   -Dworldwind.slpk.altitudeOffset=30
            System.getProperty("worldwind.slpk.altitudeOffset")?.toDoubleOrNull()?.let { layer.altitudeOffset = it }
            engine.layers.addLayer(layer)

            // Frame the camera on the dataset once its extent parses (EDT-safe Swing Timer).
            var ticks = 0
            var flyTimer: Timer? = null
            flyTimer = Timer(300) {
                ticks++
                val sector = layer.sector
                if (sector != null) {
                    val spanDeg = maxOf(
                        sector.maxLatitude.inDegrees - sector.minLatitude.inDegrees,
                        sector.maxLongitude.inDegrees - sector.minLongitude.inDegrees,
                    )
                    val range = (spanDeg * 111_320.0 * 1.4).coerceIn(1_500.0, 2_000_000.0)
                    engine.cameraFromLookAt(
                        LookAt(
                            position = Position(sector.centroidLatitude, sector.centroidLongitude, 0.0),
                            altitudeMode = AltitudeMode.ABSOLUTE,
                            range = range, heading = 0.0.degrees, tilt = 55.0.degrees, roll = 0.0.degrees,
                        )
                    )
                    WorldWind.requestRedraw()
                    flyTimer?.stop()
                } else if (ticks > 100) flyTimer?.stop()
            }.apply { isRepeats = true; start() }
        }
        window.controller = BasicWorldWindowController(window)

        JFrame("WorldWind Kotlin — SLPK: ${File(path).name}").apply {
            defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
            layout = BorderLayout()
            add(window, BorderLayout.CENTER)
            setSize(1280, 800)
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
}
