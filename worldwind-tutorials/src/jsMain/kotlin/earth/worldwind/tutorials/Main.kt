package earth.worldwind.tutorials

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import earth.worldwind.WorldWindow
import earth.worldwind.globe.elevation.coverage.BasicElevationCoverage
import earth.worldwind.layer.BackgroundLayer
import earth.worldwind.layer.atmosphere.AtmosphereLayer
import earth.worldwind.layer.mercator.google.GoogleLayer
import earth.worldwind.layer.starfield.StarFieldLayer

fun main() {
    // Register an event listener to be called when the page is loaded.
    window.onload = {
        // Create a WorldWindow for the canvas.
        val canvas = document.getElementById("WorldWindow") as HTMLCanvasElement
        val wwd = WorldWindow(canvas)

        // Add some image layers to the WorldWindow's globe.
        wwd.engine.layers.apply {
            addLayer(BackgroundLayer())
            addLayer(GoogleLayer(GoogleLayer.Type.SATELLITE))
            addLayer(StarFieldLayer())
            addLayer(AtmosphereLayer())
        }

        // Add elevation coverage source
        wwd.engine.globe.elevationModel.addCoverage(BasicElevationCoverage())

        // Request redraw WorldWindow to display new layers
        wwd.requestRedraw()
    }
}
