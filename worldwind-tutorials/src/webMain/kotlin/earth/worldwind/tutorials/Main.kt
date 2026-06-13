@file:Suppress("OPT_IN_USAGE")

package earth.worldwind.tutorials

import earth.worldwind.WorldWindow
import earth.worldwind.geom.Line
import earth.worldwind.gesture.SelectDragCallback
import earth.worldwind.globe.elevation.coverage.BasicElevationCoverage
import earth.worldwind.layer.cache.CachePolicy
import earth.worldwind.layer.cache.WebContentManager
import earth.worldwind.layer.cache.attachCache
import earth.worldwind.formats.shapefile.ShapefileBulkFeatureSource
import earth.worldwind.layer.mercator.WebMercatorLayerFactory
import earth.worldwind.layer.mvt.MvtVectorLayer
import earth.worldwind.layer.mvt.OpenTopoMapRules
import earth.worldwind.layer.mvt.UrlTemplateMvtTileSource
import earth.worldwind.ogc.Wcs100ElevationCoverage
import earth.worldwind.ogc.WmsLayerFactory
import earth.worldwind.ogc.WmtsLayerFactory
import earth.worldwind.ogc.wfs.WfsBulkFeatureSource
import earth.worldwind.layer.BulkFeatureLayer
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
import earth.worldwind.layer.ogc3d.content.spz.SpzGaussianLoader
import earth.worldwind.layer.ogc3d.content.spz.installDefaultSpzInflater
import earth.worldwind.layer.shadow.ShadowLayer
import earth.worldwind.layer.starfield.StarFieldLayer
import earth.worldwind.shape.Movable
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.w3c.dom.*
import org.w3c.dom.events.MouseEvent

// JS sees text().await() as String (so .toString() looks redundant), but wasmJs returns JsString and needs it.
@Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")
fun main() {
    // Kotlin/Wasm instantiates the module asynchronously, so main() can run AFTER the window
    // `load` event has already fired — a freshly-registered window.onload would then never run
    // (black canvas, empty dropdowns, no error). Run immediately if the DOM is already parsed,
    // otherwise wait for load. (On js, main() runs during parsing so the load path is taken.)
    val onLoad: () -> Unit = {
        // Create a WorldWindow for the canvas.
        val wwd = WorldWindow(document.getElementById("WorldWindow") as HTMLCanvasElement)
        val tutorialSelect = document.getElementById("Tutorials") as HTMLSelectElement
        val projectionSelect = document.getElementById("Projections") as HTMLSelectElement
        val actionsContainer = document.getElementById("Actions") as HTMLDivElement
        // Catch-all handler for unhandled coroutine exceptions — primarily Ktor's
        // `TypeError: Failed to fetch` when a tutorial switch cancels an in-flight HTTP
        // request mid-pipeline and the cancellation reason races past per-launch
        // try/catch (JS fetch failures can surface as plain exceptions rather than
        // CancellationException). Without this the error bubbles up to the browser's
        // unhandled-promise overlay even though every fetch site has try/catch.
        val mainExceptionHandler = CoroutineExceptionHandler { _, throwable ->
            jsConsole.warn(
                "Tutorial coroutine swallowed unhandled exception " +
                    "(usually a cancelled fetch): ${throwable.message}",
            )
        }
        val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + mainExceptionHandler)
        // 3D Tiles codecs install once for the process. Draco + KTX2 loaders are suspend
        // (load WASM modules) — fire-and-forget; the first .glb fetch lands long after start-up.
        mainScope.launch {
            installDracoDecoder()
            installKtx2Decoder()
        }
        installDefaultSpzInflater()
        // Browser's `installDefaultSpzInflater` is a no-op (CompressionStream is async-only);
        // wire pako's sync `ungzip` so SPZ tiles can decode on the render thread.
        SpzGaussianLoader.inflater = PakoSpzInflater
        // Shared IndexedDB-backed cache for the JS target. Declared up here so it's
        // visible inside the `tutorials` map below — WMS / WMTS tutorial loaders
        // reference it for cache-aware factory mode.
        val contentManager = WebContentManager(databaseName = "worldwind-tutorials")
        // Single global click listener per picker. `picker.isActive` gates the call so listeners
        // for non-current tutorials short-circuit and don't cross-talk. WorldWindow swallows
        // click events that came from a drag, so no tap-vs-drag check is needed here.
        fun installDepthPicker(picker: PickResultIndicator) {
            wwd.addEventListener("click") { e ->
                if (!picker.isActive || e !is MouseEvent) return@addEventListener
                val clickPoint = wwd.canvasCoordinates(e.clientX, e.clientY)
                picker.showPick(wwd.engine, wwd.pick(clickPoint).topPickedObject?.cartesianPoint)
                wwd.requestRedraw()
            }
        }
        // Ray-pick (CPU multi-hit) variant for Mesh / Collada tutorials.
        fun installRayPicker(isActive: () -> Boolean, onPick: (Line) -> Unit) {
            wwd.addEventListener("click") { e ->
                if (!isActive() || e !is MouseEvent) return@addEventListener
                val clickPoint = wwd.canvasCoordinates(e.clientX, e.clientY)
                val clickRay = Line()
                if (wwd.engine.rayThroughScreenPoint(clickPoint.x, clickPoint.y, clickRay)) {
                    onPick(clickRay)
                    wwd.requestRedraw()
                }
            }
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
            "OGC 3D Tiles" to Ogc3dTilesTutorial(
                wwd.engine,
                cacheProvider = { info ->
                    contentManager.openBlobStore(
                        contentKey = "ogc3d_tutorial",
                        evictionPolicy = CachePolicy(maxEntries = 16_000L),
                        displayName = "OGC 3D Tiles tutorial cache",
                    ).also { contentManager.registerWebService("ogc3d_tutorial", info) }
                },
            ),
            "Google 3D Tiles" to Google3dTilesTutorial(
                wwd.engine,
                cacheProvider = { info ->
                    contentManager.openBlobStore(
                        contentKey = "google_3dtiles_tutorial",
                        evictionPolicy = CachePolicy(maxEntries = 16_000L),
                        displayName = "Google Photorealistic 3D Tiles tutorial cache",
                    ).also { contentManager.registerWebService("google_3dtiles_tutorial", info) }
                },
            ),
            "Cesium Ion 3D Tiles" to CesiumIon3dTilesTutorial(
                wwd.engine,
                cacheProvider = { info ->
                    contentManager.openBlobStore(
                        contentKey = "cesium_ion_3dtiles_tutorial",
                        evictionPolicy = CachePolicy(maxEntries = 16_000L),
                        displayName = "Cesium Ion 3D Tiles tutorial cache",
                    ).also { contentManager.registerWebService("cesium_ion_3dtiles_tutorial", info) }
                },
            ),
            "OSM Buildings" to OsmBuildingsTutorial(wwd.engine, mainScope, layerLoader = {
                OsmBuildingsLayer(useOsmColors = true).also {
                    contentManager.attachCache(it, "OsmBuildings")
                }
            }),
            "Vector Tiles (MVT)" to MvtVectorTilesTutorial(wwd.engine, mainScope, layerLoader = {
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
            "WMS Layer" to WmsLayerTutorial(wwd.engine, mainScope, layerLoader = {
                val key = "WMS_NeoTemperature"
                WmsLayerFactory.createLayer(
                    serviceAddress = WmsLayerTutorial.SERVICE_ADDRESS,
                    layerNames = WmsLayerTutorial.LAYER_NAMES,
                    serviceMetadata = contentManager.findEntry(key)?.service?.metadata,
                ).also { contentManager.attachCache(it, key) }
            }),
            "WMTS Layer" to WmtsLayerTutorial(wwd.engine, mainScope, layerLoader = {
                val key = "WMTS_DlrHillshade"
                WmtsLayerFactory.createLayer(
                    serviceAddress = WmtsLayerTutorial.SERVICE_ADDRESS,
                    layerName = WmtsLayerTutorial.LAYER_NAME,
                    serviceMetadata = contentManager.findEntry(key)?.service?.metadata,
                ).also { contentManager.attachCache(it, key) }
            }),
            "WFS Layer" to WfsLayerTutorial(wwd.engine, mainScope, layerLoader = {
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
            "Shapefile Layer" to ShapefileLayerTutorial(wwd.engine, mainScope, layerLoader = {
                val layer = BulkFeatureLayer(
                    source = ShapefileBulkFeatureSource(ShapefileLayerTutorial.SHP_URL),
                    displayName = ShapefileLayerTutorial.DISPLAY_NAME,
                    shapeAttributes = ShapefileLayerTutorial.defaultPolygonStyle(),
                ).also { it.isPickEnabled = false }
                contentManager.attachCache(layer, "Shapefile_Countries")
                layer
            }),
            "GeoJSON" to GeoJsonTutorial(wwd.engine, mainScope) {
                window.fetch(MR.assets.geojson_sample_json.originalPath, emptyRequestInit()).await().text().await().toString()
            },
            "KML" to KmlTutorial(wwd.engine, mainScope, density = window.devicePixelRatio.toFloat()) {
                window.fetch(MR.assets.kml_sample_kml.originalPath, emptyRequestInit()).await().text().await().toString()
            },
            "WCS Elevation" to WcsElevationTutorial(wwd.engine, mainScope, layerLoader = {
                Wcs100ElevationCoverage(
                    serviceAddress = WcsElevationTutorial.SERVICE_ADDRESS,
                    coverageName = WcsElevationTutorial.COVERAGE_NAME,
                    outputFormat = WcsElevationTutorial.OUTPUT_FORMAT,
                    sector = WcsElevationTutorial.BOUNDING_SECTOR,
                    resolution = WcsElevationTutorial.RESOLUTION,
                ).also { contentManager.attachCache(it, "WCS_3DEP") }
            }),
            "NITF Imagery" to NitfImageryTutorial(wwd.engine),
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

        // Base layers are added synchronously in their final positions but `isEnabled = false`
        // so the renderer issues no tile requests against the network-only factory. The launch
        // below opens the cache, swaps in the cached factory, then flips `isEnabled = true`.
        // Pre-adding (vs add-on-completion) lets tutorials that disable existing coverages at
        // `start()` time — DTED, WCS — see the base layers regardless of attach timing.
        val satellite = WebMercatorLayerFactory.createLayer(
            urlTemplate = "https://mt.google.com/vt/lyrs=s&x={x}&y={y}&z={z}&hl={lang}",
            imageFormat = "image/jpeg",
            name = "Google Satellite",
        ).apply { isEnabled = false }
        val elevation = BasicElevationCoverage().apply { isEnabled = false }
        wwd.engine.layers.apply {
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
        wwd.engine.globe.elevationModel.addCoverage(elevation)
        mainScope.launch {
            // attachCache opens IDB stores and registers web-service rows. Tolerant of any
            // browser-side failure (quota, private-mode, broken IDB schema, transient
            // network during the registration round-trip) — fall back to a network-only
            // wiring so the worst case is "no cache this session".
            try {
                contentManager.attachCache(satellite, "GSat")
                contentManager.attachCache(elevation, BasicElevationCoverage.COVERAGE_NAME)
                satellite.isEnabled = true
                elevation.isEnabled = true
                wwd.requestRedraw()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Throwable) {
                jsConsole.warn("attachCache failed; running network-only: ${e.message}")
                satellite.isEnabled = true
                elevation.isEnabled = true
                wwd.requestRedraw()
            }
        }

        // Allow pick and move any movable object
        wwd.selectDragDetector.callback = object : SelectDragCallback {
            override fun canPickObjects(userObject: Any) = userObject is Movable
            override fun canMoveObjects(userObject: Any) = userObject is Movable
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
    if (document.readyState == DocumentReadyState.LOADING) window.onload = { onLoad() } else onLoad()
}