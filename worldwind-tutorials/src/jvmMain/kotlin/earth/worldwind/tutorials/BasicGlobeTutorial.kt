package earth.worldwind.tutorials

import earth.worldwind.BasicWorldWindowController
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
import earth.worldwind.layer.starfield.StarFieldLayer
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Movable
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import javax.swing.*

fun main() {
    // Demo apps run on whatever JDK the user has installed; outdated `cacerts` truststores
    // reject NASA / DLR / USGS endpoints with PKIX path-building failures. Trust-all is fine
    // for tutorials but should never ship in production.
    installPermissiveSslForTutorials()
    SwingUtilities.invokeLater {
        val mainScope = MainScope()
        val tutorialCombo = JComboBox<String>()
        val projectionCombo = JComboBox<String>()
        val actionsPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        var clickHandler: ((MouseEvent) -> Unit)? = null
        var wwd: WorldWindow? = null

        wwd = WorldWindow { engine ->
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
                addLayer(CompassLayer())
                addLayer(CoordinatesDisplayLayer())
                addLayer(WorldMapLayer().apply { mapWidthDp = 300.0 })
                addLayer(ViewControlsLayer())
            }

            val colladaTutorial = ColladaTutorial(engine).also { tutorial ->
                mainScope.launch { tutorial.setupScene() }
            }
            val gltfTutorial = GltfTutorial(engine).also { tutorial ->
                mainScope.launch { tutorial.setupScene() }
            }
            val geoMeshTutorial = GeographicMeshesTutorial(engine)
            val triMeshTutorial = TriangleMeshesTutorial(engine)

            val tutorials = linkedMapOf(
                "Basic globe" to BasicTutorial(engine),
                "Set camera view" to CameraViewTutorial(engine),
                "Set \"look at\" view" to LookAtViewTutorial(engine),
                "Placemarks" to PlacemarksTutorial(engine),
                "Paths" to PathsTutorial(engine),
                "Polygons" to PolygonsTutorial(engine),
                "Ellipses" to EllipsesTutorial(engine),
                "Geographic meshes" to geoMeshTutorial,
                "Triangle meshes" to triMeshTutorial,
                "COLLADA" to colladaTutorial,
                "GLTF" to gltfTutorial,
                "Dash and fill" to ShapesDashAndFillTutorial(engine),
                "Labels" to LabelsTutorial(engine),
                "Real-time sightline" to SightlineTutorial(engine),
                "Viewshed sightline" to ViewshedSightlineTutorial(engine),
                "Surface image" to SurfaceImageTutorial(engine),
                "Photo on terrain" to PhotoOnTerrainTutorial(engine),
                "Video on terrain (VLCJ)" to VlcjVideoOnTerrainTutorial(engine),
                "Video on terrain (JavaCV)" to JavaCvVideoOnTerrainTutorial(engine),
                "Video on terrain (FFmpeg)" to FFmpegVideoOnTerrainTutorial(engine),
                "Video on terrain (JavaFX)" to JavaFxVideoOnTerrainTutorial(engine),
                "MilStd2525 graphics" to MilStd2525Tutorial(engine),
                "Show tessellation" to ShowTessellationTutorial(engine),
                "MGRS Graticule" to MGRSGraticuleTutorial(engine),
                "Gauss-Kruger Graticule" to GKGraticuleTutorial(engine),
                "WMS Layer" to WmsLayerTutorial(engine, mainScope),
                "WMTS Layer" to WmtsLayerTutorial(engine, mainScope),
                "WCS Elevation" to WcsElevationTutorial(engine),
                "Elevation Heatmap" to ElevationHeatmapTutorial(engine),
            )

            val projections = linkedMapOf(
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

            SwingUtilities.invokeLater {
                fun selectTutorial(name: String) {
                    currentTutorial?.let { tutorials[it]?.stop() }
                    currentTutorial = name
                    tutorials[name]?.let { tutorial ->
                        tutorial.start()
                        actionsPanel.removeAll()
                        tutorial.actions?.forEach { actionName ->
                            actionsPanel.add(JButton(actionName).apply {
                                addActionListener { tutorial.runAction(actionName) }
                            })
                        }
                        actionsPanel.isVisible = tutorial.actions?.isNotEmpty() == true
                        actionsPanel.revalidate()
                    }
                    val picker = (tutorials[name] as? PickIndicatorTutorial)?.picker
                    clickHandler = when {
                        picker != null -> { e ->
                            // pickAsync resolves on the JOGL thread; await it via mainScope.
                            val p = wwd!!.viewportCoordinates(e.x, e.y)
                            mainScope.launch {
                                picker.showPick(engine, wwd!!.pickAsync(p.x, p.y).await().topPickedObject?.cartesianPoint)
                                wwd?.requestRedraw()
                            }
                            Unit
                        }
                        name == "Geographic meshes" -> { e ->
                            if (geoMeshTutorial.isStarted) {
                                val ray = Line()
                                val p = wwd!!.viewportCoordinates(e.x, e.y)
                                if (engine.rayThroughScreenPoint(p.x, p.y, ray)) {
                                    geoMeshTutorial.pickMesh(ray, engine.globe)
                                    wwd?.requestRedraw()
                                }
                            }
                        }
                        name == "Triangle meshes" -> { e ->
                            if (triMeshTutorial.isStarted) {
                                val ray = Line()
                                val p = wwd!!.viewportCoordinates(e.x, e.y)
                                if (engine.rayThroughScreenPoint(p.x, p.y, ray)) {
                                    triMeshTutorial.pickMesh(ray, engine.globe)
                                    wwd?.requestRedraw()
                                }
                            }
                        }
                        name == "COLLADA" -> { e ->
                            if (colladaTutorial.isStarted) {
                                val ray = Line()
                                val p = wwd!!.viewportCoordinates(e.x, e.y)
                                if (engine.rayThroughScreenPoint(p.x, p.y, ray)) {
                                    colladaTutorial.pickScene(ray, engine.globe)
                                    wwd?.requestRedraw()
                                }
                            }
                        }
                        else -> null
                    }
                    wwd?.requestRedraw()
                }

                fun selectProjection(name: String) {
                    engine.globe.projection = projections[name]!!
                    wwd?.requestRedraw()
                }

                tutorials.keys.forEach { tutorialCombo.addItem(it) }
                projections.keys.forEach { projectionCombo.addItem(it) }

                tutorialCombo.addActionListener { selectTutorial(tutorialCombo.selectedItem as String) }
                projectionCombo.addActionListener { selectProjection(projectionCombo.selectedItem as String) }

                selectTutorial(tutorials.keys.first())
                selectProjection(projections.keys.first())
            }
        }

        wwd.selectDragDetector.callback = object : SelectDragCallback {
            override fun canPickRenderable(renderable: Renderable) = renderable is Movable
            override fun canMoveRenderable(renderable: Renderable) = renderable is Movable
        }

        wwd.controller = object : BasicWorldWindowController(wwd) {
            private var pressX = 0; private var pressY = 0
            private var pressOnVC = false

            override fun onMouseEvent(event: MouseEvent): Boolean {
                when (event.id) {
                    MouseEvent.MOUSE_PRESSED -> {
                        pressX = event.x; pressY = event.y
                        pressOnVC = false
                        val consumed = super.onMouseEvent(event)
                        if (consumed && vcRepeatTimer != null) pressOnVC = true
                        return consumed
                    }
                    MouseEvent.MOUSE_RELEASED -> {
                        val result = super.onMouseEvent(event)
                        if (!pressOnVC) {
                            val dx = event.x - pressX; val dy = event.y - pressY
                            if (dx * dx + dy * dy < 100) clickHandler?.invoke(event)
                        }
                        pressOnVC = false
                        return result
                    }
                }
                return super.onMouseEvent(event)
            }
        }

        val controlsPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Tutorial:"))
            add(tutorialCombo)
            add(JLabel("Projection:"))
            add(projectionCombo)
            add(actionsPanel)
        }

        JFrame("WorldWind Kotlin - Tutorials").apply {
            defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
            layout = BorderLayout()
            add(controlsPanel, BorderLayout.NORTH)
            add(wwd, BorderLayout.CENTER)
            setSize(1280, 800)
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
}