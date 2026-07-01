package earth.worldwind.tutorials

import earth.worldwind.WorldWindow

class PointCloudFragment : BasicGlobeFragment() {
    private var tutorial: PointCloudTutorial? = null

    override fun createWorldWindow(): WorldWindow {
        val wwd = super.createWorldWindow()
        PointCloudTutorial(wwd.engine).also {
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
