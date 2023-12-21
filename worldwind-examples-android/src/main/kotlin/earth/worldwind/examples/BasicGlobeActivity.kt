package earth.worldwind.examples

import android.os.Bundle
import android.widget.FrameLayout
import earth.worldwind.WorldWindow
import earth.worldwind.globe.elevation.coverage.BasicElevationCoverage
import earth.worldwind.layer.BackgroundLayer
import earth.worldwind.layer.atmosphere.AtmosphereLayer
import earth.worldwind.layer.mercator.MercatorLayerFactory
import earth.worldwind.layer.starfield.StarFieldLayer
import earth.worldwind.ogc.GpkgContentManager
import kotlinx.coroutines.launch
import java.io.File

/**
 * Creates a simple view of a globe with touch navigation and a few layers.
 */
open class BasicGlobeActivity: AbstractMainActivity() {
    /**
     * This protected member allows derived classes to override the resource used in setContentView.
     */
    protected var layoutResourceId = R.layout.activity_globe
    /**
     * The WorldWindow (GLSurfaceView) maintained by this activity
     */
    override lateinit var wwd: WorldWindow

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Establish the activity content
        setContentView(layoutResourceId)
        aboutBoxTitle = "About the " + resources.getText(R.string.title_basic_globe)
        aboutBoxText = """Demonstrates how to construct a WorldWindow with a few layers.
The globe uses the default navigation gestures: 
 - one-finger pan moves the camera,
 - two-finger pinch-zoom adjusts the range to the look at position, 
 - two-finger rotate arcs the camera horizontally around the look at position,
 - three-finger tilt arcs the camera vertically around the look at position."""

        // Create the WorldWindow (a GLSurfaceView) which displays the globe.
        wwd = WorldWindow(this)

        // Add the WorldWindow view object to the layout that was reserved for the globe.
        val globeLayout = findViewById<FrameLayout>(R.id.globe)
        globeLayout.addView(wwd)

        // Define cache content manager
        val contentManager = GpkgContentManager(File(cacheDir, "cache.gpkg").absolutePath)

        // Setting up the WorldWindow's layers.
        wwd.engine.layers.apply {
            addLayer(BackgroundLayer())
            addLayer(MercatorLayerFactory.createLayer(
                name = "Google Satellite",
                urlTemplate = "https://mt.google.com/vt/lyrs=s&x={x}&y={y}&z={z}&hl={lang}",
                imageFormat = "image/jpeg"
            ).apply {
                wwd.mainScope.launch {
                    configureCache(contentManager, "GSat")
                    isCacheWritable = true
                }
            })
            addLayer(StarFieldLayer())
            addLayer(AtmosphereLayer())
        }

        // Setting up the WorldWindow's elevation coverages.
        wwd.engine.globe.elevationModel.addCoverage(BasicElevationCoverage().apply {
            wwd.mainScope.launch {
                configureCache(contentManager, "NASADEM")
                isCacheWritable = true
            }
        })
    }

    override fun onPause() {
        super.onPause()
        wwd.onPause() // pauses the rendering thread
    }

    override fun onResume() {
        super.onResume()
        wwd.onResume() // resumes a paused rendering thread
    }
}