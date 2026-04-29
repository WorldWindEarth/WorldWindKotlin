package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope
import earth.worldwind.WorldWindow
import kotlinx.coroutines.launch

class GltfFragment : BasicGlobeFragment() {
    override fun createWorldWindow(): WorldWindow {
        val wwd = super.createWorldWindow()
        val tutorial = GltfTutorial(wwd.engine)
        tutorial.start()
        installDepthPickIndicator(tutorial.picker)
        lifecycleScope.launch {
            tutorial.setupScene()
            wwd.requestRedraw()
        }
        return wwd
    }
}
