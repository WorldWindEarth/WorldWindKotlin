package earth.worldwind.geom.coords

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.radians
import kotlin.jvm.JvmStatic

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
            val err = converter.convertGeodeticToUTM(latitude.radians, longitude.radians)
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
    }

    override fun toString() = zone.toString() + " " + hemisphere + " " + easting + "E" + " " + northing + "N"
}