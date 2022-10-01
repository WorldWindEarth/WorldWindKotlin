package earth.worldwind.tutorials

class WmsLayerFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a WMS Layer
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { WmsLayerTutorial(it.engine, it.mainScope).start() }
}