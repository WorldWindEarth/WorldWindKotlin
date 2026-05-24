package earth.worldwind.tutorials

import earth.worldwind.WorldWindow

class MvtVectorTilesFragment : BasicGlobeFragment() {
    private var tutorial: MvtVectorTilesTutorial? = null

    override fun createWorldWindow(): WorldWindow {
        val wwd = super.createWorldWindow()
        MvtVectorTilesTutorial(wwd.engine).also {
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
