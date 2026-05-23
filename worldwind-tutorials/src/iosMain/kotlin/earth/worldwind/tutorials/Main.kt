@file:OptIn(ExperimentalForeignApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.WorldWindow as PlatformWorldWindow
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.log
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Line
import earth.worldwind.gesture.SelectDragCallback
import earth.worldwind.globe.elevation.coverage.BasicElevationCoverage
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
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.launch
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView

/**
 * iOS bridge for the SwiftUI tutorials app.
 *
 * Swift code embeds the [PlatformWorldWindow] (UIView) returned by [createWorldWindow],
 * renders a picker from [Tutorials.all], and calls [Tutorials.apply] when the selection
 * changes. Tutorial state lives on the Kotlin side so Swift doesn't track lifecycle.
 *
 * Tap-driven tutorials (Collada / GLTF / meshes / Paths / Polygons / Ellipses) get clicks
 * via [Tutorials.screenTap]: SwiftUI installs a `UITapGestureRecognizer` and forwards the
 * point-coordinate location; Kotlin multiplies by `contentScaleFactor` and routes to
 * either depth-pick or ray-pick.
 */

/** Returns a fresh [PlatformWorldWindow] with the default tutorial base layers installed
 *  (BackgroundLayer + StarField + Atmosphere + BasicElevationCoverage). The view is sized
 *  later by SwiftUI's UIViewRepresentable layout. */
fun createWorldWindow(): PlatformWorldWindow {
    val wwd = PlatformWorldWindow(CGRectMake(0.0, 0.0, 0.0, 0.0))
    setupDefaultGlobe(wwd.engine)
    // Renderable selection / drag, matches the JVM and Android tutorial setup.
    wwd.selectDragDetector.callback = object : SelectDragCallback {
        override fun canPickRenderable(renderable: Renderable) = renderable is Movable
        override fun canMoveRenderable(renderable: Renderable) = renderable is Movable
    }
    return wwd
}

/**
 * Helpers that bridge the [PlatformWorldWindow] back to Swift.
 *
 * Kotlin/Native cannot re-export Kotlin subclasses of Obj-C classes through the framework's
 * Obj-C header (see the `unavailable` attribute the compiler stamps on `WorldWindow` in the
 * generated header). Swift therefore receives instances as plain `UIView` and cannot call
 * the subclass's Kotlin members directly. These two top-level functions perform the cast on
 * the Kotlin side and expose the bits Swift actually needs (engine + requestRedraw).
 */
fun engineOf(window: UIView): WorldWind = (window as PlatformWorldWindow).engine

fun requestRedrawOn(window: UIView) { (window as PlatformWorldWindow).requestRedraw() }

/** Adds the layers and elevation that nearly every tutorial expects. Called once by
 *  [createWorldWindow] — `Tutorials.apply` does not re-invoke it because each
 *  [AbstractTutorial.start] manages its own layer state on top of these defaults.
 *
 *  Layer order matches :worldwind-tutorials JS/JVM Main: low-res blue-marble background
 *  underneath, Google Satellite Web-Mercator tiles on top of that, then sky/UI overlays. */
fun setupDefaultGlobe(engine: WorldWind) {
    engine.layers.addLayer(BackgroundLayer())
    engine.layers.addLayer(
        WebMercatorLayerFactory.createLayer(
            urlTemplate = "https://mt.google.com/vt/lyrs=s&x={x}&y={y}&z={z}&hl={lang}",
            imageFormat = "image/jpeg",
            name = "Google Satellite",
        )
    )
    engine.layers.addLayer(StarFieldLayer())
    engine.layers.addLayer(AtmosphereLayer())
    engine.layers.addLayer(ShadowLayer())
    engine.layers.addLayer(CompassLayer())
    engine.layers.addLayer(CoordinatesDisplayLayer())
    engine.layers.addLayer(WorldMapLayer().apply { mapWidthDp = 300.0 })
    engine.layers.addLayer(ViewControlsLayer())
    engine.globe.elevationModel.addCoverage(BasicElevationCoverage())
    engine.camera.set(
        latitude = 30.0.degrees,
        longitude = -90.0.degrees,
        altitude = 1.5e7,
        altitudeMode = AltitudeMode.ABSOLUTE,
        heading = ZERO, tilt = ZERO, roll = ZERO,
    )
}

/** Identity + display title for a single tutorial. */
data class TutorialEntry(val id: String, val title: String)

/**
 * Tutorial registry exposed to Swift. MIL-STD-2525 isn't here because it isn't compiled
 * for iOS at all (lives in `nonIosMain`).
 */
object Tutorials {
    /** Single source of truth: id, title, and constructor. [all] is derived from this. */
    private data class TutorialFactory(
        val id: String,
        val title: String,
        val factory: (WorldWind) -> AbstractTutorial,
    )

    // Order mirrors :worldwind-tutorials JS Main.kt for consistency across platforms. The
    // iOS port skips MilStd2525 (lives in nonIosMain) and uses AVPlayerVideoOnTerrainTutorial
    // in the "Video on terrain" slot.
    private val factories: List<TutorialFactory> = listOf(
        TutorialFactory("basic", "Basic globe", ::BasicTutorial),
        TutorialFactory("cameraView", "Set camera view", ::CameraViewTutorial),
        TutorialFactory("lookAtView", "Set \"look at\" view", ::LookAtViewTutorial),
        TutorialFactory("placemarks", "Placemarks", ::PlacemarksTutorial),
        TutorialFactory("paths", "Paths", ::PathsTutorial),
        TutorialFactory("polygons", "Polygons", ::PolygonsTutorial),
        TutorialFactory("ellipses", "Ellipses", ::EllipsesTutorial),
        TutorialFactory("ellipsoids", "Ellipsoids", ::EllipsoidsTutorial),
        TutorialFactory("geographicMeshes", "Geographic meshes", ::GeographicMeshesTutorial),
        TutorialFactory("triangleMeshes", "Triangle meshes", ::TriangleMeshesTutorial),
        TutorialFactory("collada", "COLLADA", ::ColladaTutorial),
        TutorialFactory("gltf", "GLTF", ::GltfTutorial),
        TutorialFactory("osmBuildings", "OSM Buildings", ::OsmBuildingsTutorial),
        TutorialFactory("dashAndFill", "Dash and fill", ::ShapesDashAndFillTutorial),
        TutorialFactory("labels", "Labels", ::LabelsTutorial),
        TutorialFactory("sightline", "Real-time sightline", ::SightlineTutorial),
        TutorialFactory("viewshedSightline", "Viewshed sightline", ::ViewshedSightlineTutorial),
        TutorialFactory("surfaceImage", "Surface image", ::SurfaceImageTutorial),
        TutorialFactory("photoOnTerrain", "Photo on terrain", ::PhotoOnTerrainTutorial),
        TutorialFactory("videoOnTerrain", "Video on terrain", ::AVPlayerVideoOnTerrainTutorial),
        TutorialFactory("showTessellation", "Show tessellation", ::ShowTessellationTutorial),
        TutorialFactory("mgrsGraticule", "MGRS Graticule", ::MGRSGraticuleTutorial),
        TutorialFactory("gkGraticule", "Gauss-Kruger Graticule", ::GKGraticuleTutorial),
        // WMS/WMTS take the engine's mainScope as a second parameter.
        TutorialFactory("wmsLayer", "WMS Layer") { e -> WmsLayerTutorial(e, e.renderResourceCache.mainScope) },
        TutorialFactory("wmtsLayer", "WMTS Layer") { e -> WmtsLayerTutorial(e, e.renderResourceCache.mainScope) },
        TutorialFactory("wcsElevation", "WCS Elevation", ::WcsElevationTutorial),
        TutorialFactory("elevationHeatmap", "Elevation Heatmap", ::ElevationHeatmapTutorial),
    )

    val all: List<TutorialEntry> = factories.map { TutorialEntry(it.id, it.title) }

    private var current: AbstractTutorial? = null

    /** Apply tutorial [id] to [engine]. Stops any tutorial that's already running. For
     *  Collada and GLTF, [start] runs synchronously (camera focus + layers added) and the
     *  async [setupScene] loads the model in the background; the empty renderable layer is
     *  populated on the next frame after the .dae / .gltf parse completes. This matches the
     *  JS app's semantics and avoids the prior deferred-start where a single setup failure
     *  would silently swallow both the camera focus and the model load. */
    fun apply(engine: WorldWind, id: String) {
        current?.stop()
        val tutorial = construct(engine, id)
        current = tutorial
        tutorial?.start()
        when (tutorial) {
            is ColladaTutorial -> engine.renderResourceCache.mainScope.launch {
                try { tutorial.setupScene() }
                catch (e: Throwable) { log(ERROR, "ColladaTutorial setupScene failed: ${e.message}") }
            }
            is GltfTutorial -> engine.renderResourceCache.mainScope.launch {
                try { tutorial.setupScene() }
                catch (e: Throwable) { log(ERROR, "GltfTutorial setupScene failed: ${e.message}") }
            }
            else -> {}
        }
    }

    /** Stop and clear the current tutorial. */
    fun stopCurrent() {
        current?.stop()
        current = null
    }

    /**
     * Route a tap from the SwiftUI host into the active tutorial's pick logic.
     *
     * @param wwd      the WorldWindow that received the tap
     * @param xPoints  tap X in UIKit points (UITapGestureRecognizer.locationInView)
     * @param yPoints  tap Y in UIKit points
     *
     * No-op for tutorials that don't have a pick handler.
     */
    fun screenTap(wwd: PlatformWorldWindow, xPoints: Float, yPoints: Float) {
        val tutorial = current ?: return
        val scale = wwd.contentScaleFactor.toFloat()
        val xPx = xPoints * scale
        val yPx = yPoints * scale

        when (tutorial) {
            is ColladaTutorial -> {
                if (!tutorial.isStarted) return
                val ray = Line()
                if (wwd.engine.rayThroughScreenPoint(xPx.toDouble(), yPx.toDouble(), ray)) {
                    tutorial.pickScene(ray, wwd.engine.globe)
                    wwd.requestRedraw()
                }
            }
            is TriangleMeshesTutorial -> {
                if (!tutorial.isStarted) return
                val ray = Line()
                if (wwd.engine.rayThroughScreenPoint(xPx.toDouble(), yPx.toDouble(), ray)) {
                    tutorial.pickMesh(ray, wwd.engine.globe)
                    wwd.requestRedraw()
                }
            }
            is GeographicMeshesTutorial -> {
                if (!tutorial.isStarted) return
                val ray = Line()
                if (wwd.engine.rayThroughScreenPoint(xPx.toDouble(), yPx.toDouble(), ray)) {
                    tutorial.pickMesh(ray, wwd.engine.globe)
                    wwd.requestRedraw()
                }
            }
            is PickIndicatorTutorial -> {
                // Depth pick is async on iOS — the result becomes available after the next
                // CADisplayLink tick. mainScope.launch + await is the correct sequence.
                if (!tutorial.picker.isActive) return
                wwd.engine.renderResourceCache.mainScope.launch {
                    val list = wwd.pickAsync(xPx, yPx).await()
                    tutorial.picker.showPick(wwd.engine, list.topPickedObject?.cartesianPoint)
                    wwd.requestRedraw()
                }
            }
            else -> { /* no pick handler */ }
        }
    }

    private fun construct(engine: WorldWind, id: String): AbstractTutorial? =
        factories.firstOrNull { it.id == id }?.factory?.invoke(engine)
}

@Suppress("unused")
private fun keepUIViewSymbolReachable(): UIView? = null
