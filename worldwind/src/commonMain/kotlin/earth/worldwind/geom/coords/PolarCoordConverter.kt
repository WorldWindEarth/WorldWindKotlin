package earth.worldwind.geom.coords

import kotlin.math.*

/**
 * Ported to Kotlin from the NGA GeoTrans polarst.c and polarst.h code.
 */
internal class PolarCoordConverter {
    companion object {
        private const val NO_ERROR = 0x0000
        private const val LAT_ERROR = 0x0001
        private const val LON_ERROR = 0x0002
        private const val ORIGIN_LAT_ERROR = 0x0004
        private const val ORIGIN_LON_ERROR = 0x0008
        const val EASTING_ERROR = 0x0010
        const val NORTHING_ERROR = 0x0020
        private const val A_ERROR = 0x0040
        private const val INV_F_ERROR = 0x0080
        const val RADIUS_ERROR = 0x0100
        private const val PI_OVER_2 = PI / 2.0
        private const val PI_Over_4 = PI / 4.0
        private const val TWO_PI = 2.0 * PI
    }

    /* Ellipsoid Parameters, default to WGS 84  */
    private var a = 6378137.0 /* Semi-major axis of ellipsoid in meters  */
    private var f = 1 / 298.257223563 /* Flattening of ellipsoid  */
    private var es = 0.08181919084262188000 /* Eccentricity of ellipsoid    */
    private var esOver2 = .040909595421311 /* es / 2.0 */
    private var southernHemisphere = 0.0 /* Flag variable */
    private var mc = 1.0
    private var tc = 1.0
    private var e4 = 1.0033565552493
    private var amc = 6378137.0 /* Polar_a * mc */
    private var twoA = 12756274.0 /* 2.0 * Polar_a */

    /* Polar Stereographic projection Parameters */
    private var originLat = PI * 90 / 180 /* Latitude of origin in radians */
    private var originLong = 0.0 /* Longitude of origin in radians */
    private var falseEasting = 0.0 /* False easting in meters */
    private var falseNorthing = 0.0 /* False northing in meters */

    /* Maximum variance for easting and northing values for WGS 84. */
    private var deltaEasting = 12713601.0
    private var deltaNorthing = 12713601.0

    var easting = 0.0
        private set
    var northing = 0.0
        private set
    var latitude = 0.0
        private set
    var longitude = 0.0
        private set

    /**
     * The function setPolarStereographicParameters receives the ellipsoid parameters and Polar Stereographic projection
     * parameters as inputs, and sets the corresponding state variables.  If any errors occur, error code(s) are
     * returned by the function, otherwise POLAR_NO_ERROR is returned.
     *
     * @param a              Semi-major axis of ellipsoid, in meters
     * @param f              Flattening of ellipsoid
     * @param latitude       Latitude of true scale, in radians
     * @param longitude      Longitude down from pole, in radians
     * @param easting  Easting (X) at center of projection, in meters
     * @param northing Northing (Y) at center of projection, in meters
     * @return error code
     */
    fun setPolarStereographicParameters(
        a: Double, f: Double, latitude: Double, longitude: Double, easting: Double, northing: Double
    ): Int {
        var lon = longitude
        val invF = 1 / f
        val epsilon = 1.0e-2
        var errorCode = NO_ERROR
        /* Semi-major axis must be greater than zero */
        if (a <= 0.0) errorCode = errorCode or A_ERROR
        /* Inverse flattening must be between 250 and 350 */
        if (invF < 250 || invF > 350) errorCode = errorCode or INV_F_ERROR
        /* Origin Latitude out of range */
        if (latitude < -PI_OVER_2 || latitude > PI_OVER_2) errorCode = errorCode or ORIGIN_LAT_ERROR
        /* Origin Longitude out of range */
        if (lon < -PI || lon > TWO_PI) errorCode = errorCode or ORIGIN_LON_ERROR
        /* no errors */
        if (errorCode == NO_ERROR) {
            this.a = a
            twoA = 2.0 * this.a
            this.f = f
            if (lon > PI) lon -= TWO_PI
            if (latitude < 0) {
                southernHemisphere = 1.0
                originLat = -latitude
                originLong = -lon
            } else {
                southernHemisphere = 0.0
                originLat = latitude
                originLong = lon
            }
            falseEasting = easting
            falseNorthing = northing
            val es2 = 2 * this.f - this.f * this.f
            es = sqrt(es2)
            esOver2 = es / 2.0
            if (abs(abs(originLat) - PI_OVER_2) > 1.0e-10) {
                val sLat = sin(originLat)
                val esSin = es * sLat
                val powEs = ((1.0 - esSin) / (1.0 + esSin)).pow(esOver2)
                val cLat = cos(originLat)
                mc = cLat / sqrt(1.0 - esSin * esSin)
                amc = this.a * mc
                tc = tan(PI_Over_4 - originLat / 2.0) / powEs
            } else {
                val onePlusEs = 1.0 + es
                val oneMinusEs = 1.0 - es
                e4 = sqrt(onePlusEs.pow(onePlusEs) * oneMinusEs.pow(oneMinusEs))
            }
        }

        /* Calculate Radius */
        convertGeodeticToPolarStereographic(0.0, originLong)
        deltaNorthing = this.northing * 2 // Increased range for accepted easting and northing values
        deltaNorthing = abs(deltaNorthing) + epsilon
        deltaEasting = deltaNorthing
        return errorCode
    }

    /**
     * The function Convert_Geodetic_To_Polar_Stereographic converts geodetic coordinates (latitude and longitude) to
     * Polar Stereographic coordinates (easting and northing), according to the current ellipsoid and Polar
     * Stereographic projection parameters. If any errors occur, error code(s) are returned by the function, otherwise
     * POLAR_NO_ERROR is returned.
     *
     * @param latitude  latitude, in radians
     * @param longitude Longitude, in radians
     * @return error code
     */
    fun convertGeodeticToPolarStereographic(latitude: Double, longitude: Double): Int {
        var lat = latitude
        var lon = longitude
        var errorCode = NO_ERROR
        /* Latitude out of range */
        if (lat < -PI_OVER_2 || lat > PI_OVER_2) errorCode = errorCode or LAT_ERROR
        /* Latitude and Origin Latitude in different hemispheres */
        if (lat < 0 && southernHemisphere == 0.0) errorCode = errorCode or LAT_ERROR
        /* Latitude and Origin Latitude in different hemispheres */
        if (lat > 0 && southernHemisphere == 1.0) errorCode = errorCode or LAT_ERROR
        /* Longitude out of range */
        if (lon < -PI || lon > TWO_PI) errorCode = errorCode or LON_ERROR
        /* no errors */
        if (errorCode == NO_ERROR) {
            if (abs(abs(lat) - PI_OVER_2) < 1.0e-10) {
                easting = 0.0
                northing = 0.0
            } else {
                if (southernHemisphere != 0.0) {
                    lon *= -1.0
                    lat *= -1.0
                }
                var dLam = lon - originLong
                if (dLam > PI) dLam -= TWO_PI
                if (dLam < -PI) dLam += TWO_PI
                val sLat = sin(lat)
                val esSin = es * sLat
                val powEs = ((1.0 - esSin) / (1.0 + esSin)).pow(esOver2)
                val t = tan(PI_Over_4 - lat / 2.0) / powEs
                val rho = if (abs(abs(originLat) - PI_OVER_2) > 1.0e-10) amc * t / tc else twoA * t / e4
                if (southernHemisphere != 0.0) {
                    easting = -(rho * sin(dLam) - falseEasting)
                    northing = rho * cos(dLam) + falseNorthing
                } else easting = rho * sin(dLam) + falseEasting
                northing = -rho * cos(dLam) + falseNorthing
            }
        }
        return errorCode
    }

    /**
     * The function Convert_Polar_Stereographic_To_Geodetic converts Polar
     * Stereographic coordinates (easting and northing) to geodetic
     * coordinates (latitude and longitude) according to the current ellipsoid
     * and Polar Stereographic projection Parameters. If any errors occur, the
     * code(s) are returned by the function, otherwise POLAR_NO_ERROR
     * is returned.
     *
     * @param Easting Easting (X), in meters
     * @param Northing Northing (Y), in meters
     * @return error code
     */
    fun convertPolarStereographicToGeodetic(Easting: Double, Northing: Double): Int {
        var dy = 0.0
        var dx = 0.0
        var rho = 0.0
        var tempPhi = 0.0
        var errorCode = NO_ERROR
        val minEasting = falseEasting - deltaEasting
        val maxEasting = falseEasting + deltaEasting
        val minNorthing = falseNorthing - deltaNorthing
        val maxNorthing = falseNorthing + deltaNorthing
        /* Easting out of range */
        if (Easting > maxEasting || Easting < minEasting) errorCode = errorCode or EASTING_ERROR
        /* Northing out of range */
        if (Northing > maxNorthing || Northing < minNorthing) errorCode = errorCode or NORTHING_ERROR
        if (errorCode == NO_ERROR) {
            dy = Northing - falseNorthing
            dx = Easting - falseEasting
            /* Radius of point with origin of false easting, false northing */
            rho = sqrt(dx * dx + dy * dy)
            val deltaRadius = sqrt(deltaEasting * deltaEasting + deltaNorthing * deltaNorthing)
            /* Point is outside of projection area */
            if (rho > deltaRadius) errorCode = errorCode or RADIUS_ERROR
        }
        /* no errors */
        if (errorCode == NO_ERROR) {
            if (dy == 0.0 && dx == 0.0) {
                latitude = PI_OVER_2
                longitude = originLong
            } else {
                if (southernHemisphere != 0.0) {
                    dy *= -1.0
                    dx *= -1.0
                }
                val t = if (abs(abs(originLat) - PI_OVER_2) > 1.0e-10) rho * tc / amc else rho * e4 / twoA
                var phi = PI_OVER_2 - 2.0 * atan(t)
                while (abs(phi - tempPhi) > 1.0e-10) {
                    tempPhi = phi
                    val sinPhi = sin(phi)
                    val esSin = es * sinPhi
                    val powEs = ((1.0 - esSin) / (1.0 + esSin)).pow(esOver2)
                    phi = PI_OVER_2 - 2.0 * atan(t * powEs)
                }
                latitude = phi
                longitude = originLong + atan2(dx, -dy)
                if (longitude > PI) longitude -= TWO_PI
                else if (longitude < -PI) longitude += TWO_PI
                /* force distorted values to 90, -90 degrees */
                if (latitude > PI_OVER_2) latitude = PI_OVER_2
                else if (latitude < -PI_OVER_2) latitude = -PI_OVER_2
                /* force distorted values to 180, -180 degrees */
                if (longitude > PI) longitude = PI else if (longitude < -PI) longitude = -PI
            }
            if (southernHemisphere != 0.0) {
                latitude *= -1.0
                longitude *= -1.0
            }
        }
        return errorCode
    }
}