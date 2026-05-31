package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope

class KmlFragment : BasicGlobeFragment() {
    override fun createWorldWindow() = super.createWorldWindow().also { wwd ->
        KmlTutorial(wwd.engine, lifecycleScope, density = resources.displayMetrics.density) {
            MR.assets.kml_sample_kml.readText(requireContext())
        }.start()
    }
}
