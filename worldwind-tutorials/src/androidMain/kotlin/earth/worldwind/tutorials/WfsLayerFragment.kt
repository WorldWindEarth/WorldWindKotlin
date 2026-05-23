package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope

class WfsLayerFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a WFS Layer
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { WfsLayerTutorial(it.engine, lifecycleScope).start() }
}
