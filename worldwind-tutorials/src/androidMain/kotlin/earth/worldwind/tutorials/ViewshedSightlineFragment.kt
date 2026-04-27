package earth.worldwind.tutorials

class ViewshedSightlineFragment: BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a ViewshedSightline.
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { ViewshedSightlineTutorial(it.engine).start() }
}
