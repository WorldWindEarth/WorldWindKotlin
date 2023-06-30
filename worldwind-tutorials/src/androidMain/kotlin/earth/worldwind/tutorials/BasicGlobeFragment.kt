package earth.worldwind.tutorials

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import earth.worldwind.WorldWindow
import earth.worldwind.globe.elevation.coverage.BasicElevationCoverage
import earth.worldwind.layer.BackgroundLayer
import earth.worldwind.layer.atmosphere.AtmosphereLayer
import earth.worldwind.layer.mercator.google.GoogleLayer
import earth.worldwind.layer.starfield.StarFieldLayer
import kotlinx.coroutines.Dispatchers
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
        // Setting up the WorldWindow's layers.
        wwd.engine.layers.apply {
            addLayer(BackgroundLayer())
            addLayer(GoogleLayer(GoogleLayer.Type.SATELLITE).apply {
                wwd.mainScope.launch(Dispatchers.IO) {
                    try {
                        configureCache(File(requireContext().cacheDir, "cache.gpkg").absolutePath, "GSat")
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
                    configureCache(File(requireContext().cacheDir, "cache.gpkg").absolutePath, "SRTM")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        })
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
     * Resumes the WorldWindow's rendering thread
     */
    override fun onResume() {
        super.onResume()
        wwd.onResume() // resumes a paused rendering thread
    }

    /**
     * Pauses the WorldWindow's rendering thread
     */
    override fun onPause() {
        super.onPause()
        wwd.onPause() // pauses the rendering thread
    }
}