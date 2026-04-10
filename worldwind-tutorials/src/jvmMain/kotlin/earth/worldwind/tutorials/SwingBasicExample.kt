package earth.worldwind.tutorials

import earth.worldwind.WorldWindow
import earth.worldwind.globe.elevation.coverage.BasicElevationCoverage
import earth.worldwind.layer.BlueMarbleLandsatLayer
import earth.worldwind.layer.ShowTessellationLayer
import earth.worldwind.layer.atmosphere.AtmosphereLayer
import earth.worldwind.layer.starfield.StarFieldLayer
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

fun main() {
    SwingUtilities.invokeLater {
        val worldWindow = WorldWindow {
            addElevationCoverage(BasicElevationCoverage())
            addLayer(ShowTessellationLayer().apply { zOrder = -1.0 })
            addLayer(StarFieldLayer())
            addLayer(AtmosphereLayer())
            addLayer(BlueMarbleLandsatLayer())
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