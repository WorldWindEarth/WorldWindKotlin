package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import kotlin.math.atan
import kotlin.math.sqrt

class LookAtViewTutorial(private val engine: WorldWind) : AbstractTutorial() {

    override fun start() {
        super.start()
        setLookAtAction()
    }

    private fun setLookAtAction() {
        // Create a view of LAX airport as seen from an aircraft above Santa Monica, CA.
        val aircraft = Position.fromDegrees(34.0158333, -118.4513056, 2500.0)
        // Aircraft above Santa Monica airport, altitude in meters
        val airport = Position.fromDegrees(33.9424368, -118.4081222, 38.7)

        // Compute heading and distance from aircraft to airport
        val heading = aircraft.greatCircleAzimuth(airport)
        val distanceRadians = aircraft.greatCircleDistance(airport)
        val distanceMeters = distanceRadians * engine.globe.getRadiusAt(aircraft.latitude, aircraft.longitude)

        // Compute camera settings
        val altitude = aircraft.altitude - airport.altitude
        val range = sqrt(altitude * altitude + distanceMeters * distanceMeters)
        val tilt = atan(distanceMeters / aircraft.altitude).radians

        // Apply new "look at" view
        engine.cameraFromLookAt(LookAt(airport, AltitudeMode.ABSOLUTE, range, heading, tilt, roll = Angle.ZERO))
    }

}