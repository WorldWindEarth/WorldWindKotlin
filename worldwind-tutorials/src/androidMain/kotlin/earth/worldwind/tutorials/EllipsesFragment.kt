package earth.worldwind.tutorials

class EllipsesFragment: BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a set of Ellipse shapes
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { EllipsesTutorial(it.engine).start() }
}