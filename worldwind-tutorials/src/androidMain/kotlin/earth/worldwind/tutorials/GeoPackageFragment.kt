package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope
import earth.worldwind.WorldWindow
import earth.worldwind.layer.cache.CacheCategory
import earth.worldwind.layer.cache.setOfflineOnly
import earth.worldwind.ogc.GpkgContentManager
import earth.worldwind.util.Logger
import earth.worldwind.util.open
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GeoPackageFragment: BasicGlobeFragment() {
    override fun createWorldWindow(): WorldWindow {
        val wwd = super.createWorldWindow()
        lifecycleScope.launch {
            try {
                // Unpack the tutorial GeoPackage asset to the Android app cache; the
                // GeoPackage SQLite driver only works on filesystem-backed files.
                val gpkgPath = withContext(Dispatchers.IO) {
                    TutorialUtil.unpackAsset(requireContext(), "geopackage_tutorial.gpkg").path
                }
                // Open the bundled GPKG read-only and pull every cached layer / coverage
                // through the typed bulk-open helpers. Bundled GPKG has no live server twin —
                // flip each into strict cache-only mode so cache misses don't trigger network.
                val tutorialGpkg = GpkgContentManager(gpkgPath, isReadOnly = true)
                val tiledImages = tutorialGpkg.open(CacheCategory.Tiles).onEach { it.setOfflineOnly() }
                val vectorTiles = tutorialGpkg.open(CacheCategory.VectorTiles).onEach { it.setOfflineOnly() }
                val features = tutorialGpkg.open(CacheCategory.Features).onEach { it.setOfflineOnly() }
                val coverages = tutorialGpkg.open(CacheCategory.Coverage).onEach { it.setOfflineOnly() }
                tiledImages.forEach { wwd.engine.layers.addLayer(it) }
                vectorTiles.forEach { wwd.engine.layers.addLayer(it) }
                features.forEach { wwd.engine.layers.addLayer(it) }
                coverages.forEach { wwd.engine.globe.elevationModel.addCoverage(it) }
                wwd.requestRedraw()
                val totalLayers = tiledImages.size + vectorTiles.size + features.size
                Logger.log(Logger.INFO,
                    "Opened $totalLayers layer(s) and ${coverages.size} coverage(s) from tutorial GeoPackage")
            } catch (e: Throwable) {
                Logger.log(Logger.ERROR, "GeoPackage layer creation failed", e)
            }
        }
        // Centre the camera over the bundled GPKG's sample area (Naval Air Station Oceana, VA).
        wwd.engine.camera.position.setDegrees(36.8139677556754, -76.03260320181614, 10e3)
        return wwd
    }
}
