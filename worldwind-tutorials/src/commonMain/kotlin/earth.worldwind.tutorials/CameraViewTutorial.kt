package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.geom.Position
import kotlin.math.atan

class CameraViewTutorial(private val engine: WorldWind) : AbstractTutorial() {

    override fun start() {
        super.start()
        setCameraAction()
    }

    private fun setCameraAction() {
        // Create a view of Point Mugu airport as seen from an aircraft above Oxnard, CA.
        val aircraft = Position.fromDegrees(34.2, -119.2, 3000.0) // Above Oxnard CA, altitude in meters
        val airport = Position.fromDegrees(34.1192744, -119.1195850, 4.0) // KNTD airport, Point Mugu CA, altitude MSL

        // Compute heading and tilt angles from aircraft to airport
        val heading = aircraft.greatCircleAzimuth(airport)
        val distanceRadians = aircraft.greatCircleDistance(airport)
        val distanceMeters = distanceRadians * engine.globe.getRadiusAt(aircraft.latitude, aircraft.longitude)
        val tilt = atan(distanceMeters / aircraft.altitude).radians

        // Apply the camera view
        engine.camera.set(
            aircraft.latitude, aircraft.longitude, aircraft.altitude, AltitudeMode.ABSOLUTE, heading, tilt, roll = Angle.ZERO
        )
    }

}