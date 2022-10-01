package earth.worldwind.tutorials

class OmnidirectionalSightlineFragment: BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with an OmnidirectionalSightline
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { SightlineTutorial(it.engine).start() }
}