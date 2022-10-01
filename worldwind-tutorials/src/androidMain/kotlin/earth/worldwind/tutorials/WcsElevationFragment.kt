package earth.worldwind.tutorials

class WcsElevationFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a WCS Elevation Coverage
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { WcsElevationTutorial(it.engine).start() }
}