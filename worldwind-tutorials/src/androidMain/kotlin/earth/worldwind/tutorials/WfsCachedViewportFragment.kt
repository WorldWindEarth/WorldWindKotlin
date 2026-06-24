package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope
import earth.worldwind.layer.BulkFeatureLayer
import earth.worldwind.layer.cache.attachCache
import earth.worldwind.ogc.wfs.WfsBulkFeatureSource

class WfsCachedViewportFragment : BasicGlobeFragment() {
    override fun createWorldWindow() = super.createWorldWindow().also {
        WfsAutoRefreshTutorial(it.engine, lifecycleScope, layerProvider = {
            // Strategy 1: download opengeo:countries once into the GeoPackage, then read each
            // viewport from its RTree index. attachCache wraps the source in a
            // CachedBulkFeatureSource; autoRefreshViewport drives the per-viewport reads.
            val layer = BulkFeatureLayer(
                source = WfsBulkFeatureSource(
                    serviceAddress = WfsAutoRefreshTutorial.SERVICE_ADDRESS,
                    layerName = WfsAutoRefreshTutorial.TYPE_NAME,
                ),
                displayName = WfsAutoRefreshTutorial.DISPLAY_NAME,
                customLogicToApplyProperties = WfsAutoRefreshTutorial.countryStyling,
            ).apply {
                isPickEnabled = false
                autoRefreshViewport = true
            }
            contentManager.attachCache(layer, "WFS_Countries")
            layer
        }).start()
    }
}
