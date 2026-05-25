package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope
import earth.worldwind.WorldWindow
import earth.worldwind.layer.buildings.OsmBuildingsLayer
import earth.worldwind.layer.cache.attachCache

class OsmBuildingsFragment : BasicGlobeFragment() {
    private var tutorial: OsmBuildingsTutorial? = null

    override fun createWorldWindow(): WorldWindow {
        val wwd = super.createWorldWindow()
        OsmBuildingsTutorial(wwd.engine, lifecycleScope, layerLoader = {
            OsmBuildingsLayer(useOsmColors = true).also {
                contentManager.attachCache(it, "OsmBuildings")
            }
        }).also {
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
