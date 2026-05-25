package earth.worldwind.examples

import android.os.Bundle
import android.widget.CheckBox
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import earth.worldwind.WorldWindow
import earth.worldwind.globe.elevation.coverage.BasicElevationCoverage
import earth.worldwind.globe.projection.MercatorProjection
import earth.worldwind.globe.projection.Wgs84Projection
import earth.worldwind.layer.BackgroundLayer
import earth.worldwind.layer.atmosphere.AtmosphereLayer
import earth.worldwind.layer.cache.attachCache
import earth.worldwind.layer.mercator.WebMercatorLayerFactory
import earth.worldwind.layer.shadow.ShadowLayer
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
        findViewById<FrameLayout>(R.id.globe).addView(wwd)

        // Change projection by toolbar checkbox
        findViewById<CheckBox>(R.id.is2d).setOnCheckedChangeListener { _, checked ->
            wwd.engine.globe.projection = if (checked) MercatorProjection() else Wgs84Projection()
            wwd.requestRedraw()
        }

        // Shared GeoPackage cache; the attach-cache helpers swap in the cached tileFactory /
        // elevationSourceFactory once the SQLite-backed stores open. The layers themselves
        // are added synchronously (network-only) so subclasses like BasicStressTestActivity
        // can clearLayers() / disable from their own onCreate without racing the cache.
        val contentManager = GpkgContentManager(File(cacheDir, "cache_content.gpkg").absolutePath)

        val satellite = WebMercatorLayerFactory.createLayer(
            urlTemplate = "https://mt.google.com/vt/lyrs=s&x={x}&y={y}&z={z}&hl={lang}",
            imageFormat = "image/jpeg",
            name = "Google Satellite",
        )
        val elevation = BasicElevationCoverage()
        wwd.engine.layers.apply {
            addLayer(BackgroundLayer())
            addLayer(satellite)
            addLayer(StarFieldLayer())
            addLayer(AtmosphereLayer())
            addLayer(ShadowLayer())
        }
        wwd.engine.globe.elevationModel.addCoverage(elevation)

        // Bind the persistent cache after the layers are in place. Safe under subclass
        // clearLayers() — the attach simply mutates layer.tiledSurfaceImage.tileFactory /
        // elevationSourceFactory, which is harmless even if the layer / coverage was
        // already removed from the engine.
        lifecycleScope.launch {
            contentManager.attachCache(satellite, "GSat")
            contentManager.attachCache(elevation, BasicElevationCoverage.COVERAGE_NAME)
        }
    }

    override fun onStop() {
        super.onStop()
        wwd.onPause() // pauses the rendering thread
    }

    override fun onStart() {
        super.onStart()
        wwd.onResume() // resumes a paused rendering thread
    }
}
