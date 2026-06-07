package earth.worldwind.tutorials

import java.awt.Toolkit
import earth.worldwind.BasicWorldWindowController
import earth.worldwind.WorldWindow
import earth.worldwind.geom.Line
import earth.worldwind.gesture.SelectDragCallback
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
import earth.worldwind.layer.buildings.OsmBuildingsLayer
import earth.worldwind.globe.elevation.coverage.BasicElevationCoverage
import earth.worldwind.formats.shapefile.ShapefileBulkFeatureSource
import earth.worldwind.layer.cache.attachCache
import earth.worldwind.layer.mercator.WebMercatorLayerFactory
import earth.worldwind.layer.mvt.UrlTemplateMvtTileSource
import earth.worldwind.ogc.Wcs100ElevationCoverage
import earth.worldwind.ogc.WmsLayerFactory
import earth.worldwind.ogc.WmtsLayerFactory
import earth.worldwind.layer.mvt.MvtVectorLayer
import earth.worldwind.layer.mvt.OpenTopoMapRules
import earth.worldwind.layer.shadow.ShadowLayer
import earth.worldwind.layer.starfield.StarFieldLayer
import earth.worldwind.formats.gpkg.GpkgContentManager
import earth.worldwind.ogc.wfs.WfsBulkFeatureSource
import earth.worldwind.layer.BulkFeatureLayer
import earth.worldwind.shape.Movable
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*

fun main() {
    // Demo apps run on whatever JDK the user has installed; outdated `cacerts` truststores
    // reject NASA / DLR / USGS endpoints with PKIX path-building failures. Trust-all is fine
    // for tutorials but should never ship in production.
    installPermissiveSslForTutorials()
    SwingUtilities.invokeLater {
        val mainScope = MainScope()
        // Shared GeoPackage cache; per-layer rows namespaced by content key. Parent-dir
        // creation must happen BEFORE the GpkgContentManager constructor — `geoPackage` is
        // an eager `val` that opens the SQLite file synchronously, so a missing parent dir
        // fails the open before any post-construction `.also { mkdirs() }` could run.
        val cachePath = File(
            System.getProperty("user.home"),
            ".cache/worldwind-tutorials/cache_content.gpkg",
        )
        cachePath.parentFile?.mkdirs()
        val contentManager = GpkgContentManager(cachePath.absolutePath)
        val tutorialCombo = JComboBox<String>()
        val projectionCombo = JComboBox<String>()
        val actionsPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        var clickHandler: ((MouseEvent) -> Unit)? = null
        var wwd: WorldWindow? = null

        wwd = WorldWindow { engine ->
            // Base layers are added synchronously in their final positions but `isEnabled = false`
            // so the renderer issues no tile requests against the network-only factory. The launch
            // below opens the cache, swaps in the cached factory, then flips `isEnabled = true`.
            // Pre-adding (vs add-on-completion) lets tutorials that disable existing coverages
            // at `start()` time — DTED, WCS — see the base layers regardless of attach timing.
            val satellite = WebMercatorLayerFactory.createLayer(
                urlTemplate = "https://mt.google.com/vt/lyrs=s&x={x}&y={y}&z={z}&hl={lang}",
                imageFormat = "image/jpeg",
                name = "Google Satellite",
            ).apply { isEnabled = false }
            val elevation = BasicElevationCoverage().apply { isEnabled = false }
            with(engine.layers) {
                addLayer(BackgroundLayer())
                addLayer(satellite)
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
            engine.globe.elevationModel.addCoverage(elevation)
            mainScope.launch {
                contentManager.attachCache(satellite, "GSat")
                contentManager.attachCache(elevation, BasicElevationCoverage.COVERAGE_NAME)
                satellite.isEnabled = true
                elevation.isEnabled = true
                wwd?.requestRedraw()
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
                "Ellipsoids" to EllipsoidsTutorial(engine),
                "Geographic meshes" to geoMeshTutorial,
                "Triangle meshes" to triMeshTutorial,
                "COLLADA" to colladaTutorial,
                "GLTF" to gltfTutorial,
                "OSM Buildings" to OsmBuildingsTutorial(engine, mainScope, layerLoader = {
                    OsmBuildingsLayer(useOsmColors = true).also {
                        contentManager.attachCache(it, "OsmBuildings")
                    }
                }),
                "Vector Tiles (MVT)" to MvtVectorTilesTutorial(engine, mainScope, layerLoader = {
                    val layer = MvtVectorLayer(
                        source = UrlTemplateMvtTileSource(MvtVectorTilesTutorial.URL_TEMPLATE),
                        minZoom = MvtVectorTilesTutorial.MIN_ZOOM,
                        maxZoom = MvtVectorTilesTutorial.MAX_ZOOM,
                        tileRadius = MvtVectorTilesTutorial.TILE_RADIUS,
                        style = OpenTopoMapRules,
                    )
                    contentManager.attachCache(layer, "MVT_Versatiles")
                    layer
                }),
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
                "WMS Layer" to WmsLayerTutorial(engine, mainScope, layerLoader = {
                    val key = "WMS_NeoTemperature"
                    WmsLayerFactory.createLayer(
                        serviceAddress = WmsLayerTutorial.SERVICE_ADDRESS,
                        layerNames = WmsLayerTutorial.LAYER_NAMES,
                        serviceMetadata = contentManager.findEntry(key)?.service?.metadata,
                    ).also { contentManager.attachCache(it, key) }
                }),
                "WMTS Layer" to WmtsLayerTutorial(engine, mainScope, layerLoader = {
                    val key = "WMTS_DlrHillshade"
                    WmtsLayerFactory.createLayer(
                        serviceAddress = WmtsLayerTutorial.SERVICE_ADDRESS,
                        layerName = WmtsLayerTutorial.LAYER_NAME,
                        serviceMetadata = contentManager.findEntry(key)?.service?.metadata,
                    ).also { contentManager.attachCache(it, key) }
                }),
                "WFS Layer" to WfsLayerTutorial(engine, mainScope, layerLoader = {
                    val layer = BulkFeatureLayer(
                        source = WfsBulkFeatureSource(
                            serviceAddress = WfsLayerTutorial.SERVICE_ADDRESS,
                            layerName = WfsLayerTutorial.TYPE_NAME,
                            maxFeatures = WfsLayerTutorial.MAX_FEATURES,
                            pageSize = WfsLayerTutorial.PAGE_SIZE,
                        ),
                        displayName = WfsLayerTutorial.DISPLAY_NAME,
                        customLogicToApplyProperties = WfsLayerTutorial.populationStyling,
                    )
                    contentManager.attachCache(layer, "WFS_Cities")
                    layer
                }),
                "Shapefile Layer" to ShapefileLayerTutorial(engine, mainScope, layerLoader = {
                    val layer = BulkFeatureLayer(
                        source = ShapefileBulkFeatureSource(ShapefileLayerTutorial.SHP_URL),
                        displayName = ShapefileLayerTutorial.DISPLAY_NAME,
                        shapeAttributes = ShapefileLayerTutorial.defaultPolygonStyle(),
                    ).also { it.isPickEnabled = false }
                    contentManager.attachCache(layer, "Shapefile_Countries")
                    layer
                }),
                "GeoJSON" to GeoJsonTutorial(engine, mainScope) {
                    MR.assets.geojson_sample_json.readText()
                },
                "KML" to KmlTutorial(engine, mainScope, density = Toolkit.getDefaultToolkit().screenResolution / 96f) {
                    MR.assets.kml_sample_kml.readText()
                },
                "WCS Elevation" to WcsElevationTutorial(engine, mainScope, layerLoader = {
                    Wcs100ElevationCoverage(
                        serviceAddress = WcsElevationTutorial.SERVICE_ADDRESS,
                        coverageName = WcsElevationTutorial.COVERAGE_NAME,
                        outputFormat = WcsElevationTutorial.OUTPUT_FORMAT,
                        sector = WcsElevationTutorial.BOUNDING_SECTOR,
                        resolution = WcsElevationTutorial.RESOLUTION,
                    ).also { contentManager.attachCache(it, "WCS_3DEP") }
                }),
                "GeoPackage (bundled)" to GeoPackageTutorial(engine, mainScope) {
                    SharedStagedGeoPackage.path()
                },
                "DTED Elevation (local)" to DtedElevationTutorial(engine),
                "NITF Imagery" to NitfImageryTutorial(engine),
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

            // The factory body itself runs on the AWT EDT (WorldWindow's GLEventListener.init
            // routes through SwingUtilities.invokeAndWait), so the JComboBox / JButton wiring
            // below doesn't need a separate invokeLater hop.
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

        wwd.selectDragDetector.callback = object : SelectDragCallback {
            override fun canPickObjects(userObject: Any) = userObject is Movable
            override fun canMoveObjects(userObject: Any) = userObject is Movable
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