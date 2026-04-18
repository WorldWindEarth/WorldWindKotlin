package earth.worldwind.tutorials

import earth.worldwind.BasicWorldWindowController
import earth.worldwind.WorldWindow
import earth.worldwind.geom.Line
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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import javax.swing.*

fun main() {
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
            }

            val colladaTutorial = ColladaTutorial(engine).also { tutorial ->
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
                "Dash and fill" to ShapesDashAndFillTutorial(engine),
                "Labels" to LabelsTutorial(engine),
                "Sight line" to SightlineTutorial(engine),
                "Surface image" to SurfaceImageTutorial(engine),
                "Texture quad" to TextureQuadTutorial(engine),
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
                "Mercator Projection" to MercatorProjection()
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
                    clickHandler = when (name) {
                        "Geographic meshes" -> { e ->
                            if (geoMeshTutorial.isStarted) {
                                val ray = Line()
                                if (engine.rayThroughScreenPoint(e.x.toDouble(), (engine.viewport.height - e.y).toDouble(), ray)) {
                                    geoMeshTutorial.pickMesh(ray, engine.globe)
                                    wwd?.requestRedraw()
                                }
                            }
                        }
                        "Triangle meshes" -> { e ->
                            if (triMeshTutorial.isStarted) {
                                val ray = Line()
                                if (engine.rayThroughScreenPoint(e.x.toDouble(), (engine.viewport.height - e.y).toDouble(), ray)) {
                                    triMeshTutorial.pickMesh(ray, engine.globe)
                                    wwd?.requestRedraw()
                                }
                            }
                        }
                        "COLLADA" -> { e ->
                            if (colladaTutorial.isStarted) {
                                val ray = Line()
                                if (engine.rayThroughScreenPoint(e.x.toDouble(), (engine.viewport.height - e.y).toDouble(), ray)) {
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
            override fun onMouseEvent(event: MouseEvent): Boolean {
                if (event.id == MouseEvent.MOUSE_CLICKED && event.button == MouseEvent.BUTTON1) {
                    clickHandler?.invoke(event)
                }
                return super.onMouseEvent(event)
            }
        }

        val controlsPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Tutorial:"))
            add(tutorialCombo)
            add(JLabel("Projection:"))
            add(projectionCombo)
        }

        JFrame("WorldWind Kotlin - Tutorials").apply {
            defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
            layout = BorderLayout()
            add(controlsPanel, BorderLayout.NORTH)
            add(wwd, BorderLayout.CENTER)
            add(actionsPanel, BorderLayout.SOUTH)
            setSize(1280, 800)
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
}