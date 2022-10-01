package earth.worldwind.geom

import earth.worldwind.geom.Angle.Companion.NEG180
import earth.worldwind.geom.Angle.Companion.NEG90
import earth.worldwind.geom.Angle.Companion.POS180
import earth.worldwind.geom.Angle.Companion.POS90
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.average
import earth.worldwind.geom.Angle.Companion.clampLatitude
import earth.worldwind.geom.Angle.Companion.clampLongitude
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Angle.Companion.fromRadians
import earth.worldwind.geom.Angle.Companion.max
import earth.worldwind.geom.Angle.Companion.min
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.jvm.JvmStatic

/**
 * Geographic rectangular region.
 */
open class Sector(
    /**
     * The sector's minimum latitude.
     */
    var minLatitude: Angle,
    /**
     * The sector's maximum latitude.
     */
    var maxLatitude: Angle,
    /**
     * The sector's minimum longitude.
     */
    var minLongitude: Angle,
    /**
     * The sector's maximum longitude.
     */
    var maxLongitude: Angle
) {
    /**
     * Indicates whether this sector has no dimensions.
     */
    val isEmpty get() = minLatitude == ZERO && maxLatitude == ZERO && minLongitude == ZERO && maxLongitude == ZERO
    /**
     * Indicates whether this sector contains the full range of latitude [90 to +90] and longitude [-180 to +180].
     */
    val isFullSphere get() = minLatitude == NEG90 && maxLatitude == POS90 && minLongitude == NEG180 && maxLongitude == POS180
    /**
     * Returns the angle between this sector's minimum and maximum latitudes.
     */
    val deltaLatitude get() = maxLatitude - minLatitude
    /**
     * Returns the angle between this sector's minimum and maximum longitudes.
     */
    val deltaLongitude get() = maxLongitude - minLongitude
    /**
     * Returns the angle midway between this sector's minimum and maximum latitudes.
     */
    val centroidLatitude get() = average(minLatitude, maxLatitude)
    /**
     * Returns the angle midway between this sector's minimum and maximum longitudes.
     */
    val centroidLongitude get() = average(minLongitude, maxLongitude)

    /**
     * Constructs an empty sector with minimum and maximum latitudes and longitudes all 0.
     */
    constructor(): this(minLatitude = ZERO, maxLatitude = ZERO, minLongitude = ZERO, maxLongitude = ZERO)

    /**
     * Constructs a sector with the minimum and maximum latitudes and longitudes of a specified sector.
     *
     * @param sector the sector specifying the coordinates
     */
    constructor(sector: Sector): this(sector.minLatitude, sector.maxLatitude, sector.minLongitude, sector.maxLongitude)

    companion object {
        @JvmStatic
        fun fromDegrees(minLatDegrees: Double, minLonDegrees: Double, deltaLatDegrees: Double, deltaLonDegrees: Double): Sector {
            val maxLatDegrees = if (deltaLatDegrees > 0)
                clampLatitude(minLatDegrees + deltaLatDegrees) else minLatDegrees
            val maxLonDegrees = if (deltaLonDegrees > 0)
                clampLongitude(minLonDegrees + deltaLonDegrees) else minLonDegrees
            return Sector(
                fromDegrees(minLatDegrees), fromDegrees(maxLatDegrees),
                fromDegrees(minLonDegrees), fromDegrees(maxLonDegrees)
            )
        }

        @JvmStatic
        fun fromRadians(minLatRadians: Double, minLonRadians: Double, deltaLatRadians: Double, deltaLonRadians: Double): Sector {
            val maxLatRadians = if (deltaLatRadians > 0)
                clampLatitude(minLatRadians + deltaLatRadians) else minLatRadians
            val maxLonRadians = if (deltaLonRadians > 0)
                clampLongitude(minLonRadians + deltaLonRadians) else minLonRadians
            return Sector(
                fromRadians(minLatRadians), fromRadians(maxLatRadians),
                fromRadians(minLonRadians), fromRadians(maxLonRadians)
            )
        }
    }

    /**
     * Computes the location of the angular center of this sector, which is the mid-angle of each of this sector's
     * latitude and longitude dimensions.
     *
     * @param result a pre-allocated [Location] in which to return the computed centroid
     *
     * @return the specified result argument containing the computed centroid
     */
    fun centroid(result: Location): Location {
        result.latitude = centroidLatitude
        result.longitude = centroidLongitude
        return result
    }

    /**
     * Sets this sector to the specified latitude, longitude and dimension.
     *
     * @param minLatitude    the minimum latitude, i.e., the latitude at the southwest corner of the sector.
     * @param minLongitude   the minimum longitude, i.e., the longitude at the southwest corner of the sector.
     * @param deltaLatitude  the width of the sector; must equal to or greater than zero.
     * @param deltaLongitude the height of the sector; must equal to or greater than zero.
     *
     * @return this sector with its coordinates set to the specified values
     */
    fun set(minLatitude: Angle, minLongitude: Angle, deltaLatitude: Angle, deltaLongitude: Angle) = apply {
        this.minLatitude = minLatitude
        this.minLongitude = minLongitude
        maxLatitude = if (deltaLatitude > ZERO) (minLatitude + deltaLatitude).clampLatitude() else minLatitude
        maxLongitude = if (deltaLongitude > ZERO) (minLongitude + deltaLongitude).clampLongitude() else minLongitude
    }

    /**
     * Sets this sector to the specified latitude, longitude and dimension in degrees.
     *
     * @param minLatitude    the minimum latitude in degrees, i.e., the latitude at the southwest corner of the sector.
     * @param minLongitude   the minimum longitude in degrees, i.e., the longitude at the southwest corner of the sector.
     * @param deltaLatitude  the width of the sector in degrees; must equal to or greater than zero.
     * @param deltaLongitude the height of the sector in degrees; must equal to or greater than zero.
     *
     * @return this sector with its coordinates set to the specified values
     */
    fun setDegrees(minLatitude: Double, minLongitude: Double, deltaLatitude: Double, deltaLongitude: Double) = set(
        fromDegrees(minLatitude), fromDegrees(minLongitude),
        fromDegrees(deltaLatitude), fromDegrees(deltaLongitude),
    )

    /**
     * Sets this sector to the specified latitude, longitude and dimension in radians.
     *
     * @param minLatitude    the minimum latitude in radians, i.e., the latitude at the southwest corner of the sector.
     * @param minLongitude   the minimum longitude in radians, i.e., the longitude at the southwest corner of the sector.
     * @param deltaLatitude  the width of the sector in radians; must equal to or greater than zero.
     * @param deltaLongitude the height of the sector in radians; must equal to or greater than zero.
     *
     * @return this sector with its coordinates set to the specified values
     */
    fun setRadians(minLatitude: Double, minLongitude: Double, deltaLatitude: Double, deltaLongitude: Double) = set(
        fromRadians(minLatitude), fromRadians(minLongitude),
        fromRadians(deltaLatitude), fromRadians(deltaLongitude),
    )

    /**
     * Sets this sector to the minimum and maximum latitudes and longitudes of a specified sector.
     *
     * @param sector the sector specifying the new coordinates
     *
     * @return this sector with its coordinates set to that of the specified sector
     */
    fun copy(sector: Sector) = apply {
        minLatitude = sector.minLatitude
        maxLatitude = sector.maxLatitude
        minLongitude = sector.minLongitude
        maxLongitude = sector.maxLongitude
    }

    /**
     * Sets this sector to an empty sector.
     *
     * @return this sector with its coordinates set to an empty sector
     */
    fun setEmpty() = apply {
        minLatitude = ZERO
        maxLatitude = ZERO
        minLongitude = ZERO
        maxLongitude = ZERO
    }

    /**
     * Sets this sector to the full range of latitude [90 to +90] and longitude [-180 to +180].
     *
     * @return this sector with its coordinates set to the full range of latitude and longitude
     */
    fun setFullSphere() = apply {
        minLatitude = NEG90
        maxLatitude = POS90
        minLongitude = NEG180
        maxLongitude = POS180
    }

    /**
     * Indicates whether this sector intersects a specified sector. Two sectors intersect when both the latitude
     * boundaries and the longitude boundaries overlap by a non-zero amount. An empty sector never intersects another
     * sector.
     * <br>
     * The sectors are assumed to have normalized angles (angles within the range [-90, +90] latitude and [-180, +180]
     * longitude).
     *
     * @param sector the sector to test intersection with
     *
     * @return true if the specified sector intersections this sector, false otherwise
     */
    fun intersects(sector: Sector) = minLatitude < sector.maxLatitude && maxLatitude > sector.minLatitude
            && minLongitude < sector.maxLongitude && maxLongitude > sector.minLongitude

    /**
     * Indicates if this sector is next to, or intersects, a specified sector. Two sectors intersect when the conditions
     * of the [Sector.intersects] methods have been met, and if the boundary or corner is shared with the
     * specified sector. This is a temporary implementation and will be deprecated in future releases.
     * <br>
     * The sectors are assumed to have normalized angles (angles within the range [-90, +90] latitude and [-180, +180]
     * longitude).
     *
     * @param sector the sector to test intersection with
     *
     * @return true if the specified sector intersects or is next to this sector, false otherwise
     */
    fun intersectsOrNextTo(sector: Sector) = minLatitude <= sector.maxLatitude && maxLatitude >= sector.minLatitude
            && minLongitude <= sector.maxLongitude && maxLongitude >= sector.minLongitude

    /**
     * Computes the intersection of this sector and a specified sector, storing the result in this sector and returning
     * whether or not the sectors intersect. Two sectors intersect when both the latitude boundaries and the longitude
     * boundaries overlap by a non-zero amount. An empty sector never intersects another sector. When there is no
     * intersection, this returns false and leaves this sector unchanged.
     * <br>
     * The sectors are assumed to have normalized angles (angles within the range [-90, +90] latitude and [-180, +180]
     * longitude).
     *
     * @param sector the sector to intersect with
     *
     * @return this true if this sector intersects the specified sector, false otherwise
     */
    fun intersect(sector: Sector): Boolean {
        if (minLatitude < sector.maxLatitude && maxLatitude > sector.minLatitude // latitudes intersect
            && minLongitude < sector.maxLongitude && maxLongitude > sector.minLongitude // longitudes intersect
        ) {
            if (minLatitude < sector.minLatitude) minLatitude = sector.minLatitude
            if (maxLatitude > sector.maxLatitude) maxLatitude = sector.maxLatitude
            if (minLongitude < sector.minLongitude) minLongitude = sector.minLongitude
            if (maxLongitude > sector.maxLongitude) maxLongitude = sector.maxLongitude
            return true
        }
        return false // the two sectors do not intersect
    }

    /**
     * Indicates whether this sector contains a specified geographic location.
     * An empty sector never contains a location.
     * Assumes normalized angles: [-90, +90], [-180, +180]
     *
     * @param latitude  the location's latitude
     * @param longitude the location's longitude
     *
     * @return true if this sector contains the location, false otherwise
     */
    fun contains(latitude: Angle, longitude: Angle) = latitude in minLatitude..maxLatitude && longitude in minLongitude..maxLongitude

    /**
     * Indicates whether this sector contains a specified geographic location. An empty sector never contains a
     * location.
     *
     * @param location  the location
     *
     * @return true if this sector contains the location, false otherwise
     */
    fun contains(location: Location) = contains(location.latitude, location.longitude)

    /**
     * Indicates whether this sector fully contains a specified sector. This sector contains the specified sector when
     * the specified sector's boundaries are completely contained within this sector's boundaries, or are equal to this
     * sector's boundaries. An empty sector never contains another sector.
     * <br>
     * The sectors are assumed to have normalized angles (angles within the range [-90, +90] latitude and [-180, +180]
     * longitude).
     *
     * @param sector the sector to test containment with
     *
     * @return true if the specified sector contains this sector, false otherwise
     */
    fun contains(sector: Sector) = minLatitude <= sector.minLatitude && maxLatitude >= sector.maxLatitude
            && minLongitude <= sector.minLongitude && maxLongitude >= sector.maxLongitude

    /**
     * Sets this sector to the union of itself and a specified location.
     * Assumes normalized angles: [-90, +90], [-180, +180]
     *
     * @param latitude  the location's latitude
     * @param longitude the location's longitude
     *
     * @return this sector, set to its union with the specified location
     */
    fun union(latitude: Angle, longitude: Angle) = apply {
        if (!isEmpty) {
            minLatitude = min(minLatitude, latitude)
            maxLatitude = max(maxLatitude, latitude)
            minLongitude = min(minLongitude, longitude)
            maxLongitude = max(maxLongitude, longitude)
        } else {
            minLatitude = latitude
            maxLatitude = latitude
            minLongitude = longitude
            maxLongitude = longitude
        }
    }

    /**
     * Sets this sector to the union of itself and a specified location.
     *
     * @param location  the location
     *
     * @return this sector, set to its union with the specified location
     */
    fun union(location: Location) = union(location.latitude, location.longitude)

    /**
     * Sets this sector to the union of itself and an array of specified locations. If this sector is empty, it bounds
     * the specified locations. The array is understood to contain location of at least two coordinates organized as
     * (longitude, latitude, ...), where stride indicates the number of coordinates between longitude values.
     *
     * @param array  the array of locations to consider
     * @param count  the number of array elements to consider
     * @param stride the number of coordinates between the first coordinate of adjacent locations - must be at least 2
     *
     * @return This bounding box set to contain the specified array of locations.
     *
     * @throws IllegalArgumentException If the array is empty, if the count is less than 0, or if the stride is
     * less than 2
     */
    fun union(array: FloatArray, count: Int, stride: Int) = apply {
        require(array.size >= stride) {
            logMessage(ERROR, "Sector", "union", "missingArray")
        }
        require(count >= 0) {
            logMessage(ERROR, "Sector", "union", "invalidCount")
        }
        require(stride >= 2) {
            logMessage(ERROR, "Sector", "union", "invalidStride")
        }
        val empty = isEmpty
        var minLat = if (empty) Double.MAX_VALUE else minLatitude.degrees
        var maxLat = if (empty) -Double.MAX_VALUE else maxLatitude.degrees
        var minLon = if (empty) Double.MAX_VALUE else minLongitude.degrees
        var maxLon = if (empty) -Double.MAX_VALUE else maxLongitude.degrees
        for (idx in 0 until count step stride) {
            val lon = array[idx].toDouble()
            val lat = array[idx + 1].toDouble()
            if (maxLat < lat) maxLat = lat
            if (minLat > lat) minLat = lat
            if (maxLon < lon) maxLon = lon
            if (minLon > lon) minLon = lon
        }
        if (minLat < Double.MAX_VALUE) minLatitude = fromDegrees(minLat)
        if (maxLat > -Double.MAX_VALUE) maxLatitude = fromDegrees(maxLat)
        if (minLon < Double.MAX_VALUE) minLongitude = fromDegrees(minLon)
        if (maxLon > -Double.MAX_VALUE) maxLongitude = fromDegrees(maxLon)
    }

    /**
     * Sets this sector to the union of itself and a specified sector.
     * This has no effect if the specified sector is empty.
     * If this sector is empty, it is set to the specified sector.
     * Assumes normalized angles: [-90, +90], [-180, +180]
     *
     * @param sector the sector to union with
     *
     * @return this sector, set to its union with the specified sector
     */
    fun union(sector: Sector) = apply {
        if (!sector.isEmpty) {
            // specified sector not empty
            if (!isEmpty) {
                // this sector not empty, make a union
                if (minLatitude > sector.minLatitude) minLatitude = sector.minLatitude
                if (maxLatitude < sector.maxLatitude) maxLatitude = sector.maxLatitude
                if (minLongitude > sector.minLongitude) minLongitude = sector.minLongitude
                if (maxLongitude < sector.maxLongitude) maxLongitude = sector.maxLongitude
            } else {
                // this sector is empty, set to the specified sector
                minLatitude = sector.minLatitude
                maxLatitude = sector.maxLatitude
                minLongitude = sector.minLongitude
                maxLongitude = sector.maxLongitude
            }
        }
    }

    /**
     * Translates this sector by a specified geographic increment.
     * <br>
     * The translated sector is assumed to have normalized angles (angles within the range [-90, +90] latitude and
     * [-180, +180] longitude).
     *
     * @param deltaLatitudeDegrees  the translation's latitude increment in degrees
     * @param deltaLongitudeDegrees the translation's longitude increment in degrees
     *
     * @return this sector, translated by the specified increment
     */
    fun translate(deltaLatitudeDegrees: Double, deltaLongitudeDegrees: Double) = apply {
        minLatitude = minLatitude.plusDegrees(deltaLatitudeDegrees)
        maxLatitude = maxLatitude.plusDegrees(deltaLatitudeDegrees)
        minLongitude = minLongitude.plusDegrees(deltaLongitudeDegrees)
        maxLongitude = maxLongitude.plusDegrees(deltaLongitudeDegrees)
    }

    override fun equals(other: Any?): Boolean {
        // if (this === other) return true // Empty sector is not equal self
        if (other !is Sector) return false
        if (isEmpty && other.isEmpty) return false // Two empty sectors are not equal
        return minLatitude == other.minLatitude && maxLatitude == other.maxLatitude
                && minLongitude == other.minLongitude && maxLongitude == other.maxLongitude
    }

    override fun hashCode(): Int {
        var result = minLatitude.hashCode()
        result = 31 * result + maxLatitude.hashCode()
        result = 31 * result + minLongitude.hashCode()
        result = 31 * result + maxLongitude.hashCode()
        return result
    }

    override fun toString() = "Sector(minLatitude=$minLatitude, maxLatitude=$maxLatitude, minLongitude=$minLongitude, maxLongitude=$maxLongitude)"
}