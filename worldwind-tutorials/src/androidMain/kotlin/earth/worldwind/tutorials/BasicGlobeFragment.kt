package earth.worldwind.tutorials

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import earth.worldwind.WorldWindow
import earth.worldwind.gesture.SelectDragCallback
import earth.worldwind.globe.elevation.coverage.BasicElevationCoverage
import earth.worldwind.layer.BackgroundLayer
import earth.worldwind.layer.CompassLayer
import earth.worldwind.layer.ViewControlsLayer
import earth.worldwind.layer.WorldMapLayer
import earth.worldwind.layer.atmosphere.AtmosphereLayer
import earth.worldwind.layer.mercator.WebMercatorLayerFactory
import earth.worldwind.layer.starfield.StarFieldLayer
import earth.worldwind.ogc.GpkgContentManager
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Movable
import kotlinx.coroutines.launch
import java.io.File

open class BasicGlobeFragment: Fragment() {
    /**
     * Gets the WorldWindow (GLSurfaceView) object.
     */
    lateinit var wwd: WorldWindow
        private set

    /**
     * Creates a new WorldWindow (GLSurfaceView) object.
     */
    open fun createWorldWindow(): WorldWindow {
        // Create the WorldWindow (a GLSurfaceView) which displays the globe.
        wwd = WorldWindow(requireContext())
        // Define cache content manager
        val contentManager = GpkgContentManager(File(requireContext().cacheDir, "cache_content.gpkg").absolutePath)
        // Setting up the WorldWindow's layers.
        wwd.engine.layers.apply {
            addLayer(BackgroundLayer())
            addLayer(WebMercatorLayerFactory.createLayer(
                urlTemplate = "https://mt.google.com/vt/lyrs=s&x={x}&y={y}&z={z}&hl={lang}",
                imageFormat = "image/jpeg",
                name = "Google Satellite"
            ).apply {
                lifecycleScope.launch { configureCache(contentManager, "GSat") }
            })
            addLayer(StarFieldLayer())
            addLayer(AtmosphereLayer())
            addLayer(CompassLayer())
            addLayer(WorldMapLayer().apply { corner = WorldMapLayer.Corner.TOP_LEFT })
            addLayer(ViewControlsLayer())
        }
        // Setting up the WorldWindow's elevation coverages.
        wwd.engine.globe.elevationModel.addCoverage(BasicElevationCoverage().apply {
            lifecycleScope.launch { configureCache(contentManager, "NASADEM") }
        })
        // Allow picking and dragging any Movable renderable, mirroring the JVM / JS tutorial setup.
        wwd.selectDragDetector.callback = object : SelectDragCallback {
            override fun canPickRenderable(renderable: Renderable) = renderable is Movable
            override fun canMoveRenderable(renderable: Renderable) = renderable is Movable
        }
        return wwd
    }

    /**
     * Adds the WorldWindow to this Fragment's layout.
     */
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_globe, container, false)
        val globeLayout = rootView.findViewById<FrameLayout>(R.id.globe)

        // Add the WorldWindow view object to the layout that was reserved for the globe.
        globeLayout.addView(createWorldWindow())
        return rootView
    }

    /**
     * Hook a depth-based pick indicator into [wwd]. Returns false so the event still flows
     * through to the WorldWindow's navigation handlers. Tap-vs-drag is gated by the platform
     * touch slop so a pan/zoom drag-end doesn't fire a spurious pick.
     */
    @SuppressLint("ClickableViewAccessibility")
    protected fun installDepthPickIndicator(picker: PickResultIndicator) {
        val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        wwd.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (dx * dx + dy * dy <= touchSlop * touchSlop) {
                        val x = event.x
                        val y = event.y
                        lifecycleScope.launch {
                            picker.showPick(wwd.engine, wwd.pickAsync(x, y).await().topPickedObject?.cartesianPoint)
                            wwd.requestRedraw()
                        }
                    }
                }
            }
            false
        }
    }

    /**
     * Resumes the WorldWindow's rendering thread
     */
    override fun onStart() {
        super.onStart()
        wwd.onResume() // resumes a paused rendering thread
    }

    /**
     * Pauses the WorldWindow's rendering thread
     */
    override fun onStop() {
        super.onStop()
        wwd.onPause() // pauses the rendering thread
    }
}