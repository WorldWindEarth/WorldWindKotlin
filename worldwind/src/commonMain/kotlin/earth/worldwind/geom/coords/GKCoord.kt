package earth.worldwind.geom.coords

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.geom.Location
import earth.worldwind.util.format.format
import kotlin.jvm.JvmStatic
import kotlin.math.roundToInt

class GKCoord private constructor(val latitude: Angle, val longitude: Angle, val x: Double, val y: Double) {
    companion object {
        /**
         * Create a set of Gauss-Kruger coordinates from a pair of latitude and longitude.
         *
         * @param latitude  the latitude.
         * @param longitude the longitude.
         *
         * @return the corresponding [GKCoord].
         *
         * @throws IllegalArgumentException if the conversion to GK coordinates fails.
         */
        @JvmStatic
        fun fromLatLon(latitude: Angle, longitude: Angle): GKCoord {
            val converter = GKCoordConverter()
            val err = converter.convertGeodeticToGK(latitude.inRadians, longitude.inRadians)
            require(err == GKCoordConverter.NO_ERROR) { "Gauss-Kruger Conversion Error" }
            return GKCoord(latitude, longitude, converter.northing, converter.easting)
        }

        /**
         * Create a set of Gauss-Kruger coordinates.
         *
         * @param x the northing distance (X) in meters.
         * @param y the easting distance (Y) in meters
         *
         * @return the corresponding [GKCoord].
         *
         * @throws IllegalArgumentException if the conversion to Gauss-Kruger coordinates fails.
         */
        @JvmStatic
        fun fromXY(x: Double, y: Double): GKCoord {
            val converter = GKCoordConverter()
            val err = converter.convertGKToGeodetic(y, x)
            require(err == GKCoordConverter.NO_ERROR) { "Gauss-Kruger Conversion Error" }
            return GKCoord(converter.latitude.radians, converter.longitude.radians, x, y)
        }

        /**
         * Create a Gauss-Kruger rectangular coordinate from a standard XY coordinate text string.
         *
         * @param xyString the XY coordinate text string.
         * @return the corresponding [GKCoord].
         * @throws IllegalArgumentException if the [xyString] is empty or the conversion to geodetic coordinates fails.
         */
        @JvmStatic
        fun fromString(xyString: String): GKCoord {
            val tokens = xyString.replace("[-.,;]".toRegex(), "").trim { it <= ' ' }
                .split("\\s+".toRegex()).toTypedArray()
            require(tokens.size >= 2 && tokens[1].length > 6) { "Gauss-Kruger Conversion Error" }
            val x = tokens[0].toDouble()
            val y = tokens[1].toDouble()
            return fromXY(x, y)
        }
    }

    fun toLocation() = Location(latitude, longitude)

    override fun toString(): String {
        val x = x.roundToInt()
        val y = y.roundToInt()
        val suffix = 100000
        return "%02d-%05d, %02d-%05d".format(x / suffix, x % suffix, y / suffix, y % suffix)
    }
}