package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope
import earth.worldwind.layer.cache.attachCache
import earth.worldwind.ogc.WmtsLayerFactory

class WmtsLayerFragment : BasicGlobeFragment() {
    override fun createWorldWindow() = super.createWorldWindow().also {
        WmtsLayerTutorial(it.engine, lifecycleScope, layerLoader = {
            val contentKey = "WMTS_DlrHillshade"
            WmtsLayerFactory.createLayer(
                serviceAddress = WmtsLayerTutorial.SERVICE_ADDRESS,
                layerName = WmtsLayerTutorial.LAYER_NAME,
                serviceMetadata = contentManager.findEntry(contentKey)?.service?.metadata,
            ).also { layer -> contentManager.attachCache(layer, contentKey) }
        }).start()
    }
}
