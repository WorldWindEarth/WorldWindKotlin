package earth.worldwind.tutorials

import earth.worldwind.WorldWindow
import earth.worldwind.geom.Angle.Companion.fromDegrees

open class PathsFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a set of Path shapes
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow(): WorldWindow {
        // Let the super class (BasicGlobeFragment) do the creation
        val wwd = super.createWorldWindow()

        // Add Paths examples Layer
        wwd.engine.layers.addLayer(buildPathsLayer())

        // Set Camera position to suitable location
        wwd.engine.camera.position.apply {
            latitude = fromDegrees(30.0)
            longitude = fromDegrees(-100.0)
        }
        return wwd
    }
}