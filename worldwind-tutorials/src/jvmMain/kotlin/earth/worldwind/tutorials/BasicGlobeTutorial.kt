package earth.worldwind.tutorials

import earth.worldwind.WorldWindow
import earth.worldwind.globe.elevation.coverage.BasicElevationCoverage
import earth.worldwind.layer.BackgroundLayer
import earth.worldwind.layer.atmosphere.AtmosphereLayer
import earth.worldwind.layer.mercator.WebMercatorLayerFactory
import earth.worldwind.layer.starfield.StarFieldLayer
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

fun main() {
    SwingUtilities.invokeLater {
        val worldWindow = WorldWindow { engine ->
            engine.globe.elevationModel.addCoverage(BasicElevationCoverage())
            with(engine.layers) {
                addLayer(BackgroundLayer())
                addLayer(
                    WebMercatorLayerFactory.createLayer(
                        urlTemplate = "https://mt.google.com/vt/lyrs=s&x={x}&y={y}&z={z}&hl={lang}",
                        imageFormat = "image/jpeg",
                        name = "Google Satellite"
                    )
                )
                addLayer(StarFieldLayer())
                addLayer(AtmosphereLayer())
            }

            BasicTutorial(engine).start()
        }

        JFrame("WorldWind Kotlin - Globe").apply {
            defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
            contentPane.add(worldWindow)
            setSize(1280, 800)
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
}