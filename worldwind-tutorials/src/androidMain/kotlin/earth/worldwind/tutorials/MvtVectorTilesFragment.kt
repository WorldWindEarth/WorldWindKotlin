package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope
import earth.worldwind.WorldWindow
import earth.worldwind.layer.cache.attachCache
import earth.worldwind.layer.mvt.MvtVectorLayer
import earth.worldwind.layer.mvt.OpenTopoMapRules
import earth.worldwind.layer.mvt.UrlTemplateMvtTileSource

class MvtVectorTilesFragment : BasicGlobeFragment() {
    private var tutorial: MvtVectorTilesTutorial? = null

    override fun createWorldWindow(): WorldWindow {
        val wwd = super.createWorldWindow()
        MvtVectorTilesTutorial(wwd.engine, lifecycleScope, layerLoader = {
            val layer = MvtVectorLayer(
                source = UrlTemplateMvtTileSource(MvtVectorTilesTutorial.URL_TEMPLATE),
                minZoom = MvtVectorTilesTutorial.MIN_ZOOM,
                maxZoom = MvtVectorTilesTutorial.MAX_ZOOM,
                tileRadius = MvtVectorTilesTutorial.TILE_RADIUS,
                style = OpenTopoMapRules,
            )
            contentManager.attachCache(layer, "MVT_Versatiles")
            layer
        }).also {
            tutorial = it
            it.start()
        }
        return wwd
    }

    override fun onDestroyView() {
        tutorial?.stop()
        tutorial = null
        super.onDestroyView()
    }
}
