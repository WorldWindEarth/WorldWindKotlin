package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.formats.gpkg.GpkgContentManager
import earth.worldwind.geom.Angle
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.layer.Layer
import earth.worldwind.layer.cache.CacheCategory
import earth.worldwind.layer.cache.setOfflineOnly
import earth.worldwind.util.Logger
import earth.worldwind.util.open
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Opens the bundled tutorial GeoPackage (Naval Air Station Oceana, VA) read-only and
 * pulls every cached layer / coverage through the typed bulk-open helpers, adding them
 * to the engine.
 *
 * @param gpkgPathLoader stages the moko-resource to a filesystem path the SQLite driver
 *   can open. JVM/Android both ship the asset via `MR.assets.geopackage_tutorial_gpkg`
 *   but extract it through platform-specific channels (classpath stream + temp file on
 *   JVM, `assets.open` + cache dir on Android).
 */
class GeoPackageTutorial(
    engine: WorldWind,
    private val scope: CoroutineScope,
    private val gpkgPathLoader: suspend () -> String,
) : AbstractTutorial(engine) {

    private val addedLayers = mutableListOf<Layer>()
    private val addedCoverages = mutableListOf<TiledElevationCoverage>()
    private var contentManager: GpkgContentManager? = null
    private var job: Job? = null

    override fun start() {
        super.start()
        engine.camera.apply {
            position.setDegrees(36.8139677556754, -76.03260320181614, 10e3)
            heading = Angle.ZERO
            tilt = Angle.ZERO
            roll = Angle.ZERO
        }
        job = scope.launch {
            try {
                val gpkgPath = gpkgPathLoader()
                // Bundled GPKG has no live server twin — flip each layer/coverage into strict
                // cache-only mode so misses don't trigger network.
                val cm = GpkgContentManager(gpkgPath, isReadOnly = true)
                if (!isActive) { cm.close(); return@launch }
                contentManager = cm
                val tiledImages = cm.open(CacheCategory.Tiles).onEach { it.setOfflineOnly() }
                val vectorTiles = cm.open(CacheCategory.VectorTiles).onEach { it.setOfflineOnly() }
                val features = cm.open(CacheCategory.Features).onEach { it.setOfflineOnly() }
                val coverages = cm.open(CacheCategory.Coverage).onEach { it.setOfflineOnly() }
                if (!isActive) return@launch
                listOf(tiledImages, vectorTiles, features).forEach { group ->
                    group.forEach { layer ->
                        engine.layers.addLayer(layer)
                        addedLayers += layer
                    }
                }
                coverages.forEach { coverage ->
                    engine.globe.elevationModel.addCoverage(coverage)
                    addedCoverages += coverage
                }
                WorldWind.requestRedraw()
                Logger.log(
                    Logger.INFO,
                    "Opened ${addedLayers.size} layer(s) and ${addedCoverages.size} coverage(s) " +
                            "from tutorial GeoPackage",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.log(Logger.ERROR, "GeoPackage layer creation failed", e)
            }
        }
    }

    override fun stop() {
        super.stop()
        job?.cancel()
        job = null
        addedLayers.forEach { engine.layers.removeLayer(it) }
        addedLayers.clear()
        addedCoverages.forEach { engine.globe.elevationModel.removeCoverage(it) }
        addedCoverages.clear()
        contentManager?.let { cm ->
            scope.launch { runCatching { cm.close() } }
        }
        contentManager = null
    }
}
