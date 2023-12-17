package earth.worldwind.geom

import earth.worldwind.geom.Angle.Companion.POS180
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Angle.Companion.fromRadians
import earth.worldwind.geom.Angle.Companion.normalizeLongitude
import earth.worldwind.shape.PathType
import earth.worldwind.shape.PathType.GREAT_CIRCLE
import earth.worldwind.shape.PathType.RHUMB_LINE
import earth.worldwind.util.format.format
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetIn
import kotlin.jvm.JvmStatic
import kotlin.math.*

/**
 * Geographic location with a latitude and longitude.
 */
open class Location(
    /**
     * The location's latitude.
     */
    var latitude: Angle,
    /**
     * The location's longitude.
     */
    var longitude: Angle
) {
    /**
     * Constructs a location with latitude and longitude both 0.
     */
    constructor(): this(latitude = ZERO, longitude = ZERO)

    /**
     * Constructs a location with the latitude and longitude of a specified location.
     *
     * @param location the location specifying the coordinates
     */
    constructor(location: Location): this(location.latitude, location.longitude)

    companion object {
        protected const val NEAR_ZERO_THRESHOLD = 1e-15
        protected val timeZoneLatitudes = mapOf(
            -12 to -45, // GMT-12
            -11 to -30, // GMT-11
            -10 to 20, // GMT-10
            -9 to 45, // GMT-9
            -8 to 40, // GMT-8
            -7 to 35, // GMT-7
            -6 to 30, // GMT-6
            -5 to 25, // GMT-5
            -4 to -15, // GMT-4
            -3 to 0, // GMT-3
            -2 to 45, // GMT-2
            -1 to 30, // GMT-1
            0 to 30, // GMT+0
            1 to 20, // GMT+1
            2 to 20, // GMT+2
            3 to 25, // GMT+3
            4 to 30, // GMT+4
            5 to 35, // GMT+5
            6 to 30, // GMT+6
            7 to 25, // GMT+7
            8 to -30, // GMT+8
            9 to -30, // GMT+9
            10 to -30, // GMT+10
            11 to -45, // GMT+11
            12 to -45 // GMT+12
        )

        /**
         * Constructs a location with a specified latitude and longitude in degrees.
         *
         * @param latitudeDegrees  the latitude in degrees
         * @param longitudeDegrees the longitude in degrees
         *
         * @return the new location
         */
        @JvmStatic
        fun fromDegrees(latitudeDegrees: Double, longitudeDegrees: Double) =
            Location(fromDegrees(latitudeDegrees), fromDegrees(longitudeDegrees))

        /**
         * Constructs a location with a specified latitude and longitude in radians.
         *
         * @param latitudeRadians  the latitude in radians
         * @param longitudeRadians the longitude in radians
         *
         * @return the new location
         */
        @JvmStatic
        fun fromRadians(latitudeRadians: Double, longitudeRadians: Double) =
            Location(fromRadians(latitudeRadians), fromRadians(longitudeRadians))

        /**
         * Constructs an approximate location for a specified time zone. Used when selecting an initial camera position
         * based on the device's current time zone.
         *
         * @param timeZone the time zone in question
         *
         * @return the new location
         */
        @JvmStatic
        fun fromTimeZone(timeZone: TimeZone): Location {
            val secPerHour = 3.6e3
            val offsetSec = Clock.System.now().offsetIn(timeZone).totalSeconds
            val offsetHours = (offsetSec / secPerHour).toInt()
            // use a pre-determined latitude or 0 if none is available
            val latDegrees = (timeZoneLatitudes[offsetHours]?:0).toDouble()
            val lonDegrees = 180.0 * offsetHours / 12 // center on the time zone's average longitude
            return Location(fromDegrees(latDegrees), fromDegrees(lonDegrees))
        }

        /**
         * Determines whether a list of locations crosses the antimeridian.
         *
         * @param locations the locations to test
         *
         * @return true if the antimeridian is crossed, false otherwise
         */
        @JvmStatic
        fun locationsCrossAntimeridian(locations: List<Location>): Boolean {
            // Check the list's length. A list with fewer than two locations does not cross the antimeridan.
            val len = locations.size
            if (len < 2) return false

            // Compute the longitude attributes associated with the first location.
            var lon1 = normalizeLongitude(locations[0].longitude.inDegrees)
            var sig1 = sign(lon1)

            // Iterate over the segments in the list. A segment crosses the antimeridian if its endpoint longitudes have
            // different signs and are more than 180 degrees apart (but not 360, which indicates the longitudes are the same).
            for (idx in 1 until len) {
                val lon2 = normalizeLongitude(locations[idx].longitude.inDegrees)
                val sig2 = sign(lon2)
                if (sig1 != sig2) {
                    val delta = abs(lon1 - lon2)
                    if (delta > 180 && delta < 360) return true
                }
                lon1 = lon2
                sig1 = sig2
            }
            return false
        }

        @JvmStatic
        fun fromString(coordinates: String): Location {
            val tokens = coordinates.replace("[*'\"NSEW;°′″,]".toRegex(), " ").trim { it <= ' ' }
                .split("\\s+".toRegex()).toTypedArray()
            // Lat
            var lat = 0.0
            var exponent = 0
            var i = 0
            while (i < tokens.size / 2) {
                lat += tokens[i].toDouble() / 60.0.pow(exponent++.toDouble())
                i++
            }
            // Lon
            var lon = 0.0
            exponent = 0
            while (i < tokens.size) {
                lon += tokens[i].toDouble() / 60.0.pow(exponent++.toDouble())
                i++
            }
            return fromDegrees(if (coordinates.contains("S")) -lat else lat, if (coordinates.contains("W")) -lon else lon)
        }
    }

    /**
     * Sets this location to a specified latitude and longitude.
     *
     * @param latitude  the new latitude
     * @param longitude the new longitude
     *
     * @return this location with its latitude and longitude set to the specified values
     */
    fun set(latitude: Angle, longitude: Angle) = apply {
        this.latitude = latitude
        this.longitude = longitude
    }

    /**
     * Sets this location to a specified latitude and longitude in degrees.
     *
     * @param latitudeDegrees  the new latitude in degrees
     * @param longitudeDegrees the new longitude in degrees
     *
     * @return this location with its latitude and longitude set to the specified values
     */
    fun setDegrees(latitudeDegrees: Double, longitudeDegrees: Double) =
        set(fromDegrees(latitudeDegrees), fromDegrees(longitudeDegrees))

    /**
     * Sets this location to a specified latitude and longitude in radians.
     *
     * @param latitudeRadians  the new latitude in radians
     * @param longitudeRadians the new longitude in radians
     *
     * @return this location with its latitude and longitude set to the specified values
     */
    fun setRadians(latitudeRadians: Double, longitudeRadians: Double) =
        set(fromRadians(latitudeRadians), fromRadians(longitudeRadians))

    /**
     * Sets this location to the latitude and longitude of a specified location.
     *
     * @param location the location specifying the new coordinates
     *
     * @return this location with its latitude and longitude set to that of the specified location
     */
    fun copy(location: Location) = set(location.latitude, location.longitude)

    /**
     * Compute a location along a path between two locations. The amount indicates the fraction of the path at which to
     * compute a location. This value is typically between 0 and 1, where 0 indicates the begin location (this location)
     * and 1 indicates the end location.
     *
     * @param endLocation the path's end location
     * @param pathType    [PathType] indicating type of path to assume
     * @param amount      the fraction of the path at which to compute a location
     * @param result      a pre-allocated Location in which to return the computed location
     *
     * @return the result argument set to the computed location
     */
    fun interpolateAlongPath(endLocation: Location, pathType: PathType, amount: Double, result: Location): Location {
        return if (this == endLocation) {
            result.latitude = latitude
            result.longitude = longitude
            result
        } else when (pathType) {
            GREAT_CIRCLE -> {
                val azimuth = greatCircleAzimuth(endLocation)
                val distanceRadians = greatCircleDistance(endLocation) * amount
                greatCircleLocation(azimuth, distanceRadians, result)
            }
            RHUMB_LINE -> {
                val azimuth = rhumbAzimuth(endLocation)
                val distanceRadians = rhumbDistance(endLocation) * amount
                rhumbLocation(azimuth, distanceRadians, result)
            }
            else -> {
                val azimuth = linearAzimuth(endLocation)
                val distanceRadians = linearDistance(endLocation) * amount
                linearLocation(azimuth, distanceRadians, result)
            }
        }
    }

    /**
     * Computes the azimuth angle (clockwise from North) for the great circle path between this location and a specified
     * location. This angle can be used as the starting azimuth for a great circle path beginning at this location, and
     * passing through the specified location. This function uses a spherical model, not elliptical.
     *
     * @param location the great circle path's ending location
     *
     * @return the computed azimuth
     */
    fun greatCircleAzimuth(location: Location): Angle {
        val lat1 = latitude.inRadians
        val lon1 = longitude.inRadians
        val lat2 = location.latitude.inRadians
        val lon2 = location.longitude.inRadians
        if (lat1 == lat2 && lon1 == lon2) return ZERO
        if (lon1 == lon2) return if (lat1 > lat2) POS180 else ZERO

        // Taken from "Map Projections - A Working Manual", page 30, equation 5-4b.
        // The atan2() function is used in place of the traditional atan(y/x) to simplify the case when x == 0.
        val y = cos(lat2) * sin(lon2 - lon1)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(lon2 - lon1)
        val azimuthRadians = atan2(y, x)
        return if (azimuthRadians.isNaN()) ZERO else fromRadians(azimuthRadians)
    }

    /**
     * Computes the angular distance of the great circle path between this location and a specified location. In
     * radians, this angle is the arc length of the segment between the two locations. To compute a distance in meters
     * from this value, multiply the return value by the radius of the globe. This function uses a spherical model, not
     * elliptical.
     *
     * @param location the great circle path's ending location
     *
     * @return the computed angular distance in radians
     */
    fun greatCircleDistance(location: Location): Double {
        val lat1Radians = latitude.inRadians
        val lon1Radians = longitude.inRadians
        val lat2Radians = location.latitude.inRadians
        val lon2Radians = location.longitude.inRadians
        if (lat1Radians == lat2Radians && lon1Radians == lon2Radians) return 0.0

        // "Haversine formula," taken from http://en.wikipedia.org/wiki/Great-circle_distance#Formul.C3.A6
        val a = sin((lat2Radians - lat1Radians) / 2.0)
        val b = sin((lon2Radians - lon1Radians) / 2.0)
        val c = a * a + cos(lat1Radians) * cos(lat2Radians) * b * b
        val distanceRadians = 2.0 * asin(sqrt(c))
        return if (distanceRadians.isNaN()) 0.0 else distanceRadians
    }

    /**
     * Computes the location on the great circle path starting at this location and traversing with the specified
     * azimuth and angular distance. This function uses a spherical model, not elliptical.
     *
     * @param azimuth         the starting azimuth
     * @param distanceRadians the angular distance along the path in radians
     * @param result          a pre-allocated Location in which to return the computed location
     *
     * @return the result argument set to the computed location
     */
    fun greatCircleLocation(azimuth: Angle, distanceRadians: Double, result: Location): Location {
        if (distanceRadians == 0.0) {
            result.latitude = latitude
            result.longitude = longitude
            return result
        }
        val latRadians = latitude.inRadians
        val lonRadians = longitude.inRadians
        val azimuthRadians = azimuth.inRadians
        val cosLat = cos(latRadians)
        val sinLat = sin(latRadians)
        val cosAzimuth = cos(azimuthRadians)
        val sinAzimuth = sin(azimuthRadians)
        val sinDistance = sin(distanceRadians)
        val cosDistance = cos(distanceRadians)

        // Taken from "Map Projections - A Working Manual", page 31, equation 5-5 and 5-6.
        val endLatRadians = asin(sinLat * cosDistance + cosLat * sinDistance * cosAzimuth)
        val endLonRadians = lonRadians + atan2(
            sinDistance * sinAzimuth, cosLat * cosDistance - sinLat * sinDistance * cosAzimuth
        )
        if (endLatRadians.isNaN() || endLonRadians.isNaN()) {
            result.latitude = latitude
            result.longitude = longitude
        } else {
            result.latitude = fromRadians(endLatRadians).normalizeLatitude()
            result.longitude = fromRadians(endLonRadians).normalizeLongitude()
        }
        return result
    }

    /**
     * Computes the azimuth angle (clockwise from North) for the rhumb path (line of constant azimuth) between this
     * location and a specified location. This angle can be used as the starting azimuth for a rhumb path beginning at
     * this location, and passing through the specified location. This function uses a spherical model, not elliptical.
     *
     * @param location the rhumb path's ending location
     *
     * @return the computed azimuth
     */
    fun rhumbAzimuth(location: Location): Angle {
        val lat1 = latitude.inRadians
        val lon1 = longitude.inRadians
        val lat2 = location.latitude.inRadians
        val lon2 = location.longitude.inRadians
        if (lat1 == lat2 && lon1 == lon2) return ZERO
        var dLon = lon2 - lon1
        val dPhi = ln(tan(lat2 / 2.0 + PI / 4) / tan(lat1 / 2.0 + PI / 4))

        // If lonChange over 180 take shorter rhumb across 180 meridian.
        if (abs(dLon) > PI) dLon = if (dLon > 0) -(2 * PI - dLon) else 2 * PI + dLon
        val azimuthRadians = atan2(dLon, dPhi)
        return if (azimuthRadians.isNaN()) ZERO else fromRadians(azimuthRadians)
    }

    /**
     * Computes the angular distance of the rhumb path (line of constant azimuth) between this location and a specified
     * location. In radians, this angle is the arc length of the segment between the two locations. To compute a
     * distance in meters from this value, multiply the return value by the radius of the globe. This function uses a
     * spherical model, not elliptical.
     *
     * @param location the great circle path's ending location
     *
     * @return the computed angular distance in radians
     */
    fun rhumbDistance(location: Location): Double {
        val lat1 = latitude.inRadians
        val lon1 = longitude.inRadians
        val lat2 = location.latitude.inRadians
        val lon2 = location.longitude.inRadians
        if (lat1 == lat2 && lon1 == lon2) return 0.0
        val dLat = lat2 - lat1
        var dLon = lon2 - lon1
        // Avoid indeterminates along E/W courses when lat end points are "nearly" identical
        val q = if (abs(dLat) < NEAR_ZERO_THRESHOLD) cos(lat1)
        else {
            val dPhi = ln(tan(lat2 / 2.0 + PI / 4) / tan(lat1 / 2.0 + PI / 4))
            dLat / dPhi
        }

        // If lonChange over 180 take shorter rhumb across 180 meridian.
        if (abs(dLon) > PI) dLon = if (dLon > 0) -(2 * PI - dLon) else 2 * PI + dLon
        val distanceRadians = sqrt(dLat * dLat + q * q * dLon * dLon)
        return if (distanceRadians.isNaN()) 0.0 else distanceRadians
    }

    /**
     * Computes the location on a rhumb path (line of constant azimuth) starting at this location and traversing with
     * the specified azimuth and angular distance. This function uses a spherical model, not elliptical.
     *
     * @param azimuth         the starting azimuth
     * @param distanceRadians the angular distance along the path in radians
     * @param result          a pre-allocated Location in which to return the computed location
     *
     * @return the result argument set to the computed location
     */
    fun rhumbLocation(azimuth: Angle, distanceRadians: Double, result: Location): Location {
        if (distanceRadians == 0.0) {
            result.latitude = latitude
            result.longitude = longitude
            return result
        }
        val latRadians = latitude.inRadians
        val lonRadians = longitude.inRadians
        val azimuthRadians = azimuth.inRadians
        var endLatRadians = latRadians + distanceRadians * cos(azimuthRadians)
        val dLat = endLatRadians - latRadians
        // Avoid indeterminates along E/W courses when lat end points are "nearly" identical
        val q = if (abs(dLat) < NEAR_ZERO_THRESHOLD) cos(latRadians)
        else {
            val dPhi = ln(tan(endLatRadians / 2 + PI / 4) / tan(latRadians / 2 + PI / 4))
            dLat / dPhi
        }
        val dLon = distanceRadians * sin(azimuthRadians) / q

        // Handle latitude passing over either pole.
        if (abs(endLatRadians) > PI / 2) endLatRadians = if (endLatRadians > 0) PI - endLatRadians else -PI - endLatRadians
        val endLonRadians = (lonRadians + dLon + PI) % (2 * PI) - PI
        if (endLatRadians.isNaN() || endLonRadians.isNaN()) {
            result.latitude = latitude
            result.longitude = longitude
        } else {
            result.latitude = fromRadians(endLatRadians).normalizeLatitude()
            result.longitude = fromRadians(endLonRadians).normalizeLongitude()
        }
        return result
    }

    /**
     * Computes the azimuth angle (clockwise from North) for the linear path between this location and a specified
     * location. This angle can be used as the starting azimuth for a linear path beginning at this location, and
     * passing through the specified location. This function uses a flat-earth approximation proximal to this location.
     *
     * @param location the linear path's ending location
     *
     * @return the computed azimuth
     */
    fun linearAzimuth(location: Location): Angle {
        val lat1 = latitude.inRadians
        val lon1 = longitude.inRadians
        val lat2 = location.latitude.inRadians
        val lon2 = location.longitude.inRadians
        if (lat1 == lat2 && lon1 == lon2) return ZERO
        var dLon = lon2 - lon1
        val dPhi = lat2 - lat1

        // If longitude change is over 180 take shorter path across 180 meridian.
        if (abs(dLon) > PI) dLon = if (dLon > 0) -(2 * PI - dLon) else 2 * PI + dLon
        val azimuthRadians = atan2(dLon, dPhi)
        return if (azimuthRadians.isNaN()) ZERO else fromRadians(azimuthRadians)
    }

    /**
     * Computes the angular distance of the linear path between this location and a specified location. In radians, this
     * angle is the arc length of the segment between the two locations. To compute a distance in meters from this
     * value, multiply the return value by the radius of the globe. This function uses a flat-earth approximation
     * proximal to this location.
     *
     * @param location the great circle path's ending location
     *
     * @return the computed angular distance in radians
     */
    fun linearDistance(location: Location): Double {
        val lat1 = latitude.inRadians
        val lon1 = longitude.inRadians
        val lat2 = location.latitude.inRadians
        val lon2 = location.longitude.inRadians
        if (lat1 == lat2 && lon1 == lon2) return 0.0
        val dLat = lat2 - lat1
        var dLon = lon2 - lon1

        // If lonChange over 180 take shorter path across 180 meridian.
        if (abs(dLon) > PI) dLon = if (dLon > 0) -(2 * PI - dLon) else 2 * PI + dLon
        val distanceRadians = sqrt(dLat * dLat + dLon * dLon)
        return if (distanceRadians.isNaN()) 0.0 else distanceRadians
    }

    /**
     * Computes the location on the linear path starting at this location and traversing with the specified azimuth and
     * angular distance. This function uses a flat-earth approximation proximal to this location.
     *
     * @param azimuth         the starting azimuth
     * @param distanceRadians the angular distance along the path in radians
     * @param result          a pre-allocated Location in which to return the computed location
     *
     * @return the result argument set to the computed location
     */
    fun linearLocation(azimuth: Angle, distanceRadians: Double, result: Location): Location {
        if (distanceRadians == 0.0) {
            result.latitude = latitude
            result.longitude = longitude
            return result
        }
        val latRadians = latitude.inRadians
        val lonRadians = longitude.inRadians
        val azimuthRadians = azimuth.inRadians
        var endLatRadians = latRadians + distanceRadians * cos(azimuthRadians)

        // Handle latitude passing over either pole.
        if (abs(endLatRadians) > PI / 2) endLatRadians = if (endLatRadians > 0) PI - endLatRadians else -PI - endLatRadians
        val endLonRadians = (lonRadians + distanceRadians * sin(azimuthRadians) + PI) % (2 * PI) - PI
        if (endLatRadians.isNaN() || endLonRadians.isNaN()) {
            result.latitude = latitude
            result.longitude = longitude
        } else {
            result.latitude = fromRadians(endLatRadians).normalizeLatitude()
            result.longitude = fromRadians(endLonRadians).normalizeLongitude()
        }
        return result
    }

    fun equals(other: Location, tolerance: Double): Boolean {
        if (this === other) return true
        return abs(latitude.inDegrees - other.latitude.inDegrees) < tolerance
                && abs(longitude.inDegrees - other.longitude.inDegrees) < tolerance
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Location) return false
        return latitude.inDegrees == other.latitude.inDegrees && longitude.inDegrees == other.longitude.inDegrees
    }

    override fun hashCode(): Int {
        var result = latitude.hashCode()
        result = 31 * result + longitude.hashCode()
        return result
    }

    override fun toString() = "Location(latitude=$latitude, longitude=$longitude)"

    fun toDDString() = "%s%09.6f°, %s%010.6f°"
        .format(latitude.latitudeLetter, abs(latitude.inDegrees), longitude.longitudeLetter, abs(longitude.inDegrees))

    fun toDMString(): String {
        val lat = latitude.toDMS()
        val lon = longitude.toDMS()
        return "%s%02d°%06.3f′, %s%03d°%06.3f′".format(
            latitude.latitudeLetter, lat[1], lat[2] + lat[3] / 60.0,
            longitude.longitudeLetter, lon[1], lon[2] + lon[3] / 60.0
        )
    }

    fun toDMSString(): String {
        val lat = latitude.toDMS()
        val lon = longitude.toDMS()
        return "%s%02d°%02d′%04.1f″, %s%03d°%02d′%04.1f″".format(
            latitude.latitudeLetter, lat[1], lat[2], lat[3],
            longitude.longitudeLetter, lon[1], lon[2], lon[3]
        )
    }

}