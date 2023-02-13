package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope

class WmtsLayerFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a WMTS Layer
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { WmtsLayerTutorial(it.engine, lifecycleScope).start() }
}