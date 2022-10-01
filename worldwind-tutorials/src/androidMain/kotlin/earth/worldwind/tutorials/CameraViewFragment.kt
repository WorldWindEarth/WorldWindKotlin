package earth.worldwind.tutorials

class CameraViewFragment: BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow with its camera positioned at a given location and configured to point in a given
     * direction.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { CameraViewTutorial(wwd.engine).start() }
}