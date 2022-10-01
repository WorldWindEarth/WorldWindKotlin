package earth.worldwind.tutorials

import earth.worldwind.WorldWindow

open class SurfaceImageFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow with an additional RenderableLayer containing two SurfaceImages.
     */
    override fun createWorldWindow(): WorldWindow {
        // Let the super class (BasicGlobeFragment) do the creation
        val wwd = super.createWorldWindow()

        // Add Surface Image Layer
        wwd.engine.layers.addLayer(buildSurfaceImageLayer())

        // Position the viewer so that the Surface Images are visible when the activity is created.
        wwd.engine.camera.position.setDegrees(37.46543388598137, 14.97980511744455, 4.0e5)
        return wwd
    }
}