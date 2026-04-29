package earth.worldwind.tutorials

class PolygonsFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a set of Polygon shapes
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow() = super.createWorldWindow().also {
        val tutorial = PolygonsTutorial(it.engine)
        tutorial.start()
        installDepthPickIndicator(tutorial.picker)
    }
}