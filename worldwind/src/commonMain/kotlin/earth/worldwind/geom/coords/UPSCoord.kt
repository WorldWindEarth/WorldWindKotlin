package earth.worldwind.geom.coords

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.radians
import kotlin.jvm.JvmStatic

/**
 * This immutable class holds a set of UPS coordinates along with it's corresponding latitude and longitude.
 */
class UPSCoord private constructor(
    val latitude: Angle, val longitude: Angle, val hemisphere: Hemisphere, val easting: Double, val northing: Double
) {
    companion object {
        /**
         * Create a set of UPS coordinates from a pair of latitude and longitude for the given `Globe`.
         *
         * @param latitude  the latitude.
         * @param longitude the longitude.
         *
         * @return the corresponding [UPSCoord].
         *
         * @throws IllegalArgumentException if the conversion to UPS coordinates fails.
         */
        @JvmStatic
        fun fromLatLon(latitude: Angle, longitude: Angle): UPSCoord {
            val converter = UPSCoordConverter()
            val err = converter.convertGeodeticToUPS(latitude.inRadians, longitude.inRadians)
            require(err == UPSCoordConverter.NO_ERROR) { "UPS Conversion Error" }
            return UPSCoord(latitude, longitude, converter.hemisphere, converter.easting, converter.northing)
        }

        /**
         * Create a set of UPS coordinates for the given [Globe].
         *
         * @param hemisphere the hemisphere, either [Hemisphere.N] of [Hemisphere.S].
         * @param easting    the easting distance in meters
         * @param northing   the northing distance in meters.
         *
         * @return the corresponding [UPSCoord].
         *
         * @throws IllegalArgumentException if the conversion to UPS coordinates fails.
         */
        @JvmStatic
        fun fromUPS(hemisphere: Hemisphere, easting: Double, northing: Double): UPSCoord {
            val converter = UPSCoordConverter()
            val err = converter.convertUPSToGeodetic(hemisphere, easting, northing)
            require(err == UTMCoordConverter.NO_ERROR) { "UTM Conversion Error" }
            return UPSCoord(
                converter.latitude.radians, converter.longitude.radians, hemisphere, easting, northing
            )
        }
    }

    override fun toString() = hemisphere.toString() + " " + easting + "E" + " " + northing + "N"
}