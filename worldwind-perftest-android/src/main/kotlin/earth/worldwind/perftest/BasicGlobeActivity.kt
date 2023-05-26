package earth.worldwind.perftest

import android.os.Bundle
import android.widget.FrameLayout
import earth.worldwind.WorldWindow
import earth.worldwind.globe.elevation.coverage.BasicElevationCoverage
import earth.worldwind.layer.BackgroundLayer
import earth.worldwind.layer.atmosphere.AtmosphereLayer
import earth.worldwind.layer.mercator.google.GoogleLayer
import earth.worldwind.layer.starfield.StarFieldLayer
import kotlinx.coroutines.Dispatchers
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

        // Create the WorldWindow (a GLSurfaceView) which displays the globe.
        wwd = WorldWindow(this)

        // Add the WorldWindow view object to the layout that was reserved for the globe.
        val globeLayout = findViewById<FrameLayout>(R.id.globe)
        globeLayout.addView(wwd)

        // Setting up the WorldWindow's layers.
        wwd.engine.layers.apply {
            addLayer(BackgroundLayer())
            addLayer(GoogleLayer(GoogleLayer.Type.SATELLITE).apply {
                wwd.mainScope.launch(Dispatchers.IO) {
                    try {
                        configureCache(File(cacheDir, "cache.gpkg").absolutePath, "GSat")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            })
            addLayer(StarFieldLayer())
            addLayer(AtmosphereLayer())
        }

        // Setting up the WorldWindow's elevation coverages.
        wwd.engine.globe.elevationModel.addCoverage(BasicElevationCoverage().apply {
            wwd.mainScope.launch(Dispatchers.IO) {
                try {
                    configureCache(File(cacheDir, "cache.gpkg").absolutePath, "SRTM")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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

    override fun onLowMemory() {
        super.onLowMemory()
        wwd.engine.renderResourceCache.trimStale()
    }
}