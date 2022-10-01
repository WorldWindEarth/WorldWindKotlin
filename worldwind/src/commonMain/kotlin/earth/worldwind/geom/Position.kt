package earth.worldwind.geom

import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Angle.Companion.fromRadians
import earth.worldwind.shape.PathType
import kotlin.jvm.JvmStatic

/**
 * Geographic position with a latitude and longitude and altitude in meters.
 */
open class Position(
    /**
     * The position's latitude.
     */
    latitude: Angle,
    /**
     * The position's longitude.
     */
    longitude: Angle,
    /**
     * The position's altitude in meters.
     */
    var altitude: Double
): Location(latitude, longitude) {
    /**
     * Constructs a position with latitude, longitude and altitude all 0.
     */
    constructor(): this(latitude = ZERO, longitude = ZERO, altitude = 0.0)

    /**
     * Constructs a position with the latitude, longitude and altitude of a specified position.
     *
     * @param position the position specifying the coordinates
     */
    constructor(position: Position): this(position.latitude, position.longitude, position.altitude)

    companion object {
        /**
         * Constructs a position with a specified latitude and longitude in degrees and altitude in meters.
         *
         * @param latitudeDegrees  the latitude in degrees
         * @param longitudeDegrees the longitude in degrees
         * @param altitude         the altitude in meters
         *
         * @return the new position
         */
        @JvmStatic
        fun fromDegrees(latitudeDegrees: Double, longitudeDegrees: Double, altitude: Double) =
            Position(fromDegrees(latitudeDegrees), fromDegrees(longitudeDegrees), altitude)

        /**
         * Constructs a position with a specified latitude and longitude in radians and altitude in meters.
         *
         * @param latitudeRadians  the latitude in radians
         * @param longitudeRadians the longitude in radians
         * @param altitude         the altitude in meters
         *
         * @return the new position
         */
        @JvmStatic
        fun fromRadians(latitudeRadians: Double, longitudeRadians: Double, altitude: Double) =
            Position(fromRadians(latitudeRadians), fromRadians(longitudeRadians), altitude)
    }

    /**
     * Sets this position to a specified latitude and longitude and altitude in meters.
     *
     * @param latitude  the new latitude
     * @param longitude the new longitude
     * @param altitude  the new altitude in meters
     *
     * @return this position with its latitude, longitude and altitude set to the specified values
     */
    fun set(latitude: Angle, longitude: Angle, altitude: Double) = apply {
        set(latitude, longitude)
        this.altitude = altitude
    }

    /**
     * Sets this position to a specified latitude and longitude in degrees and altitude in meters.
     *
     * @param latitudeDegrees  the new latitude in degrees
     * @param longitudeDegrees the new longitude in degrees
     * @param altitude         the new altitude in meters
     *
     * @return this position with its latitude, longitude and altitude set to the specified values
     */
    fun setDegrees(latitudeDegrees: Double, longitudeDegrees: Double, altitude: Double) = apply {
        setDegrees(latitudeDegrees, longitudeDegrees)
        this.altitude = altitude
    }

    /**
     * Sets this position to a specified latitude and longitude in radians and altitude in meters.
     *
     * @param latitudeRadians  the new latitude in radians
     * @param longitudeRadians the new longitude in radians
     * @param altitude         the new altitude in meters
     *
     * @return this position with its latitude, longitude and altitude set to the specified values
     */
    fun setRadians(latitudeRadians: Double, longitudeRadians: Double, altitude: Double) = apply {
        setRadians(latitudeRadians, longitudeRadians)
        this.altitude = altitude
    }

    /**
     * Sets this position to the latitude, longitude and altitude of a specified position.
     *
     * @param position the position specifying the new coordinates
     *
     * @return this position with its latitude, longitude and altitude set to that of the specified position
     */
    fun copy(position: Position) = set(position.latitude, position.longitude, position.altitude)

    /**
     * Compute a position along a path between two positions. The amount indicates the fraction of the path at which to
     * compute a position. This value is typically between 0 and 1, where 0 indicates the begin position (this position)
     * and 1 indicates the end position.
     *
     * @param endPosition the path's end position
     * @param pathType    [PathType] indicating type of path to assume
     * @param amount      the fraction of the path at which to compute a position
     * @param result      a pre-allocated Position in which to return the computed result
     *
     * @return the result argument set to the computed position
     */
    fun interpolateAlongPath(endPosition: Position, pathType: PathType, amount: Double, result: Position): Position {
        // Interpolate latitude and longitude.
        super.interpolateAlongPath(endPosition, pathType, amount, result)
        // Interpolate altitude.
        result.altitude = (1 - amount) * altitude + amount * endPosition.altitude
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Position) return false
        if (!super.equals(other)) return false
        return altitude == other.altitude
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + altitude.hashCode()
        return result
    }

    override fun toString() = "Position(latitude=$latitude, longitude=$longitude, altitude=$altitude)"
}