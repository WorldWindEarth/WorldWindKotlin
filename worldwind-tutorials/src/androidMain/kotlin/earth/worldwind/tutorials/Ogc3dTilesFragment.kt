package earth.worldwind.tutorials

import earth.worldwind.WorldWindow
import earth.worldwind.layer.cache.CachePolicy

class Ogc3dTilesFragment : BasicGlobeFragment() {
    private var tutorial: Ogc3dTilesTutorial? = null

    override fun createWorldWindow(): WorldWindow {
        val wwd = super.createWorldWindow()
        val ctx = requireContext().applicationContext
        Ogc3dTilesTutorial(
            wwd.engine,
            cacheProvider = { info ->
                val cm = TutorialContentManagerHolder.get(ctx)
                cm.openBlobStore(
                    contentKey = "ogc3d_tutorial",
                    evictionPolicy = CachePolicy(maxEntries = 32_000L),
                    displayName = "OGC 3D Tiles tutorial cache",
                ).also { cm.registerWebService("ogc3d_tutorial", info) }
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
