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
import earth.worldwind.formats.gltf.draco.installDracoDecoder
import earth.worldwind.formats.gltf.ktx2.installKtx2Decoder
import earth.worldwind.layer.ogc3d.content.spz.installDefaultSpzInflater
import earth.worldwind.globe.elevation.coverage.BasicElevationCoverage
import earth.worldwind.formats.shapefile.ShapefileBulkFeatureSource
import earth.worldwind.layer.cache.CachePolicy
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
import earth.worldwind.ogc.wfs.WfsTiledFeatureSource
import earth.worldwind.layer.BulkFeatureLayer
import earth.worldwind.layer.TiledFeatureLayer
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
        // 3D Tiles codecs install once for the process. Draco + KTX2 loaders are suspend
        // (JNI bridges) — fire-and-forget; the first .glb fetch lands long after start-up.
        mainScope.launch {
            installDracoDecoder()
            installKtx2Decoder()
        }
        installDefaultSpzInflater()
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
                "OGC 3D Tiles" to Ogc3dTilesTutorial(
                    engine,
                    cacheProvider = { info ->
                        contentManager.openBlobStore(
                            contentKey = "ogc3d_tutorial",
                            evictionPolicy = CachePolicy(maxEntries = 32_000L),
                            displayName = "OGC 3D Tiles tutorial cache",
                        ).also { contentManager.registerWebService("ogc3d_tutorial", info) }
                    },
                ),
                "Google 3D Tiles" to Google3dTilesTutorial(
                    engine,
                    cacheProvider = { info ->
                        contentManager.openBlobStore(
                            contentKey = "google_3dtiles_tutorial",
                            evictionPolicy = CachePolicy(maxEntries = 32_000L),
                            displayName = "Google Photorealistic 3D Tiles tutorial cache",
                        ).also { contentManager.registerWebService("google_3dtiles_tutorial", info) }
                    },
                ),
                "Cesium Ion 3D Tiles" to CesiumIon3dTilesTutorial(
                    engine,
                    cacheProvider = { info ->
                        contentManager.openBlobStore(
                            contentKey = "cesium_ion_3dtiles_tutorial",
                            evictionPolicy = CachePolicy(maxEntries = 32_000L),
                            displayName = "Cesium Ion 3D Tiles tutorial cache",
                        ).also { contentManager.registerWebService("cesium_ion_3dtiles_tutorial", info) }
                    },
                ),
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
                "WFS Auto-Refresh" to WfsAutoRefreshTutorial(engine, mainScope),
                "WFS Cached (viewport)" to WfsAutoRefreshTutorial(engine, mainScope, layerProvider = {
                    // Strategy 1: download once into a GeoPackage, then read each viewport from its RTree.
                    val layer = BulkFeatureLayer(
                        source = WfsBulkFeatureSource(
                            serviceAddress = WfsAutoRefreshTutorial.SERVICE_ADDRESS,
                            layerName = WfsAutoRefreshTutorial.TYPE_NAME,
                        ),
                        displayName = WfsAutoRefreshTutorial.DISPLAY_NAME,
                        customLogicToApplyProperties = WfsAutoRefreshTutorial.countryStyling,
                    ).apply {
                        isPickEnabled = false
                        autoRefreshViewport = true
                    }
                    contentManager.attachCache(layer, "WFS_Countries")
                    layer
                }),
                "WFS Roads (tiled)" to WfsAutoRefreshTutorial(engine, mainScope,
                    cameraLatitude = WfsAutoRefreshTutorial.ROADS_CAMERA_LAT,
                    cameraLongitude = WfsAutoRefreshTutorial.ROADS_CAMERA_LON,
                    cameraAltitude = WfsAutoRefreshTutorial.ROADS_CAMERA_ALT,
                    layerProvider = {
                    // Strategy 2: per-tile BBOX GetFeature, each tile cached in the GeoPackage and
                    // read cache-first on revisit. Suits ne:ne_10m_roads (dense global lines, too large
                    // to fetch whole) — exercises the batched line/outline path with many features per tile.
                    val layer = TiledFeatureLayer(
                        source = WfsTiledFeatureSource(
                            serviceAddress = WfsAutoRefreshTutorial.SERVICE_ADDRESS,
                            typeName = WfsAutoRefreshTutorial.ROADS_TYPE_NAME,
                            maxFeaturesPerTile = WfsAutoRefreshTutorial.ROADS_MAX_FEATURES_PER_TILE,
                        ),
                        levelOffset = WfsAutoRefreshTutorial.ROADS_LEVEL_OFFSET,
                        // useBatchedRendering defaults true: per-tile batched line outlines (one VBO/EBO per tile).
                        displayName = WfsAutoRefreshTutorial.ROADS_DISPLAY_NAME,
                        customLogicToApplyProperties = WfsAutoRefreshTutorial.roadsStyling,
                        // Road LINES: never draw a coarser ancestor — its lines would show through the
                        // finer tiles (both resolutions at once). Keep only the finest loaded tiles; cells
                        // still loading stay blank for a moment rather than showing doubled roads.
                        coarseAncestorFallback = false,
                    ).apply {
                        isPickEnabled = false
                        // Shrink each tile's line VBO (the GPU-memory driver) by simplifying harder.
                        simplifyTolerancePixels = WfsAutoRefreshTutorial.ROADS_SIMPLIFY_PX
                    }
                    contentManager.attachCache(layer, "WFS_Roads")
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