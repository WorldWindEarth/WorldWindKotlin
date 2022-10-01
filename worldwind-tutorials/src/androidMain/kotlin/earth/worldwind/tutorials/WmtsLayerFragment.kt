package earth.worldwind.tutorials

class WmtsLayerFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a WMTS Layer
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { WmtsLayerTutorial(it.engine, it.mainScope).start() }
}