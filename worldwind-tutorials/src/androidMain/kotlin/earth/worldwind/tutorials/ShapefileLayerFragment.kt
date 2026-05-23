package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope

class ShapefileLayerFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object loaded with an ESRI shapefile.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { ShapefileLayerTutorial(it.engine, lifecycleScope).start() }
}
