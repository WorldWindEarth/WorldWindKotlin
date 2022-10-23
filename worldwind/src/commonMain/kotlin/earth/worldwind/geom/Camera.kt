package earth.worldwind.geom

import earth.worldwind.geom.AltitudeMode.ABSOLUTE
import earth.worldwind.geom.Angle.Companion.POS180
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage

open class Camera {
    val position = Position()
    var altitudeMode = ABSOLUTE
    var heading = ZERO
    var tilt = ZERO
    var roll = ZERO
    var fieldOfView = 45.0.degrees
        set(value) {
            require(value > ZERO && value < POS180) {
                logMessage(ERROR, "Camera", "setFieldOfView", "invalidFieldOfView")
            }
            field = value
        }

    fun set(
        latitude: Angle, longitude: Angle, altitude: Double, altitudeMode: AltitudeMode,
        heading: Angle, tilt: Angle, roll: Angle, fieldOfView: Angle
    ) = set(latitude, longitude, altitude, altitudeMode, heading, tilt, roll).apply { this.fieldOfView = fieldOfView }

    fun set(
        latitude: Angle, longitude: Angle, altitude: Double, altitudeMode: AltitudeMode,
        heading: Angle, tilt: Angle, roll: Angle
    ) = apply {
        this.position.set(latitude, longitude, altitude)
        this.altitudeMode = altitudeMode
        this.heading = heading
        this.tilt = tilt
        this.roll = roll
    }

    fun copy(camera: Camera) = set(
        camera.position.latitude,
        camera.position.longitude,
        camera.position.altitude,
        camera.altitudeMode,
        camera.heading,
        camera.tilt,
        camera.roll,
        camera.fieldOfView
    )

    override fun toString() = "Camera(position=$position, altitudeMode=$altitudeMode, heading=$heading, tilt=$tilt, roll=$roll, fieldOfView=$fieldOfView)"
}