package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope

class WmsLayerFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a WMS Layer
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { WmsLayerTutorial(it.engine, lifecycleScope).start() }
}