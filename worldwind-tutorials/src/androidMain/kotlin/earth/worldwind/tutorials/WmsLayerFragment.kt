package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope
import earth.worldwind.layer.cache.attachCache
import earth.worldwind.ogc.WmsLayerFactory

class WmsLayerFragment : BasicGlobeFragment() {
    override fun createWorldWindow() = super.createWorldWindow().also {
        WmsLayerTutorial(it.engine, lifecycleScope, layerLoader = {
            val contentKey = "WMS_NeoTemperature"
            // Use any previously-persisted capabilities XML; otherwise hit GetCapabilities.
            WmsLayerFactory.createLayer(
                serviceAddress = WmsLayerTutorial.SERVICE_ADDRESS,
                layerNames = WmsLayerTutorial.LAYER_NAMES,
                serviceMetadata = contentManager.findEntry(contentKey)?.service?.metadata,
            ).also { layer -> contentManager.attachCache(layer, contentKey) }
        }).start()
    }
}
