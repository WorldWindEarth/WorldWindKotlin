package earth.worldwind.tutorials

import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.fromRadians
import earth.worldwind.geom.Position.Companion.fromDegrees
import kotlin.math.atan

open class WcsElevationFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a WCS Elevation Coverage
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow(): WorldWindow {
        // Let the super class (BasicGlobeFragment) do the creation
        val wwd = super.createWorldWindow()

        // Create an elevation coverage from a version 1.0.0 WCS
        val coverage = buildWCSElevationCoverage()

        // Remove any existing coverages from the Globe
        wwd.engine.globe.elevationModel.clearCoverages()

        // Add the coverage to the Globe's elevation model
        wwd.engine.globe.elevationModel.addCoverage(coverage)

        // Position the camera to look at Mt. Rainier
        positionView(wwd)
        return wwd
    }

    protected open fun positionView(wwd: WorldWindow) {
        val mtRainier = fromDegrees(46.852886, -121.760374, 4392.0)
        val eye = fromDegrees(46.912, -121.527, 2000.0)

        // Compute heading and distance from peak to eye
        val heading = eye.greatCircleAzimuth(mtRainier)
        val distanceRadians = mtRainier.greatCircleDistance(eye)
        val distance = distanceRadians * wwd.engine.globe.getRadiusAt(mtRainier.latitude, mtRainier.longitude)

        // Compute camera settings
        val tilt = fromRadians(atan(distance / eye.altitude))

        // Apply the new view
        wwd.engine.camera.set(
            eye.latitude, eye.longitude, eye.altitude, AltitudeMode.ABSOLUTE, heading, tilt, roll = ZERO
        )
    }
}