package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope

class GeoJsonFragment : BasicGlobeFragment() {
    override fun createWorldWindow() = super.createWorldWindow().also { wwd ->
        GeoJsonTutorial(wwd.engine, lifecycleScope) {
            MR.assets.geojson_sample_json.readText(requireContext())
        }.start()
    }
}
