package earth.worldwind.geom.coords

import earth.worldwind.geom.Ellipsoid
import kotlin.math.*

/**
 * Converter used to translate Gauss-Kruger rectangular coordinates to and from geodetic latitude and longitude.
 */
internal class GKCoordConverter {
    companion object {
        const val NO_ERROR = 0x0000
    }

    private val ellipsoid = Ellipsoid.Krasovsky

    /** Easting (Y) in meters */
    var easting = 0.0
        private set
    /** Northing (X) in meters */
    var northing = 0.0
        private set
    /** Latitude in radians. */
    var latitude = 0.0
        private set
    /** Longitude in radians.*/
    var longitude = 0.0
        private set

    /**
     * The function Convert_Geodetic_To_GK converts geodetic (latitude and longitude) coordinates to Gauss-Kruger
     * projection (easting and northing) coordinates according to the Krasovsky ellipsoid.
     *
     * @param lat Latitude in radians
     * @param lon Longitude in radians
     *
     * @return error code
     */
    fun convertGeodeticToGK(lat: Double, lon: Double): Int {
        val zone = (lon * 180.0 / PI / 6.0 + 1).toInt()

        val a = ellipsoid.semiMajorAxis
        val b = ellipsoid.semiMinorAxis
        val e2 = (a * a - b * b) / (a * a)
        val n = (a - b) / (a + b)
        val nPow2 = n * n
        val nPow3 = n * n * n

        val f = 1.0
        val lat0 = 0.0
        val lon0 = (zone * 6 - 3) * PI / 180.0
        val n0 = 0.0
        val e0 = zone * 1e6 + 500000.0

        val dLon = lon - lon0
        val dLat = lat - lat0
        val pLat = lat + lat0


        val sinLat = sin(lat)
        val sinLatPow2 = sinLat * sinLat
        val cosLat = cos(lat)
        val cosLatPow3 = cosLat * cosLat * cosLat
        val cosLatPow5 = cosLat * cosLat * cosLat * cosLat * cosLat
        val tanLat = tan(lat)
        val tanLatPow2 = tanLat * tanLat
        val tanLatPow4 = tanLatPow2 * tanLatPow2

        val v = a * f * (1.0 - e2 * sinLatPow2).pow(-0.5)
        val p = a * f * (1.0 - e2) * (1 - e2 * sinLatPow2).pow(-1.5)
        val n2 = v / p - 1.0
        val m1 = (1.0 + n + 5.0 / 4.0 * nPow2 + 5.0 / 4.0 * nPow3) * dLat
        val m2 = (3.0 * n + 3.0 * nPow2 + 21.0 / 8.0 * nPow3) * sin(dLat) * cos(pLat)
        val m3 = (15.0 / 8.0 * nPow2 + 15.0 / 8.0 * nPow3) * sin(2.0 * dLat) * cos(2 * pLat)
        val m4 = 35.0 / 24.0 * nPow3 * sin(3 * dLat) * cos(3.0 * pLat)
        val m = b * f * (m1 - m2 + m3 - m4)
        val i = m + n0
        val ii = v / 2.0 * sinLat * cosLat
        val iii = v / 24.0 * sinLat * cosLatPow3 * (5.0 - tanLatPow2 + 9.0 * n2)
        val iiia = v / 720.0 * sinLat * cosLatPow5 * (61.0 - 58.0 * tanLatPow2 + tanLatPow4)
        val iv = v * cosLat
        val V = v / 6.0 * cosLatPow3 * (v / p - tanLatPow2)
        val VI = v / 120.0 * cosLatPow5 * (5.0 - 18.0 * tanLatPow2 + tanLatPow4 + 14 * n2 - 58 * tanLatPow2 * n2)

        easting = e0 + iv * (lon - lon0) + V * dLon * dLon * dLon + VI * dLon * dLon * dLon * dLon * dLon
        northing = i + ii * dLon * dLon + iii * dLon * dLon * dLon * dLon + iiia * dLon * dLon * dLon * dLon * dLon * dLon

        return NO_ERROR
    }

    fun convertGKToGeodetic(easting: Double, northing: Double): Int {
        val zone = (easting / 1e6).toInt()
        val l0 = (6.0 * (if (zone <= 30) zone else -(60 - zone)) - 3.0) * PI / 180.0
        val x = northing
        val y = easting - (zone * 1e6 + 500000.0)
        val beta = x / 6367558.497
        val cosBeta = cos(beta)
        val cos2beta = cosBeta * cosBeta
        val bx = ((2382 * cos2beta + 293609) * cos2beta + 50221747) * sin(beta) * cos(beta) * 1e-10 + beta
        val cosBx = cos(bx)
        val cos2Bx = cosBx * cosBx
        val sinBx = sin(bx)
        val sin2Bx = sinBx * sinBx
        val a22 = (0.003369263 * cos2Bx + 0.5) * sinBx * cosBx
        val a24 = ((0.0056154 - 0.0000151 * cos2Bx) * cos2Bx + 0.1616128) * cos2Bx + 0.25
        val a26 = ((0.00389 * cos2Bx + 0.04310) * cos2Bx - 0.00168) * cos2Bx + 0.125
        val a28 = ((0.013 * cos2Bx + 0.008) * cos2Bx - 0.031) * cos2Bx + 0.078
        val b13 = (1 / 6.0 - 0.00112309 * cos2Bx) * cos2Bx - 1 / 3.0
        val b15 = ((0.008783 - 0.000112 * cos2Bx) * cos2Bx - 1 / 6.0) * cos2Bx + 0.2
        val b17 = (1 / 6.0 - 0.0361 * cos2Bx) * cos2Bx - 0.1429
        val b19 = ((0.064 - 0.004 * cos2Bx) * cos2Bx - 1 / 6.0) * cos2Bx + 1 / 9.0
        val nx = ((0.605 * sin2Bx + 107.155) * sin2Bx + 21346.142) * sin2Bx + ellipsoid.semiMajorAxis
        val z = y / (nx * cosBx)

        latitude = bx + (((a28 * z * z - a26) * z * z + a24) * z * z - 1) * z * z * a22
        longitude = l0 + ((((b19 * z * z + b17) * z * z + b15) * z * z + b13) * z * z + 1) * z

        return NO_ERROR
    }

}