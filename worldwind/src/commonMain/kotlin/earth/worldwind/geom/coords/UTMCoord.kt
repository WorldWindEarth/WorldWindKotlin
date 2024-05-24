package earth.worldwind.geom.coords

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.geom.Location
import kotlin.jvm.JvmStatic
import kotlin.math.roundToInt

/**
 * This immutable class holds a set of UTM coordinates along with it's corresponding latitude and longitude.
 */
class UTMCoord private constructor(
    val latitude: Angle, val longitude: Angle, val zone: Int, val hemisphere: Hemisphere, val easting: Double, val northing: Double
) {
    companion object {
        /**
         * Create a set of UTM coordinates from a pair of latitude and longitude for the given `Globe`.
         *
         * @param latitude  the latitude.
         * @param longitude the longitude.
         *
         * @return the corresponding [UTMCoord].
         *
         * @throws IllegalArgumentException if the conversion to UTM coordinates fails.
         */
        @JvmStatic
        fun fromLatLon(latitude: Angle, longitude: Angle): UTMCoord {
            val converter = UTMCoordConverter()
            val err = converter.convertGeodeticToUTM(latitude.inRadians, longitude.inRadians)
            require(err == UTMCoordConverter.NO_ERROR) { "UTM Conversion Error" }
            return UTMCoord(
                latitude, longitude, converter.zone, converter.hemisphere, converter.easting, converter.northing
            )
        }

        /**
         * Create a set of UTM coordinates for the given [Globe].
         *
         * @param zone       the UTM zone - 1 to 60.
         * @param hemisphere the hemisphere, either [Hemisphere.N] of [Hemisphere.S].
         * @param easting    the easting distance in meters
         * @param northing   the northing distance in meters.
         *
         * @return the corresponding [UTMCoord].
         *
         * @throws IllegalArgumentException if the conversion to UTM coordinates fails.
         */
        @JvmStatic
        fun fromUTM(
            zone: Int, hemisphere: Hemisphere, easting: Double, northing: Double
        ): UTMCoord {
            val converter = UTMCoordConverter()
            val err = converter.convertUTMToGeodetic(zone, hemisphere, easting, northing)
            require(err == UTMCoordConverter.NO_ERROR) { "UTM Conversion Error" }
            return UTMCoord(
                converter.latitude.radians, converter.longitude.radians, zone, hemisphere, easting, northing
            )
        }

        /**
         * Create a UTM coordinate from a standard UTM coordinate text string.
         *
         * The string will be converted to uppercase and stripped of all spaces before being evaluated.
         *
         * @param UTMString the UTM coordinate text string.
         * @return the corresponding [UTMCoord].
         * @throws IllegalArgumentException if the [UTMString] is empty, or the conversion to geodetic coordinates fails.
         */
        @JvmStatic
        fun fromString(UTMString: String): UTMCoord {
            val separated = UTMString.trim { it <= ' ' }.replace(" +".toRegex(), " ").split(" ")
             return fromUTM(
                separated[0].toInt(),
                if (separated[1] == "S") Hemisphere.S else Hemisphere.N, //Hemisphere.valueOf(separated[1]),
                separated[2].substring(0, separated[2].length - 1).toDouble(),
                separated[3].substring(0, separated[3].length - 1).toDouble()
            )
        }
    }

    fun toLocation() = Location(latitude, longitude)

    override fun toString() = zone.toString() + " " + hemisphere + " " + easting.roundToInt() + "E" + " " + northing.roundToInt() + "N"
}