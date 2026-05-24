package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope
import earth.worldwind.formats.shapefile.CachedShapefileLayer
import earth.worldwind.layer.configureCache

class ShapefileLayerFragment : BasicGlobeFragment() {
    override fun createWorldWindow() = super.createWorldWindow().also { wwd ->
        val loader: suspend () -> earth.worldwind.layer.RenderableLayer = {
            CachedShapefileLayer(
                shpUrl = ShapefileLayerTutorial.SHP_URL,
                displayName = ShapefileLayerTutorial.DISPLAY_NAME,
                attributes = ShapefileLayerTutorial.defaultPolygonStyle(),
            ).also { layer ->
                layer.configureCache(contentManager, "Shapefile_Countries")
                layer.load()
            }
        }
        ShapefileLayerTutorial(wwd.engine, lifecycleScope, layerLoader = loader).start()
    }
}
