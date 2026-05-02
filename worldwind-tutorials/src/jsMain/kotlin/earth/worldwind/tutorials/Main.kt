@file:JsExport
@file:Suppress("OPT_IN_USAGE", "NON_EXPORTABLE_TYPE")

package earth.worldwind.tutorials

import earth.worldwind.WorldWindow
import earth.worldwind.geom.Line
import earth.worldwind.gesture.SelectDragCallback
import earth.worldwind.globe.elevation.coverage.BasicElevationCoverage
import earth.worldwind.globe.projection.EquirectangularProjection
import earth.worldwind.globe.projection.GnomonicProjection
import earth.worldwind.globe.projection.MercatorProjection
import earth.worldwind.globe.projection.ModifiedSinusoidalProjection
import earth.worldwind.globe.projection.PolarEquidistantProjection
import earth.worldwind.globe.projection.SinusoidalProjection
import earth.worldwind.globe.projection.TransverseMercatorProjection
import earth.worldwind.globe.projection.UpsProjection
import earth.worldwind.globe.projection.UtmProjection
import earth.worldwind.globe.projection.Wgs84Projection
import earth.worldwind.layer.BackgroundLayer
import earth.worldwind.layer.CompassLayer
import earth.worldwind.layer.CoordinatesDisplayLayer
import earth.worldwind.layer.ViewControlsLayer
import earth.worldwind.layer.WorldMapLayer
import earth.worldwind.layer.atmosphere.AtmosphereLayer
import earth.worldwind.layer.mercator.WebMercatorLayerFactory
import earth.worldwind.layer.shadow.ShadowLayer
import earth.worldwind.layer.starfield.StarFieldLayer
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Movable
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.*
import org.w3c.dom.events.EventListener
import org.w3c.dom.events.MouseEvent

fun main() {
    // Register an event listener to be called when the page is loaded.
    window.onload = {
        // Create a WorldWindow for the canvas.
        val wwd = WorldWindow(document.getElementById("WorldWindow") as HTMLCanvasElement)
        val tutorialSelect = document.getElementById("Tutorials") as HTMLSelectElement
        val projectionSelect = document.getElementById("Projections") as HTMLSelectElement
        val actionsContainer = document.getElementById("Actions") as HTMLDivElement
        val mainScope = MainScope()
        // Single global click listener per picker. `picker.isActive` gates the call so listeners
        // for non-current tutorials short-circuit and don't cross-talk. WorldWindow swallows
        // click events that came from a drag, so no tap-vs-drag check is needed here.
        fun installDepthPicker(picker: PickResultIndicator) {
            wwd.addEventListener("click", EventListener { e ->
                if (!picker.isActive || e !is MouseEvent) return@EventListener
                val clickPoint = wwd.canvasCoordinates(e.clientX, e.clientY)
                picker.showPick(wwd.engine, wwd.pick(clickPoint).topPickedObject?.cartesianPoint)
                wwd.requestRedraw()
            })
        }
        // Ray-pick (CPU multi-hit) variant for Mesh / Collada tutorials.
        fun installRayPicker(isActive: () -> Boolean, onPick: (Line) -> Unit) {
            wwd.addEventListener("click", EventListener { e ->
                if (!isActive() || e !is MouseEvent) return@EventListener
                val clickPoint = wwd.canvasCoordinates(e.clientX, e.clientY)
                val clickRay = Line()
                if (wwd.engine.rayThroughScreenPoint(clickPoint.x, clickPoint.y, clickRay)) {
                    onPick(clickRay)
                    wwd.requestRedraw()
                }
            })
        }

        val tutorials = mapOf(
            "Basic globe" to BasicTutorial(wwd.engine),
            "Set camera view" to CameraViewTutorial(wwd.engine),
            "Set \"look at\" view" to LookAtViewTutorial(wwd.engine),
            "Placemarks" to PlacemarksTutorial(wwd.engine),
            "Paths" to PathsTutorial(wwd.engine).also { installDepthPicker(it.picker) },
            "Polygons" to PolygonsTutorial(wwd.engine).also { installDepthPicker(it.picker) },
            "Ellipses" to EllipsesTutorial(wwd.engine).also { installDepthPicker(it.picker) },
            "Ellipsoids" to EllipsoidsTutorial(wwd.engine),
            "Geographic meshes" to GeographicMeshesTutorial(wwd.engine).also {
                installRayPicker({ it.isStarted }) { ray -> it.pickMesh(ray, wwd.engine.globe) }
            },
            "Triangle meshes" to TriangleMeshesTutorial(wwd.engine).also {
                installRayPicker({ it.isStarted }) { ray -> it.pickMesh(ray, wwd.engine.globe) }
            },
            "COLLADA" to ColladaTutorial(wwd.engine).also { tutorial ->
                mainScope.launch {
                    tutorial.setupScene()
                    wwd.requestRedraw()
                }
                installRayPicker({ tutorial.isStarted }) { ray -> tutorial.pickScene(ray, wwd.engine.globe) }
            },
            "GLTF" to GltfTutorial(wwd.engine).also { tutorial ->
                installDepthPicker(tutorial.picker)
                mainScope.launch {
                    tutorial.setupScene()
                    wwd.requestRedraw()
                }
            },
            "Dash and fill" to ShapesDashAndFillTutorial(wwd.engine),
            "Labels" to LabelsTutorial(wwd.engine),
            "Real-time sightline" to SightlineTutorial(wwd.engine),
            "Viewshed sightline" to ViewshedSightlineTutorial(wwd.engine),
            "Surface image" to SurfaceImageTutorial(wwd.engine),
            "Photo on terrain" to PhotoOnTerrainTutorial(wwd.engine),
            "Video on terrain" to HtmlVideoOnTerrainTutorial(wwd.engine),
            "MilStd2525 graphics" to MilStd2525Tutorial(wwd.engine),
            "Show tessellation" to ShowTessellationTutorial(wwd.engine),
            "MGRS Graticule" to MGRSGraticuleTutorial(wwd.engine),
            "Gauss-Kruger Graticule" to GKGraticuleTutorial(wwd.engine),
            "WMS Layer" to WmsLayerTutorial(wwd.engine, mainScope),
            "WMTS Layer" to WmtsLayerTutorial(wwd.engine, mainScope),
            "WCS Elevation" to WcsElevationTutorial(wwd.engine),
            "Elevation Heatmap" to ElevationHeatmapTutorial(wwd.engine),
        )
        val projections = mapOf(
            "WGS84 Projection" to Wgs84Projection(),
            "Mercator Projection" to MercatorProjection(),
            "Equirectangular Projection" to EquirectangularProjection(),
            "Sinusoidal Projection" to SinusoidalProjection(),
            "Modified Sinusoidal Projection" to ModifiedSinusoidalProjection(),
            "Transverse Mercator Projection" to TransverseMercatorProjection(),
            "UTM Projection (Zone 1)" to UtmProjection(),
            "North Polar Equidistant" to PolarEquidistantProjection(isNorth = true),
            "South Polar Equidistant" to PolarEquidistantProjection(isNorth = false),
            "North UPS" to UpsProjection(isNorth = true),
            "South UPS" to UpsProjection(isNorth = false),
            "North Gnomonic" to GnomonicProjection(isNorth = true),
            "South Gnomonic" to GnomonicProjection(isNorth = false),
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
            // Atmosphere `time` is null by default: no day/night terminator. BasicTutorial
            // sets it (and animates) on start; other tutorials use the layer's
            // [lightDirectionProvider] so shadows still get a sun direction.
            addLayer(AtmosphereLayer())
            addLayer(ShadowLayer())
            addLayer(CompassLayer())
            addLayer(CoordinatesDisplayLayer())
            addLayer(WorldMapLayer().apply { mapWidthDp = 300.0 })
            addLayer(ViewControlsLayer())
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