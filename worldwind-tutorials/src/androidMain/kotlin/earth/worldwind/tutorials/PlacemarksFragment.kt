package earth.worldwind.tutorials

import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position.Companion.fromDegrees

open class PlacemarksFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow with a RenderableLayer populated with four Placemarks.
     */
    override fun createWorldWindow(): WorldWindow {
        // Let the super class (BasicGlobeFragment) do the creation
        val wwd = super.createWorldWindow()

        // Add Placemarks examples Layer
        wwd.engine.layers.addLayer(buildPlacemarksLayer())

        // And finally, for this demo, position the viewer to look at the airport placemark
        // from a tilted perspective when this Android activity is created.
        val lookAt = LookAt(
            position = fromDegrees(34.200, -119.208, 0.0),
            altitudeMode = AltitudeMode.ABSOLUTE,
            range = 1e5,
            heading = ZERO,
            tilt = fromDegrees(80.0),
            roll = ZERO
        )
        wwd.engine.cameraFromLookAt(lookAt)
        return wwd
    }
}