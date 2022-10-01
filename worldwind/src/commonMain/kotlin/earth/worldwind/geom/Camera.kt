package earth.worldwind.geom

import earth.worldwind.geom.AltitudeMode.ABSOLUTE
import earth.worldwind.geom.Angle.Companion.POS180
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Angle.Companion.fromRadians
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.jvm.JvmOverloads

open class Camera {
    val position = Position()
    var altitudeMode = ABSOLUTE
    var heading = ZERO
    var tilt = ZERO
    var roll = ZERO
    var fieldOfView = fromDegrees(45.0)
        set(value) {
            require(value > ZERO && value < POS180) {
                logMessage(ERROR, "Camera", "setFieldOfView", "invalidFieldOfView")
            }
            field = value
        }

    @JvmOverloads
    fun set(
        latitude: Angle, longitude: Angle, altitude: Double, altitudeMode: AltitudeMode,
        heading: Angle, tilt: Angle, roll: Angle, fieldOfView: Angle = this.fieldOfView
    ) = apply {
        this.position.set(latitude, longitude, altitude)
        this.altitudeMode = altitudeMode
        this.heading = heading
        this.tilt = tilt
        this.roll = roll
        this.fieldOfView = fieldOfView
    }

    @JvmOverloads
    fun setDegrees(
        latitudeDegrees: Double, longitudeDegrees: Double, altitudeMeters: Double, altitudeMode: AltitudeMode,
        headingDegrees: Double, tiltDegrees: Double, rollDegrees: Double, fieldOfViewDegrees: Double = this.fieldOfView.degrees
    ) = set(
        fromDegrees(latitudeDegrees), fromDegrees(longitudeDegrees), altitudeMeters, altitudeMode,
        fromDegrees(headingDegrees), fromDegrees(tiltDegrees), fromDegrees(rollDegrees), fromDegrees(fieldOfViewDegrees)
    )

    @JvmOverloads
    fun setRadians(
        latitudeRadians: Double, longitudeRadians: Double, altitudeMeters: Double, altitudeMode: AltitudeMode,
        headingRadians: Double, tiltRadians: Double, rollRadians: Double, fieldOfViewRadians: Double = this.fieldOfView.radians
    ) = set(
        fromRadians(latitudeRadians), fromRadians(longitudeRadians), altitudeMeters, altitudeMode,
        fromRadians(headingRadians), fromRadians(tiltRadians), fromRadians(rollRadians), fromRadians(fieldOfViewRadians)
    )

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