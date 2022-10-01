package earth.worldwind.tutorials

import earth.worldwind.WorldWindow
import earth.worldwind.geom.Angle.Companion.fromDegrees

open class PolygonsFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a set of Polygon shapes
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow(): WorldWindow {
        // Let the super class (BasicGlobeFragment) do the creation
        val wwd = super.createWorldWindow()

        // Add Polygons examples Layer
        wwd.engine.layers.addLayer(buildPolygonsLayer())

        // Set Camera position to suitable location
        wwd.engine.camera.position.apply {
            latitude = fromDegrees(30.0)
            longitude = fromDegrees(-115.0)
        }
        return wwd
    }
}