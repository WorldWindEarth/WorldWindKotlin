package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope
import earth.worldwind.WorldWindow
import earth.worldwind.layer.buildings.CachedOsmBuildingsLayer
import earth.worldwind.layer.configureCache
import kotlinx.coroutines.launch

class OsmBuildingsFragment : BasicGlobeFragment() {
    private var tutorial: OsmBuildingsTutorial? = null

    override fun createWorldWindow(): WorldWindow {
        val wwd = super.createWorldWindow()
        val factory: () -> CachedOsmBuildingsLayer = {
            CachedOsmBuildingsLayer(useOsmColors = true).also { layer ->
                lifecycleScope.launch { layer.configureCache(contentManager, "OsmBuildings") }
            }
        }
        OsmBuildingsTutorial(wwd.engine, layerFactory = factory).also {
            tutorial = it
            it.start()
        }
        return wwd
    }

    override fun onDestroyView() {
        tutorial?.stop()
        tutorial = null
        super.onDestroyView()
    }
}
