package earth.worldwind.geom.coords

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.fromRadians
import kotlin.jvm.JvmStatic

/**
 * This class holds an immutable MGRS coordinate string along with
 * the corresponding latitude and longitude.
 */
class MGRSCoord private constructor(val latitude: Angle, val longitude: Angle, private val MGRSString: String) {
    companion object {
        /**
         * Create a MGRS coordinate from a pair of latitude and longitude [Angle]
         * with the maximum precision of five digits (one meter).
         *
         * @param latitude the latitude [Angle].
         * @param longitude the longitude [Angle].
         * @return the corresponding [MGRSCoord].
         * @throws IllegalArgumentException if the conversion to MGRS coordinates fails.
         */
        @JvmStatic
        fun fromLatLon(latitude: Angle, longitude: Angle) = fromLatLon(latitude, longitude, 5)

        /**
         * Create a MGRS coordinate from a pair of latitude and longitude [Angle]
         * with the given precision or number of digits (1 to 5).
         *
         * @param latitude the latitude [Angle].
         * @param longitude the longitude [Angle].
         * @param precision the number of digits used for easting and northing (1 to 5).
         * @return the corresponding [MGRSCoord].
         * @throws IllegalArgumentException if the conversion to MGRS coordinates fails.
         */
        @JvmStatic
        fun fromLatLon(latitude: Angle, longitude: Angle, precision: Int): MGRSCoord {
            val converter = MGRSCoordConverter()
            val err = converter.convertGeodeticToMGRS(latitude.radians, longitude.radians, precision)
            require(err == MGRSCoordConverter.NO_ERROR) { "MGRS Conversion Error" }
            return MGRSCoord(latitude, longitude, converter.mgrsString)
        }

        /**
         * Create a MGRS coordinate from a standard MGRS coordinate text string.
         *
         * The string will be converted to uppercase and stripped of all spaces before being evaluated.
         *
         * Valid examples:<br>
         * 32TLP5626635418<br>
         * 32 T LP 56266 35418<br>
         * 11S KU 528 111<br>
         *
         * @param MGRSString the MGRS coordinate text string.
         * @return the corresponding [MGRSCoord].
         * @throws IllegalArgumentException if the [MGRSString] is empty,
         * the [Globe] is null, or the conversion to geodetic coordinates fails (invalid coordinate string).
         */
        @JvmStatic
        fun fromString(MGRSString: String): MGRSCoord {
            var str = MGRSString
            str = str.uppercase().replace(" ", "")
            val converter = MGRSCoordConverter()
            val err = converter.convertMGRSToGeodetic(str)
            require(err == MGRSCoordConverter.NO_ERROR) { "MGRS Conversion Error" }
            return MGRSCoord(fromRadians(converter.latitude), fromRadians(converter.longitude), str)
        }
    }

    override fun toString() = MGRSString
}