package earth.worldwind.tutorials

class EllipsoidsFragment: BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a set of Ellipsoid shapes.
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow() = super.createWorldWindow().also {
        EllipsoidsTutorial(it.engine).start()
    }
}
