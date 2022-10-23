package earth.worldwind.geom.coords

import earth.worldwind.geom.Angle.Companion.radians
import kotlin.math.PI

/*
 * Converter used to translate UTM coordinates to and from geodetic latitude and longitude.
 * Ported to Kotlin from the NGA GeoTrans utm.c and utm.h
 */
internal class UTMCoordConverter {
    companion object {
        const val NO_ERROR = 0x0000
        const val LAT_ERROR = 0x0001
        const val LON_ERROR = 0x0002
        const val EASTING_ERROR = 0x0004
        const val NORTHING_ERROR = 0x0008
        const val ZONE_ERROR = 0x0010
        const val HEMISPHERE_ERROR = 0x0020
        const val ZONE_OVERRIDE_ERROR = 0x0040
        const val TM_ERROR = 0x0200
        private const val MIN_LAT = -82 * PI / 180.0 /* -82 degrees in radians    */
        private const val MAX_LAT = 86 * PI / 180.0 /* 86 degrees in radians     */
        private const val MIN_EASTING = 100000
        private const val MAX_EASTING = 900000
        private const val MIN_NORTHING = 0
        private const val MAX_NORTHING = 10000000
    }

    private val a = 6378137.0 /* Semi-major axis of ellipsoid in meters  */
    private val f = 1 / 298.257223563 /* Flattening of ellipsoid                 */
    private val override = 0 /* Zone override flag                      */
    private var centralMeridian = 0.0

    var hemisphere = Hemisphere.N
        private set
    /** Easting (X) in meters */
    var easting = 0.0
        private set
    /** Northing (Y) in meters */
    var northing = 0.0
        private set
    /** UTM zone */
    var zone = 0
        private set
    /** Latitude in radians. */
    var latitude = 0.0
        private set
    /** Longitude in radians.*/
    var longitude = 0.0
        private set

    /**
     * The function Convert_Geodetic_To_UTM converts geodetic (latitude and longitude) coordinates to UTM projection
     * (zone, hemisphere, easting and northing) coordinates according to the current ellipsoid and UTM zone override
     * parameters.  If any errors occur, the error code(s) are returned by the function, otherwise UTM_NO_ERROR is
     * returned.
     *
     * @param latitude  Latitude in radians
     * @param longitude Longitude in radians
     *
     * @return error code
     */
    fun convertGeodeticToUTM(latitude: Double, longitude: Double): Int {
        var lon = longitude
        var errorCode = NO_ERROR
        val originLatitude = 0.0
        val falseEasting = 500000.0
        var falseNorthing = 0.0
        val scale = 0.9996
        /* Latitude out of range */
        if (latitude < MIN_LAT || latitude > MAX_LAT) errorCode = errorCode or LAT_ERROR
        /* Longitude out of range */
        if (lon < -PI || lon > 2 * PI) errorCode = errorCode or LON_ERROR
        /* no errors */
        if (errorCode == NO_ERROR) {
            if (lon < 0) lon += 2 * PI + 1.0e-10
            val latDegrees = (latitude * 180.0 / PI).toInt()
            val lonDegrees = (lon * 180.0 / PI).toInt()
            var tempZone = (if (lon < PI) 31 + lon * 180.0 / PI / 6.0 else lon * 180.0 / PI / 6.0 - 29).toInt()
            if (tempZone > 60) tempZone = 1
            /* UTM special cases */
            if (latDegrees in 56..63 && lonDegrees > -1 && lonDegrees < 3) tempZone = 31
            if (latDegrees in 56..63 && lonDegrees > 2 && lonDegrees < 12) tempZone = 32
            if (latDegrees > 71 && lonDegrees > -1 && lonDegrees < 9) tempZone = 31
            if (latDegrees > 71 && lonDegrees > 8 && lonDegrees < 21) tempZone = 33
            if (latDegrees > 71 && lonDegrees > 20 && lonDegrees < 33) tempZone = 35
            if (latDegrees > 71 && lonDegrees > 32 && lonDegrees < 42) tempZone = 37
            if (override != 0) {
                if (tempZone == 1 && override == 60) tempZone = override
                else if (tempZone == 60 && override == 1) tempZone = override
                else if (tempZone - 1 <= override && override <= tempZone + 1) tempZone = override
                else errorCode = ZONE_OVERRIDE_ERROR
            }
            if (errorCode == NO_ERROR) {
                centralMeridian = if (tempZone >= 31) (6 * tempZone - 183) * PI / 180.0 else (6 * tempZone + 177) * PI / 180.0
                zone = tempZone
                if (latitude < 0) {
                    falseNorthing = 10000000.0
                    hemisphere = Hemisphere.S
                } else hemisphere = Hemisphere.N
                try {
                    val tm = TMCoord.fromLatLon(
                        latitude.radians, lon.radians, a, f, originLatitude.radians,
                        centralMeridian.radians, falseEasting, falseNorthing, scale
                    )
                    easting = tm.easting
                    northing = tm.northing
                    if (easting < MIN_EASTING || easting > MAX_EASTING) errorCode = EASTING_ERROR
                    if (northing < MIN_NORTHING || northing > MAX_NORTHING) errorCode = errorCode or NORTHING_ERROR
                } catch (e: Exception) {
                    errorCode = TM_ERROR
                }
            }
        }
        return errorCode
    }

    /**
     * The function Convert_UTM_To_Geodetic converts UTM projection (zone, hemisphere, easting and northing) coordinates
     * to geodetic(latitude and  longitude) coordinates, according to the current ellipsoid parameters.  If any errors
     * occur, the error code(s) are returned by the function, otherwise UTM_NO_ERROR is returned.
     *
     * @param zone       UTM zone.
     * @param hemisphere The coordinate hemisphere, either [Hemisphere.N] of [Hemisphere.S].
     * @param easting    easting (X) in meters.
     * @param northing   Northing (Y) in meters.
     *
     * @return error code.
     */
    fun convertUTMToGeodetic(zone: Int, hemisphere: Hemisphere, easting: Double, northing: Double): Int {
        var errorCode = NO_ERROR
        val originLatitude = 0.0
        val falseEasting = 500000.0
        var falseNorthing = 0.0
        val scale = 0.9996
        if (zone < 1 || zone > 60) errorCode = errorCode or ZONE_ERROR
        if (hemisphere != Hemisphere.S && hemisphere != Hemisphere.N) errorCode = errorCode or HEMISPHERE_ERROR
        if (northing < MIN_NORTHING || northing > MAX_NORTHING) errorCode = errorCode or NORTHING_ERROR
        /* no errors */
        if (errorCode == NO_ERROR) {
            centralMeridian = if (zone >= 31) (6 * zone - 183) * PI / 180.0 else (6 * zone + 177) * PI / 180.0
            if (hemisphere == Hemisphere.S) falseNorthing = 10000000.0
            try {
                val tm = TMCoord.fromTM(
                    easting, northing,
                    originLatitude.radians, centralMeridian.radians,
                    falseEasting, falseNorthing, scale
                )
                latitude = tm.latitude.radians
                longitude = tm.longitude.radians
                /* Latitude out of range */
                if (latitude < MIN_LAT || latitude > MAX_LAT) errorCode = errorCode or NORTHING_ERROR
            } catch (e: Exception) {
                errorCode = TM_ERROR
            }
        }
        return errorCode
    }
}