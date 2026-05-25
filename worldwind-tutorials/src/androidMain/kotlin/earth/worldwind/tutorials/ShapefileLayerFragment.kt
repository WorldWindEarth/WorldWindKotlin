package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope
import earth.worldwind.formats.shapefile.ShapefileBulkFeatureSource
import earth.worldwind.layer.cache.attachCache
import earth.worldwind.layer.BulkFeatureLayer

class ShapefileLayerFragment : BasicGlobeFragment() {
    override fun createWorldWindow() = super.createWorldWindow().also { wwd ->
        ShapefileLayerTutorial(wwd.engine, lifecycleScope, layerLoader = {
            val layer = BulkFeatureLayer(
                source = ShapefileBulkFeatureSource(ShapefileLayerTutorial.SHP_URL),
                displayName = ShapefileLayerTutorial.DISPLAY_NAME,
                shapeAttributes = ShapefileLayerTutorial.defaultPolygonStyle(),
            ).also { it.isPickEnabled = false }
            contentManager.attachCache(layer, "Shapefile_Countries")
            layer
        }).start()
    }
}
