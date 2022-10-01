package earth.worldwind.geom

import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Angle.Companion.fromRadians

open class LookAt(
    val position: Position, var altitudeMode: AltitudeMode, var range: Double, var heading: Angle, var tilt: Angle, var roll: Angle,
) {
    constructor(): this(
        position = Position(),
        altitudeMode = AltitudeMode.ABSOLUTE,
        range = 0.0,
        heading = ZERO,
        tilt = ZERO,
        roll = ZERO)

    constructor(lookAt: LookAt): this(
        lookAt.position,
        lookAt.altitudeMode,
        lookAt.range,
        lookAt.heading,
        lookAt.tilt,
        lookAt.roll
    )

    fun set(
        latitude: Angle, longitude: Angle, altitude: Double, altitudeMode: AltitudeMode,
        range: Double, heading: Angle, tilt: Angle, roll: Angle
    ) = apply {
        this.position.set(latitude, longitude, altitude)
        this.altitudeMode = altitudeMode
        this.range = range
        this.heading = heading
        this.tilt = tilt
        this.roll = roll
    }

    fun setDegrees(
        latitudeDegrees: Double, longitudeDegrees: Double, altitudeMeters: Double, altitudeMode: AltitudeMode,
        rangeMeters: Double, headingDegrees: Double, tiltDegrees: Double, rollDegrees: Double
    ) = set(
        fromDegrees(latitudeDegrees), fromDegrees(longitudeDegrees), altitudeMeters, altitudeMode, rangeMeters,
        fromDegrees(headingDegrees), fromDegrees(tiltDegrees), fromDegrees(rollDegrees)
    )

    fun setRadians(
        latitudeRadians: Double, longitudeRadians: Double, altitudeMeters: Double, altitudeMode: AltitudeMode,
        rangeMeters: Double, headingRadians: Double, tiltRadians: Double, rollRadians: Double
    ) = set(
        fromRadians(latitudeRadians), fromRadians(longitudeRadians), altitudeMeters, altitudeMode, rangeMeters,
        fromRadians(headingRadians), fromRadians(tiltRadians), fromRadians(rollRadians)
    )

    fun copy(lookAt: LookAt) = set(
        lookAt.position.latitude, lookAt.position.longitude, lookAt.position.altitude,
        lookAt.altitudeMode, lookAt.range, lookAt.heading, lookAt.tilt, lookAt.roll
    )

    override fun toString() = "LookAt(position=$position, altitudeMode=$altitudeMode, range=$range, heading=$heading, tilt=$tilt, roll=$roll)"
}