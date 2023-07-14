package earth.worldwind.geom.coords

import earth.worldwind.geom.Ellipsoid
import kotlin.math.*

/*
 * Converter used to translate Transverse Mercator coordinates to and from geodetic latitude and longitude.
 * Ported to Kotlin from the NGA GeoTrans code tranmerc.c and tranmerc.h
 */
internal class TMCoordConverter {
    companion object {
        const val NO_ERROR = 0x0000
        const val LAT_ERROR = 0x0001
        const val LON_ERROR = 0x0002
        const val EASTING_ERROR = 0x0004
        const val NORTHING_ERROR = 0x0008
        const val ORIGIN_LAT_ERROR = 0x0010
        const val CENT_MER_ERROR = 0x0020
        const val A_ERROR = 0x0040
        const val INV_F_ERROR = 0x0080
        const val SCALE_FACTOR_ERROR = 0x0100
        const val LON_WARNING = 0x0200
        private const val MAX_LAT = PI * 89.99 / 180.0 /* 90 degrees in radians */
        private const val MAX_DELTA_LONG = PI * 90 / 180.0 /* 90 degrees in radians */
        private const val MIN_SCALE_FACTOR = 0.3
        private const val MAX_SCALE_FACTOR = 3.0
    }

    /* Ellipsoid Parameters, default to WGS 84  */
    private val ellipsoid = Ellipsoid.WGS84
    var a = ellipsoid.semiMajorAxis /* Semi-major axis of ellipsoid i meters */
        private set
    var f = 1 / ellipsoid.inverseFlattening /* Flattening of ellipsoid  */
        private set
    private var es = 0.0066943799901413800 /* Eccentricity (0.08181919084262188000) squared */
    private var ebs = 0.0067394967565869 /* Second Eccentricity squared */

    /* Transverse_Mercator projection Parameters */
    private var originLat = 0.0 /* Latitude of origin in radians */
    private var originLong = 0.0 /* Longitude of origin in radians */
    private var falseNorthing = 0.0 /* False northing in meters */
    private var falseEasting = 0.0 /* False easting in meters */
    private var scaleFactor = 1.0 /* Scale factor  */

    /* Isometric to geodetic latitude parameters, default to WGS 84 */
    private var ap = 6367449.1458008
    private var bp = 16038.508696861
    private var cp = 16.832613334334
    private var dp = 0.021984404273757
    private var ep = 3.1148371319283e-005

    /* Maximum variance for easting and northing values for WGS 84. */
    private var deltaEasting = 40000000.0
    private var deltaNorthing = 40000000.0

    /** Easting/X at the center of the projection */
    var easting = 0.0
        private set
    /** Northing/Y at the center of the projection */
    var northing = 0.0
        private set
    /** Latitude in radians. */
    var latitude = 0.0
        private set
    /** Longitude in radians. */
    var longitude = 0.0
        private set

    /**
     * The function receives the ellipsoid parameters and Transverse Mercator
     * projection parameters as inputs, and sets the corresponding state variables. If any errors occur, the error
     * code(s) are returned by the function, otherwise NO_ERROR is returned.
     *
     * @param a                Semi-major axis of ellipsoid, in meters
     * @param f                Flattening of ellipsoid
     * @param latitude  Latitude in radians at the origin of the projection
     * @param centralMeridian Longitude in radians at the center of the projection
     * @param easting    Easting/X at the center of the projection
     * @param northing   Northing/Y at the center of the projection
     * @param scaleFactor     Projection scale factor
     *
     * @return error code
     */
    fun setTransverseMercatorParameters(
        a: Double, f: Double, latitude: Double, centralMeridian: Double, easting: Double, northing: Double, scaleFactor: Double
    ): Int {
        var cm = centralMeridian
        val invF = 1 / f
        var errorCode = NO_ERROR
        /* Semi-major axis must be greater than zero */
        if (a <= 0.0) errorCode = errorCode or A_ERROR
        /* Inverse flattening must be between 250 and 350 */
        if (invF < 250 || invF > 350) errorCode = errorCode or INV_F_ERROR
        /* origin latitude out of range */
        if (latitude < -MAX_LAT || latitude > MAX_LAT) errorCode = errorCode or ORIGIN_LAT_ERROR
        /* origin longitude out of range */
        if (cm < -PI || cm > 2 * PI) errorCode = errorCode or CENT_MER_ERROR
        if (scaleFactor < MIN_SCALE_FACTOR || scaleFactor > MAX_SCALE_FACTOR) {
            errorCode = errorCode or SCALE_FACTOR_ERROR
        }
        /* no errors */
        if (errorCode == NO_ERROR) {
            this.a = a
            this.f = f
            originLat = 0.0
            originLong = 0.0
            falseNorthing = 0.0
            falseEasting = 0.0
            this.scaleFactor = 1.0

            /* Eccentricity Squared */
            es = 2 * this.f - this.f * this.f
            /* Second Eccentricity Squared */
            ebs = 1 / (1 - es) - 1
            val b = this.a * (1 - this.f)
            /*True meridional constants  */
            val tn = (this.a - b) / (this.a + b)
            val tn2 = tn * tn
            val tn3 = tn2 * tn
            val tn4 = tn3 * tn
            val tn5 = tn4 * tn
            ap = this.a * (1e0 - tn + 5e0 * (tn2 - tn3) / 4e0 + 81e0 * (tn4 - tn5) / 64e0)
            bp = 3e0 * this.a * (tn - tn2 + (7e0 * (tn3 - tn4) / 8e0) + 55e0 * tn5 / 64e0) / 2e0
            cp = 15e0 * this.a * (tn2 - tn3 + 3e0 * (tn4 - tn5) / 4e0) / 16.0
            dp = 35e0 * this.a * (tn3 - tn4 + 11e0 * tn5 / 16e0) / 48e0
            ep = 315e0 * this.a * (tn4 - tn5) / 512e0
            convertGeodeticToTransverseMercator(MAX_LAT, MAX_DELTA_LONG)
            deltaEasting = this.easting
            deltaNorthing = this.northing
            convertGeodeticToTransverseMercator(0.0, MAX_DELTA_LONG)
            deltaEasting = this.easting
            originLat = latitude
            if (cm > PI) cm -= 2 * PI
            originLong = cm
            falseNorthing = northing
            falseEasting = easting
            this.scaleFactor = scaleFactor
        }
        return errorCode
    }

    /**
     * The function Convert_Geodetic_To_Transverse_Mercator converts geodetic (latitude and longitude) coordinates to
     * Transverse Mercator projection (easting and northing) coordinates, according to the current ellipsoid and
     * Transverse Mercator projection coordinates.  If any errors occur, the error code(s) are returned by the function,
     * otherwise NO_ERROR is returned.
     *
     * @param latitude  Latitude in radians
     * @param longitude Longitude in radians
     *
     * @return error code
     */
    fun convertGeodeticToTransverseMercator(latitude: Double, longitude: Double): Int {
        var lon = longitude
        var errorCode = NO_ERROR
        /* Latitude out of range */
        if (latitude < -MAX_LAT || latitude > MAX_LAT) errorCode = errorCode or LAT_ERROR
        if (lon > PI) lon -= 2 * PI
        if (lon < originLong - MAX_DELTA_LONG || lon > originLong + MAX_DELTA_LONG) {
            val tempLong = if (lon < 0) lon + 2 * PI else lon
            val tempOrigin = if (originLong < 0) originLong + 2 * PI else originLong
            if (tempLong < tempOrigin - MAX_DELTA_LONG || tempLong > tempOrigin + MAX_DELTA_LONG)
                errorCode = errorCode or LON_ERROR
        }
        /* no errors */
        if (errorCode == NO_ERROR) {
            /*
             *  Delta Longitude
             */
            var dLam = lon - originLong
            /* Distortion will result if Longitude is more than 9 degrees from the Central Meridian */
            if (abs(dLam) > 9.0 * PI / 180) errorCode = errorCode or LON_WARNING
            if (dLam > PI) dLam -= 2 * PI
            if (dLam < -PI) dLam += 2 * PI
            if (abs(dLam) < 2e-10) dLam = 0.0
            val s = sin(latitude)
            val c = cos(latitude)
            val c2 = c * c
            val c3 = c2 * c
            val c5 = c3 * c2
            val c7 = c5 * c2
            val t = tan(latitude)
            val tan2 = t * t
            val tan3 = tan2 * t
            val tan4 = tan3 * t
            val tan5 = tan4 * t
            val tan6 = tan5 * t
            val eta = ebs * c2
            val eta2 = eta * eta
            val eta3 = eta2 * eta
            val eta4 = eta3 * eta

            /* radius of curvature in prime vertical */
            val sn = a / sqrt(1 - es * sin(latitude).pow(2))

            /* True Meridional Distances */
            val tmd = (ap * latitude
                    - bp * sin(2.0 * latitude)
                    + cp * sin(4.0 * latitude)
                    - dp * sin(6.0 * latitude)
                    + ep * sin(8.0 * latitude))

            /*  Origin  */
            val tmdO = (ap * originLat
                    - bp * sin(2.0 * originLat)
                    + cp * sin(4.0 * originLat)
                    - dp * sin(6.0 * originLat)
                    + ep * sin(8.0 * originLat))

            /* northing */
            val t1 = (tmd - tmdO) * scaleFactor
            val t2 = sn * s * c * scaleFactor / 2e0
            val t3 = sn * s * c3 * scaleFactor * (5e0 - tan2 + 9e0 * eta + 4e0 * eta2) / 24e0
            val t4 = sn * s * c5 * scaleFactor * (61e0 - 58e0 * tan2 + tan4 + 270e0 * eta - 330e0 * tan2 * eta + 445e0 * eta2 + 324e0 * eta3 - 680e0 * tan2 * eta2 + 88e0 * eta4 - 600e0 * tan2 * eta3 - 192e0 * tan2 * eta4) / 720e0
            val t5 = sn * s * c7 * scaleFactor * (1385e0 - 3111e0 * tan2 + 543e0 * tan4 - tan6) / 40320e0
            northing = falseNorthing + t1 + dLam.pow(2e0) * t2 + dLam.pow(4e0) * t3 + dLam.pow(6e0) * t4 + dLam.pow(8e0) * t5

            /* Easting */
            val t6 = sn * c * scaleFactor
            val t7 = sn * c3 * scaleFactor * (1e0 - tan2 + eta) / 6e0
            val t8 = sn * c5 * scaleFactor * ((5e0 - 18e0 * tan2 + tan4 + 14e0 * eta) - 58e0 * tan2 * eta + 13e0 * eta2 + 4e0 * eta3 - 64e0 * tan2 * eta2 - 24e0 * tan2 * eta3) / 120e0
            val t9 = sn * c7 * scaleFactor * (61e0 - 479e0 * tan2 + 179e0 * tan4 - tan6) / 5040e0
            easting = falseEasting + dLam * t6 + dLam.pow(3e0) * t7 + dLam.pow(5e0) * t8 + dLam.pow(7e0) * t9
        }
        return errorCode
    }

    /**
     * The function Convert_Transverse_Mercator_To_Geodetic converts Transverse Mercator projection (easting and
     * northing) coordinates to geodetic (latitude and longitude) coordinates, according to the current ellipsoid and
     * Transverse Mercator projection parameters.  If any errors occur, the error code(s) are returned by the function,
     * otherwise NO_ERROR is returned.
     *
     * @param easting  Easting/X in meters
     * @param northing Northing/Y in meters
     *
     * @return error code
     */
    fun convertTransverseMercatorToGeodetic(easting: Double, northing: Double): Int {
        var errorCode = NO_ERROR
        /* Easting out of range  */
        if (easting < falseEasting - deltaEasting || easting > falseEasting + deltaEasting)
            errorCode = errorCode or EASTING_ERROR
        /* Northing out of range */
        if (northing < falseNorthing - deltaNorthing || northing > falseNorthing + deltaNorthing)
            errorCode = errorCode or NORTHING_ERROR
        if (errorCode == NO_ERROR) {
            /* True Meridional Distances for latitude of origin */
            val tmdO = (ap * originLat
                    - bp * sin(2.0 * originLat)
                    + cp * sin(4.0 * originLat)
                    - dp * sin(6.0 * originLat)
                    + ep * sin(8.0 * originLat))

            /*  Origin  */
            val tmd = tmdO + (northing - falseNorthing) / scaleFactor

            /* First Estimate */
            var sr = a * (1e0 - es) / sqrt(1e0 - es * sin(0e0).pow(2)).pow(3)
            var ftphi = tmd / sr
            for (i in 0..4) {
                val t10 = (ap * ftphi
                        - bp * sin(2.0 * ftphi)
                        + cp * sin(4.0 * ftphi)
                        - dp * sin(6.0 * ftphi)
                        + ep * sin(8.0 * ftphi))
                sr = a * (1e0 - es) / sqrt(1e0 - es * sin(ftphi).pow(2)).pow(3)
                ftphi += (tmd - t10) / sr
            }

            /* Radius of Curvature in the meridian */
            sr = a * (1e0 - es) / sqrt(1e0 - es * sin(ftphi).pow(2)).pow(3)

            /* Radius of Curvature in the meridian */
            val sn = a / sqrt(1e0 - es * sin(ftphi).pow(2))

            /* Sine Cosine terms */
            val c = cos(ftphi)

            /* Tangent Value  */
            val t = tan(ftphi)
            val tan2 = t * t
            val tan4 = tan2 * tan2
            val eta = ebs * c.pow(2)
            val eta2 = eta * eta
            val eta3 = eta2 * eta
            val eta4 = eta3 * eta
            var de = easting - falseEasting
            if (abs(de) < 0.0001) de = 0.0

            /* Latitude */
            val t10 = t / (2e0 * sr * sn * scaleFactor.pow(2))
            val t11 = t * (5e0 + 3e0 * tan2 + eta - 4e0 * eta.pow(2) - 9e0 * tan2 * eta) / (24e0 * sr * sn.pow(3) * scaleFactor.pow(4))
            val t12 = (t * ((61e0 + 90e0 * tan2 + 46e0 * eta + 45e0 * tan4 - 252e0 * tan2 * eta - 3e0 * eta2 + 100e0
                        * eta3) - 66e0 * tan2 * eta2 - (90e0 * tan4
                        * eta) + 88e0 * eta4 + 225e0 * tan4 * eta2 + 84e0 * tan2 * eta3 - 192e0 * tan2 * eta4)
                        / (720e0 * sr * sn.pow(5) * scaleFactor.pow(6)))
            val t13 = t * (1385e0 + 3633e0 * tan2 + 4095e0 * tan4 + (1575e0 * t.pow(6))) / (40320e0 * sr * sn.pow(7) * scaleFactor.pow(8))
            latitude = (ftphi - de.pow(2) * t10 + de.pow(4) * t11 - de.pow(6) * t12 + de.pow(8) * t13)
            val t14 = 1e0 / (sn * c * scaleFactor)
            val t15 = (1e0 + 2e0 * tan2 + eta) / (6e0 * sn.pow(3) * c * scaleFactor.pow(3))
            val t16 = ((5e0 + 6e0 * eta + 28e0 * tan2 - 3e0 * eta2 + 8e0 * tan2 * eta + 24e0 * tan4 - 4e0
                        * eta3) + 4e0 * tan2 * eta2 + (24e0 * tan2 * eta3)) / (120e0 * sn.pow(5) * c * scaleFactor.pow(5))
            val t17 = (61e0 + 662e0 * tan2 + 1320e0 * tan4 + (720e0 * t.pow(6))) / (5040e0 * sn.pow(7) * c * scaleFactor.pow(7))

            /* Difference in Longitude */
            val dLam = de * t14 - de.pow(3) * t15 + de.pow(5) * t16 - de.pow(7) * t17

            /* Longitude */
            longitude = originLong + dLam
            if (abs(latitude) > 90.0 * PI / 180.0) errorCode = errorCode or NORTHING_ERROR
            if (longitude > PI) {
                longitude -= 2 * PI
                if (abs(longitude) > PI) errorCode = errorCode or EASTING_ERROR
            }
            if (abs(dLam) > 9.0 * PI / 180 * cos(latitude)) {
                /* Distortion will result if Longitude is more than 9 degrees from the Central Meridian at the equator */
                /* and decreases to 0 degrees at the poles */
                /* As you move towards the poles, distortion will become more significant */
                errorCode = errorCode or LON_WARNING
            }
            if (latitude > 1.0e10) errorCode = errorCode or LON_WARNING
        }
        return errorCode
    }
}
