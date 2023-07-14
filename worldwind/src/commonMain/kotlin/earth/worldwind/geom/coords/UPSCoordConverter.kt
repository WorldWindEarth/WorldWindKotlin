package earth.worldwind.geom.coords

import earth.worldwind.geom.Ellipsoid
import kotlin.math.PI

/**
 * Ported to Kotlin from the NGA GeoTrans ups.c and ups.h code
 */
internal class UPSCoordConverter {
    companion object {
        const val NO_ERROR = 0x0000
        const val LAT_ERROR = 0x0001
        const val LON_ERROR = 0x0002
        const val HEMISPHERE_ERROR = 0x0004
        const val EASTING_ERROR = 0x0008
        const val NORTHING_ERROR = 0x0010
        private const val MAX_LAT = PI * 90 / 180.0 // 90 degrees in radians

        // Min and max latitude values accepted
        private const val MIN_NORTH_LAT = 72 * PI / 180.0 // 83.5
        private const val MIN_SOUTH_LAT = -72 * PI / 180.0 // -79.5
        private const val MAX_ORIGIN_LAT = 81.114528 * PI / 180.0
        private const val MIN_EAST_NORTH = 0.0
        private const val MAX_EAST_NORTH = 4000000.0
    }

    private var originLatitude = MAX_ORIGIN_LAT /*set default = North hemisphere */
    private val originLongitude = 0.0

    /* Ellipsoid Parameters, default to WGS 84  */
    private val ellipsoid = Ellipsoid.WGS84
    private val a = ellipsoid.semiMajorAxis /* Semi-major axis of ellipsoid in meters   */
    private val f = 1 / ellipsoid.inverseFlattening /* Flattening of ellipsoid  */
    private val falseEasting = 2000000.0
    private val falseNorthing = 2000000.0

    var hemisphere = Hemisphere.N
        private set
    /** easting/X in meters */
    var easting = 0.0
        private set
    /** northing/Y in meters */
    var northing = 0.0
        private set
    /** latitude in radians.*/
    var latitude = 0.0
        private set
    /** longitude in radians.*/
    var longitude = 0.0
        private set

    private val polarConverter = PolarCoordConverter()

    /**
     * The function convertGeodeticToUPS converts geodetic (latitude and longitude) coordinates to UPS (hemisphere,
     * easting, and northing) coordinates, according to the current ellipsoid parameters. If any errors occur, the error
     * code(s) are returned by the function, otherwise UPS_NO_ERROR is returned.
     *
     * @param latitude  latitude in radians
     * @param longitude longitude in radians
     *
     * @return error code
     */
    fun convertGeodeticToUPS(latitude: Double, longitude: Double): Int {
        /* latitude out of range */
        if (latitude < -MAX_LAT || latitude > MAX_LAT) return LAT_ERROR
        if (latitude < 0 && latitude > MIN_SOUTH_LAT) return LAT_ERROR
        if (latitude >= 0 && latitude < MIN_NORTH_LAT) return LAT_ERROR
        /* slam out of range */
        if (longitude < -PI || longitude > 2 * PI) return LON_ERROR
        if (latitude < 0) {
            originLatitude = -MAX_ORIGIN_LAT
            hemisphere = Hemisphere.S
        } else {
            originLatitude = MAX_ORIGIN_LAT
            hemisphere = Hemisphere.N
        }
        polarConverter.setPolarStereographicParameters(a, f, originLatitude, originLongitude, 0.0, 0.0)
        polarConverter.convertGeodeticToPolarStereographic(latitude, longitude)
        easting = falseEasting + polarConverter.easting
        northing = falseNorthing + if (Hemisphere.S == hemisphere) - polarConverter.northing else polarConverter.northing
        return NO_ERROR
    }

    /**
     * The function Convert_UPS_To_Geodetic converts UPS (hemisphere, easting, and northing) coordinates to geodetic
     * (latitude and longitude) coordinates according to the current ellipsoid parameters.  If any errors occur, the
     * error code(s) are returned by the function, otherwise UPS_NO_ERROR is returned.
     *
     * @param hemisphere hemisphere, either [Hemisphere.N] of [Hemisphere.S].
     * @param easting    easting/X in meters
     * @param northing   northing/Y in meters
     *
     * @return error code
     */
    fun convertUPSToGeodetic(hemisphere: Hemisphere?, easting: Double, northing: Double): Int{
        var errorCode = NO_ERROR
        if (Hemisphere.N != hemisphere && Hemisphere.S != hemisphere) errorCode = errorCode or HEMISPHERE_ERROR
        if (easting < MIN_EAST_NORTH || easting > MAX_EAST_NORTH) errorCode = errorCode or EASTING_ERROR
        if (northing < MIN_EAST_NORTH || northing > MAX_EAST_NORTH) errorCode = errorCode or NORTHING_ERROR
        if (Hemisphere.N == hemisphere) originLatitude = MAX_ORIGIN_LAT
        if (Hemisphere.S == hemisphere) originLatitude = -MAX_ORIGIN_LAT
        /*  no errors   */
        if (errorCode == NO_ERROR) {
            polarConverter.setPolarStereographicParameters(a, f, originLatitude, originLongitude, falseEasting, falseNorthing)
            polarConverter.convertPolarStereographicToGeodetic(easting, northing)
            latitude = polarConverter.latitude
            longitude = polarConverter.longitude
            if (latitude < 0 && latitude > MIN_SOUTH_LAT) errorCode = errorCode or LAT_ERROR
            if (latitude >= 0 && latitude < MIN_NORTH_LAT) errorCode = errorCode or LAT_ERROR
        }
        return errorCode
    }
}