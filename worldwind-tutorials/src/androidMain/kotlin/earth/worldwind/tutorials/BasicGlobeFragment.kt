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
import earth.worldwind.layer.mercator.MercatorLayerFactory
import earth.worldwind.layer.starfield.StarFieldLayer
import earth.worldwind.ogc.GpkgContentManager
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
        val contentManager = GpkgContentManager(File(requireContext().cacheDir, "content.gpkg").absolutePath)
        // Setting up the WorldWindow's layers.
        wwd.engine.layers.apply {
            addLayer(BackgroundLayer())
            addLayer(MercatorLayerFactory.createLayer(
                name = "Google Satellite",
                urlTemplate = "https://mt.google.com/vt/lyrs=s&x={x}&y={y}&z={z}&hl={lang}",
                imageFormat = "image/jpeg"
            ).apply {
                wwd.mainScope.launch { configureCache(contentManager, "GSat") }
            })
            addLayer(StarFieldLayer())
            addLayer(AtmosphereLayer())
        }
        // Setting up the WorldWindow's elevation coverages.
        wwd.engine.globe.elevationModel.addCoverage(BasicElevationCoverage().apply {
            wwd.mainScope.launch { configureCache(contentManager, "NASADEM") }
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