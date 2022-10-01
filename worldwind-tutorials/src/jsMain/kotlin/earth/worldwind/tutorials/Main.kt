@file:JsExport

package earth.worldwind.tutorials

import earth.worldwind.WorldWindow
import earth.worldwind.globe.elevation.coverage.BasicElevationCoverage
import earth.worldwind.layer.BackgroundLayer
import earth.worldwind.layer.atmosphere.AtmosphereLayer
import earth.worldwind.layer.mercator.google.GoogleLayer
import earth.worldwind.layer.starfield.StarFieldLayer
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*

fun main() {
    // Register an event listener to be called when the page is loaded.
    window.onload = {
        // Create a WorldWindow for the canvas.
        val wwd = WorldWindow(document.getElementById("WorldWindow") as HTMLCanvasElement)
        val select = document.getElementById("Tutorials") as HTMLSelectElement
        val actionsContainer = document.getElementById("Actions") as HTMLDivElement
        val tutorials = mapOf (
            "Basic globe" to BasicTutorial(),
            "Set camera view" to CameraViewTutorial(wwd.engine ),
            "Set Look At" to LookAtViewTutorial(wwd.engine),
            "Placemarks" to PlacemarksTutorial(wwd.engine),
            "Paths" to PathsTutorial(wwd.engine),
            "Polygons" to PolygonsTutorial(wwd.engine),
            "Ellipses" to EllipsesTutorial(wwd.engine),
            // TODO Uncomment when ImageSource.fromLineStipple will be implemented
            //"Dash and fill" to ShapeDashAndFillTutorial(wwd.engine),
            "Labels" to LabelsTutorial(wwd.engine),
            "Sight line" to SightlineTutorial(wwd.engine),
            "Surface image" to SurfaceImageTutorial(wwd.engine),
            "Show tessellation" to ShowTessellationTutorial(wwd.engine),
            "MGRS Graticule" to MGRSGraticuleTutorial(wwd.engine),
            // TODO Uncomment when TIFF elevation data parsing will be implemented
            //"WCS Elevation" to WcsElevationTutorial(wwd.engine),
            "WMS Layer" to WmsLayerTutorial(wwd.engine, wwd.mainScope),
            "WMTS Layer" to WmtsLayerTutorial(wwd.engine, wwd.mainScope),
        )
        var currentTutorial: String? = null

        // Add some image layers to the WorldWindow's globe.
        wwd.engine.layers.apply {
            addLayer(BackgroundLayer())
            addLayer(GoogleLayer(GoogleLayer.Type.SATELLITE))
            addLayer(StarFieldLayer())
            addLayer(AtmosphereLayer())
        }

        // Add elevation coverage source
        wwd.engine.globe.elevationModel.addCoverage(BasicElevationCoverage())

        fun callAction(actionName: String) { currentTutorial?.let { tutorials[it]?.runAction(actionName) } }

        fun createAction(actionName: String) {
            (document.createElement("button") as HTMLButtonElement).apply {
                innerHTML = actionName
                actionsContainer.append(this)
                onclick = { callAction(actionName) }
            }
        }

        fun selectTutorial(tutorial: String) {
            currentTutorial?.let { tutorials[it]?.stop() }
            currentTutorial = tutorial
            tutorials[tutorial]?.run {
                start()
                //TODO actions
                actionsContainer.innerHTML = ""
                actions?.forEach { action -> createAction(action) }
                actionsContainer.hidden = actions?.isEmpty() != false
            }
            wwd.requestRedraw()
        }

        tutorials.keys.forEach {
            (document.createElement("option") as HTMLOptionElement).apply {
                value = it
                innerHTML = it
                select.append(this)
            }
        }
        select.onchange = { event -> selectTutorial((event.target as HTMLSelectElement).value) }

        selectTutorial(tutorials.keys.first())
    }
}