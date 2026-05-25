package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope
import earth.worldwind.layer.cache.attachCache
import earth.worldwind.ogc.wfs.WfsBulkFeatureSource
import earth.worldwind.layer.BulkFeatureLayer

class WfsLayerFragment : BasicGlobeFragment() {
    override fun createWorldWindow() = super.createWorldWindow().also {
        WfsLayerTutorial(it.engine, lifecycleScope, layerLoader = {
            val layer = BulkFeatureLayer(
                source = WfsBulkFeatureSource(
                    serviceAddress = WfsLayerTutorial.SERVICE_ADDRESS,
                    layerName = WfsLayerTutorial.TYPE_NAME,
                    maxFeatures = WfsLayerTutorial.MAX_FEATURES,
                    pageSize = WfsLayerTutorial.PAGE_SIZE,
                ),
                displayName = WfsLayerTutorial.DISPLAY_NAME,
                customLogicToApplyProperties = WfsLayerTutorial.populationStyling,
            )
            // Uniform attachCache: wraps source in CachedBulkFeatureSource + registers
            // a "WFS" web-service row so listEntries() can rediscover next launch.
            contentManager.attachCache(layer, "WFS_Cities")
            layer
        }).start()
    }
}
