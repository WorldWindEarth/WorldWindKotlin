@file:JsExport
@file:Suppress("OPT_IN_USAGE", "NON_EXPORTABLE_TYPE")

package earth.worldwind.tutorials

import earth.worldwind.WorldWindow
import earth.worldwind.gesture.SelectDragCallback
import earth.worldwind.globe.elevation.coverage.BasicElevationCoverage
import earth.worldwind.globe.projection.MercatorProjection
import earth.worldwind.globe.projection.Wgs84Projection
import earth.worldwind.layer.BackgroundLayer
import earth.worldwind.layer.atmosphere.AtmosphereLayer
import earth.worldwind.layer.mercator.WebMercatorLayerFactory
import earth.worldwind.layer.starfield.StarFieldLayer
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Movable
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*

fun main() {
    // Register an event listener to be called when the page is loaded.
    window.onload = {
        // Create a WorldWindow for the canvas.
        val wwd = WorldWindow(document.getElementById("WorldWindow") as HTMLCanvasElement)
        val tutorialSelect = document.getElementById("Tutorials") as HTMLSelectElement
        val projectionSelect = document.getElementById("Projections") as HTMLSelectElement
        val actionsContainer = document.getElementById("Actions") as HTMLDivElement
        val tutorials = mapOf(
            "Basic globe" to BasicTutorial(wwd.engine),
            "Set camera view" to CameraViewTutorial(wwd.engine),
            "Set \"look at\" view" to LookAtViewTutorial(wwd.engine),
            "Placemarks" to PlacemarksTutorial(wwd.engine),
            "Paths" to PathsTutorial(wwd.engine),
            "Polygons" to PolygonsTutorial(wwd.engine),
            "Ellipses" to EllipsesTutorial(wwd.engine),
            "Dash and fill" to ShapesDashAndFillTutorial(wwd.engine),
            "Labels" to LabelsTutorial(wwd.engine),
            "Sight line" to SightlineTutorial(wwd.engine),
            "Surface image" to SurfaceImageTutorial(wwd.engine),
            "MilStd2525 graphics" to MilStd2525Tutorial(wwd.engine),
            "Show tessellation" to ShowTessellationTutorial(wwd.engine),
            "MGRS Graticule" to MGRSGraticuleTutorial(wwd.engine),
            "Gauss-Kruger Graticule" to GKGraticuleTutorial(wwd.engine),
            "WMS Layer" to WmsLayerTutorial(wwd.engine, wwd.mainScope),
            "WMTS Layer" to WmtsLayerTutorial(wwd.engine, wwd.mainScope),
            "WCS Elevation" to WcsElevationTutorial(wwd.engine),
            "Elevation Heatmap" to ElevationHeatmapTutorial(wwd.engine),
        )
        val projections = mapOf(
            "WGS84 Projection" to Wgs84Projection(),
            "Mercator Projection" to MercatorProjection()
        )
        var currentTutorial: String? = null

        // Add some image layers to the WorldWindow's globe.
        wwd.engine.layers.apply {
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

        // Add elevation coverage source
        wwd.engine.globe.elevationModel.addCoverage(BasicElevationCoverage())

        // Allow pick and move any movable object
        wwd.selectDragDetector.callback = object : SelectDragCallback {
            override fun canPickRenderable(renderable: Renderable) = renderable is Movable
            override fun canMoveRenderable(renderable: Renderable) = renderable is Movable
        }

        fun callAction(actionName: String) {
            currentTutorial?.let { tutorials[it]?.runAction(actionName) }
        }

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

        fun selectProjection(projectionName: String) {
            wwd.engine.globe.projection = projections[projectionName]!!
            wwd.requestRedraw()
        }

        tutorials.keys.forEach {
            (document.createElement("option") as HTMLOptionElement).apply {
                value = it
                innerHTML = it
                tutorialSelect.append(this)
            }
        }
        projections.keys.forEach {
            (document.createElement("option") as HTMLOptionElement).apply {
                value = it
                innerHTML = it
                projectionSelect.append(this)
            }
        }
        tutorialSelect.onchange = { event -> selectTutorial((event.target as HTMLSelectElement).value) }
        projectionSelect.onchange = { event -> selectProjection((event.target as HTMLSelectElement).value) }
        selectTutorial(tutorials.keys.first())
        selectProjection(projections.keys.first())
    }
}