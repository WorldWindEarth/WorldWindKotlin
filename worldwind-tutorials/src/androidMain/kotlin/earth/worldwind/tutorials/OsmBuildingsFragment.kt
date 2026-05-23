package earth.worldwind.tutorials

import earth.worldwind.WorldWindow

class OsmBuildingsFragment : BasicGlobeFragment() {
    private var tutorial: OsmBuildingsTutorial? = null

    override fun createWorldWindow(): WorldWindow {
        val wwd = super.createWorldWindow()
        OsmBuildingsTutorial(wwd.engine).also {
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
