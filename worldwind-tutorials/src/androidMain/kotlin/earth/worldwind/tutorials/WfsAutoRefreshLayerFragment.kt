package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope

class WfsAutoRefreshLayerFragment : BasicGlobeFragment() {
    override fun createWorldWindow() = super.createWorldWindow().also {
        // WfsLayer auto-refresh is viewport-driven and built entirely in commonMain, so unlike
        // WfsLayerFragment there's no platform-specific layerLoader or cache attach here.
        WfsAutoRefreshTutorial(it.engine, lifecycleScope).start()
    }
}
