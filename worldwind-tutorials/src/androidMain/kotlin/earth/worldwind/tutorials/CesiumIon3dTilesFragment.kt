package earth.worldwind.tutorials

import earth.worldwind.WorldWindow
import earth.worldwind.layer.cache.CachePolicy

class CesiumIon3dTilesFragment : BasicGlobeFragment() {
    private var tutorial: CesiumIon3dTilesTutorial? = null

    override fun createWorldWindow(): WorldWindow {
        val wwd = super.createWorldWindow()
        val ctx = requireContext().applicationContext
        CesiumIon3dTilesTutorial(
            wwd.engine,
            cacheProvider = { info ->
                val cm = TutorialContentManagerHolder.get(ctx)
                cm.openBlobStore(
                    contentKey = "cesium_ion_3dtiles_tutorial",
                    evictionPolicy = CachePolicy(maxEntries = 32_000L),
                    displayName = "Cesium Ion 3D Tiles tutorial cache",
                ).also { cm.registerWebService("cesium_ion_3dtiles_tutorial", info) }
            },
        ).also {
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
