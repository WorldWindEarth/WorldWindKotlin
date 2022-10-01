package earth.worldwind.tutorials

import earth.worldwind.WorldWindow
import earth.worldwind.geom.Angle.Companion.fromDegrees

open class EllipsesFragment: BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a set of Ellipse shapes
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow(): WorldWindow {
        // Let the super class (BasicGlobeFragment) do the creation
        val wwd = super.createWorldWindow()

        // Add Ellipse examples Layer
        wwd.engine.layers.addLayer(buildEllipsesLayer())

        // Set Camera position to suitable location
        wwd.engine.camera.position.apply {
            latitude = fromDegrees(30.0)
            longitude = fromDegrees(-110.0)
        }

        return wwd
    }
}