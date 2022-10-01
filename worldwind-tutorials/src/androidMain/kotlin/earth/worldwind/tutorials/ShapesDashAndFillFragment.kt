package earth.worldwind.tutorials

import earth.worldwind.WorldWindow
import earth.worldwind.geom.Angle.Companion.fromDegrees

open class ShapesDashAndFillFragment: BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a set of Path and Polygon shapes with dashed lines and
     * repeating fill.
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow(): WorldWindow {
        // Let the super class (BasicGlobeFragment) do the creation
        val wwd = super.createWorldWindow()

        // Add dash and fill Layer
        wwd.engine.layers.addLayer(buildDashAndFillLayer())

        // Set Camera position to suitable location
        wwd.engine.camera.position.apply {
            latitude = fromDegrees(30.0)
            longitude = fromDegrees(-85.0)
        }
        return wwd
    }
}