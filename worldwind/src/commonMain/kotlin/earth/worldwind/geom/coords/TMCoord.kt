package earth.worldwind.geom.coords

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.fromRadians
import kotlin.jvm.JvmStatic

/**
 * This class holds a set of Transverse Mercator coordinates along with the
 * corresponding latitude and longitude.
 */
class TMCoord private constructor(val latitude: Angle, val longitude: Angle, val easting: Double, val northing: Double) {
    companion object {
        /**
         * Create a set of Transverse Mercator coordinates from a pair of latitude and longitude,
         * for the given `Globe` and projection parameters.
         *
         * @param latitude the latitude.
         * @param longitude the longitude.
         * @param aOrNull semi-major ellipsoid radius. If this and argument f are non-null and globe is null, will use the specified a and f.
         * @param fOrNull ellipsoid flattening. If this and argument a are non-null and globe is null, will use the specified a and f.
         * @param originLatitude the origin latitude.
         * @param centralMeridian the central meridian longitude.
         * @param falseEasting easting value at the center of the projection in meters.
         * @param falseNorthing northing value at the center of the projection in meters.
         * @param scale scaling factor.
         * @return the corresponding `TMCoord`.
         * or the conversion to TM coordinates fails. If the globe is null conversion will default
         * to using WGS84.
         */
        @JvmStatic
        fun fromLatLon(
            latitude: Angle, longitude: Angle, aOrNull: Double?, fOrNull: Double?,
            originLatitude: Angle, centralMeridian: Angle, falseEasting: Double, falseNorthing: Double, scale: Double
        ): TMCoord {
            var a = aOrNull
            var f = fOrNull
            val converter = TMCoordConverter()
            if (a == null || f == null) {
                a = converter.a
                f = converter.f
            }
            var err = converter.setTransverseMercatorParameters(
                a, f, originLatitude.radians, centralMeridian.radians, falseEasting, falseNorthing, scale
            )
            if (err == TMCoordConverter.NO_ERROR) err = converter.convertGeodeticToTransverseMercator(latitude.radians, longitude.radians)
            require(err == TMCoordConverter.NO_ERROR || err == TMCoordConverter.LON_WARNING) { "TM Conversion Error" }
            return TMCoord(latitude, longitude, converter.easting, converter.northing)
        }

        /**
         * Create a set of Transverse Mercator coordinates for the given `Globe`,
         * easting, northing and projection parameters.
         *
         * @param easting the easting distance value in meters.
         * @param northing the northing distance value in meters.
         * @param originLatitude the origin latitude `double`.
         * @param centralMeridian the central meridian longitude `double`.
         * @param falseEasting easting value at the center of the projection in meters.
         * @param falseNorthing northing value at the center of the projection in meters.
         * @param scale scaling factor.
         * @return the corresponding `TMCoord`.
         * @throws IllegalArgumentException if the conversion to geodetic coordinates fails.
         * If the globe is null conversion will default to using WGS84.
         */
        @JvmStatic
        fun fromTM(
            easting: Double, northing: Double, originLatitude: Angle, centralMeridian: Angle,
            falseEasting: Double, falseNorthing: Double, scale: Double
        ): TMCoord {
            val converter = TMCoordConverter()
            val a = converter.a
            val f = converter.f
            var err = converter.setTransverseMercatorParameters(
                a, f, originLatitude.radians, centralMeridian.radians, falseEasting, falseNorthing, scale
            )
            if (err == TMCoordConverter.NO_ERROR) err = converter.convertTransverseMercatorToGeodetic(easting, northing)
            require(err == TMCoordConverter.NO_ERROR || err == TMCoordConverter.LON_WARNING) { "TM Conversion Error" }
            return TMCoord(fromRadians(converter.latitude), fromRadians(converter.longitude), easting, northing)
        }
    }
}