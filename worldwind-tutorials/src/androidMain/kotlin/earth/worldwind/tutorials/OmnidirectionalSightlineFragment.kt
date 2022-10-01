package earth.worldwind.tutorials

import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.LookAt

open class OmnidirectionalSightlineFragment: BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with an OmnidirectionalSightline
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow(): WorldWindow {
        // Let the super class (BasicGlobeFragment) do the creation
        val wwd = super.createWorldWindow()

        // Add omnidirectional sight line Layer
        wwd.engine.layers.addLayer(buildSightLineLayer())

        // Position the camera to look at the line of site terrain coverage
        val lookAt = LookAt().setDegrees(
            latitudeDegrees = 46.230,
            longitudeDegrees = -122.190,
            altitudeMeters = 500.0,
            altitudeMode = AltitudeMode.ABSOLUTE,
            rangeMeters = 1.5e4,
            headingDegrees = 45.0,
            tiltDegrees = 70.0,
            rollDegrees = 0.0
        )
        wwd.engine.cameraFromLookAt(lookAt)
        return wwd
    }
}