package earth.worldwind.tutorials

class ElevationHeatmapFragment: BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with an Elevation Heatmap
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { ElevationHeatmapTutorial(it.engine).start() }
}
