package earth.worldwind.geom.coords

import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.util.format.format
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.pow

/*
 * Converter used to translate MGRS coordinate strings to and from geodetic latitude and longitude.
 * Ported to Kotlin from the NGA GeoTrans mgrs.c and mgrs.h code. Contains routines to convert from Geodetic to MGRS and
 * the other direction.
 */
internal class MGRSCoordConverter {
    companion object {
        const val NO_ERROR = 0
        private const val LAT_ERROR = 0x0001
        private const val LON_ERROR = 0x0002
        const val STRING_ERROR = 0x0004
        private const val PRECISION_ERROR = 0x0008
        private const val EASTING_ERROR = 0x0040
        private const val NORTHING_ERROR = 0x0080
        private const val HEMISPHERE_ERROR = 0x0200
        private const val LAT_WARNING = 0x0400
        private const val UTM_ERROR = 0x1000
        private const val UPS_ERROR = 0x2000
        private const val PI_OVER_2 = PI / 2.0
        private const val MAX_PRECISION = 5
        private const val MIN_UTM_LAT = -80 * PI / 180.0 // -80 degrees in radians
        private const val MAX_UTM_LAT = 84 * PI / 180.0 // 84 degrees in radians
        const val DEG_TO_RAD = 0.017453292519943295 // PI/180
        private const val RAD_TO_DEG = 57.29577951308232 // 180/PI
        private const val MIN_EAST_NORTH = 0.0
        private const val MAX_EAST_NORTH = 4000000.0
        private const val TWO_MIL = 2000000.0
        private const val ONE_HT = 100000.0
        private const val CLARKE_1866 = "CC"
        private const val CLARKE_1880 = "CD"
        private const val BESSEL_1841 = "BR"
        private const val BESSEL_1841_NAMIBIA = "BN"
        private const val LETTER_A = 0 /* ARRAY INDEX FOR LETTER A               */
        private const val LETTER_B = 1 /* ARRAY INDEX FOR LETTER B               */
        private const val LETTER_C = 2 /* ARRAY INDEX FOR LETTER C               */
        private const val LETTER_D = 3 /* ARRAY INDEX FOR LETTER D               */
        private const val LETTER_E = 4 /* ARRAY INDEX FOR LETTER E               */
        private const val LETTER_F = 5 /* ARRAY INDEX FOR LETTER E               */
        private const val LETTER_G = 6 /* ARRAY INDEX FOR LETTER H               */
        private const val LETTER_H = 7 /* ARRAY INDEX FOR LETTER H               */
        private const val LETTER_I = 8 /* ARRAY INDEX FOR LETTER I               */
        private const val LETTER_J = 9 /* ARRAY INDEX FOR LETTER J               */
        private const val LETTER_K = 10 /* ARRAY INDEX FOR LETTER J               */
        private const val LETTER_L = 11 /* ARRAY INDEX FOR LETTER L               */
        private const val LETTER_M = 12 /* ARRAY INDEX FOR LETTER M               */
        private const val LETTER_N = 13 /* ARRAY INDEX FOR LETTER N               */
        private const val LETTER_O = 14 /* ARRAY INDEX FOR LETTER O               */
        private const val LETTER_P = 15 /* ARRAY INDEX FOR LETTER P               */
        private const val LETTER_Q = 16 /* ARRAY INDEX FOR LETTER Q               */
        private const val LETTER_R = 17 /* ARRAY INDEX FOR LETTER R               */
        private const val LETTER_S = 18 /* ARRAY INDEX FOR LETTER S               */
        private const val LETTER_T = 19 /* ARRAY INDEX FOR LETTER S               */
        private const val LETTER_U = 20 /* ARRAY INDEX FOR LETTER U               */
        private const val LETTER_V = 21 /* ARRAY INDEX FOR LETTER V               */
        private const val LETTER_W = 22 /* ARRAY INDEX FOR LETTER W               */
        private const val LETTER_X = 23 /* ARRAY INDEX FOR LETTER X               */
        private const val LETTER_Y = 24 /* ARRAY INDEX FOR LETTER Y               */
        private const val LETTER_Z = 25 /* ARRAY INDEX FOR LETTER Z               */
        private const val MGRS_LETTERS = 3 /* NUMBER OF LETTERS IN MGRS              */
        private const val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

        // UPS Constants are in the following order:
        // letter representing latitude band
        // 2nd letter range - high number
        // 2nd letter range - low number
        // 3rd letter range - high number (UPS)
        // False easting based on 2nd letter
        // False northing based on 3rd letter
        private val upsConstants = arrayOf(
            intArrayOf(LETTER_A, LETTER_J, LETTER_Z, LETTER_Z, 800000, 800000),
            intArrayOf(LETTER_B, LETTER_A, LETTER_R, LETTER_Z, 2000000, 800000),
            intArrayOf(LETTER_Y, LETTER_J, LETTER_Z, LETTER_P, 800000, 1300000),
            intArrayOf(LETTER_Z, LETTER_A, LETTER_J, LETTER_P, 2000000, 1300000)
        )

        // Latitude Band Constants are in the following order:
        // letter representing latitude band
        // minimum northing for latitude band
        // upper latitude for latitude band
        // lower latitude for latitude band
        private val latitudeBandConstants = arrayOf(
            doubleArrayOf(LETTER_C.toDouble(), 1100000.0, -72.0, -80.5, 0.0),
            doubleArrayOf(LETTER_D.toDouble(), 2000000.0, -64.0, -72.0, 2000000.0),
            doubleArrayOf(LETTER_E.toDouble(), 2800000.0, -56.0, -64.0, 2000000.0),
            doubleArrayOf(LETTER_F.toDouble(), 3700000.0, -48.0, -56.0, 2000000.0),
            doubleArrayOf(LETTER_G.toDouble(), 4600000.0, -40.0, -48.0, 4000000.0),
            doubleArrayOf(LETTER_H.toDouble(), 5500000.0, -32.0, -40.0, 4000000.0),
            doubleArrayOf(LETTER_J.toDouble(), 6400000.0, -24.0, -32.0, 6000000.0),
            doubleArrayOf(LETTER_K.toDouble(), 7300000.0, -16.0, -24.0, 6000000.0),
            doubleArrayOf(LETTER_L.toDouble(), 8200000.0, -8.0, -16.0, 8000000.0),
            doubleArrayOf(LETTER_M.toDouble(), 9100000.0, 0.0, -8.0, 8000000.0),
            doubleArrayOf(LETTER_N.toDouble(), 0.0, 8.0, 0.0, 0.0),
            doubleArrayOf(LETTER_P.toDouble(), 800000.0, 16.0, 8.0, 0.0),
            doubleArrayOf(LETTER_Q.toDouble(), 1700000.0, 24.0, 16.0, 0.0),
            doubleArrayOf(LETTER_R.toDouble(), 2600000.0, 32.0, 24.0, 2000000.0),
            doubleArrayOf(LETTER_S.toDouble(), 3500000.0, 40.0, 32.0, 2000000.0),
            doubleArrayOf(LETTER_T.toDouble(), 4400000.0, 48.0, 40.0, 4000000.0),
            doubleArrayOf(LETTER_U.toDouble(), 5300000.0, 56.0, 48.0, 4000000.0),
            doubleArrayOf(LETTER_V.toDouble(), 6200000.0, 64.0, 56.0, 6000000.0),
            doubleArrayOf(LETTER_W.toDouble(), 7000000.0, 72.0, 64.0, 6000000.0),
            doubleArrayOf(LETTER_X.toDouble(), 7900000.0, 84.5, 72.0, 6000000.0)
        )
    }

    var latitude = 0.0
        private set
    var longitude = 0.0
        private set
    var mgrsString = ""
        private set
    private var ltr2LowValue = 0
    private var ltr2HighValue = 0 // this is only used for doing MGRS to xxx conversions.
    private var falseNorthing = 0.0
    private var lastLetter = 0
    private var lastError = NO_ERROR
    private var north = 0.0
    private var south = 0.0
    private var minNorthing = 0.0
    private var northingOffset = 0.0
    private val mgrsEllipsoidCode = "WE"

    private class MGRSComponents(
        val zone: Int, val latitudeBand: Int, val squareLetter1: Int, val squareLetter2: Int,
        val easting: Double, val northing: Double, val precision: Int
    ) {
        override fun toString() = "MGRS: " + zone + " " + alphabet[latitudeBand] + " " +
                    alphabet[squareLetter1] + alphabet[squareLetter2] + " " +
                    easting + " " + northing + " " + "(" + precision + ")"
    }

    /**
     * The function ConvertMGRSToGeodetic converts an MGRS coordinate string to Geodetic (latitude and longitude)
     * coordinates according to the current ellipsoid parameters.  If any errors occur, the error code(s) are returned
     * by the function, otherwise UTM_NO_ERROR is returned.
     *
     * @param MGRSString MGRS coordinate string.
     *
     * @return the error code.
     */
    fun convertMGRSToGeodetic(MGRSString: String): Int {
        latitude = 0.0
        longitude = 0.0
        val mgrs = breakMGRSString(MGRSString) ?: return lastError
        var errorCode = NO_ERROR
        if (mgrs.zone != 0) {
            val utm = convertMGRSToUTM(MGRSString)
            if (utm != null) {
                latitude = utm.latitude.radians
                longitude = utm.longitude.radians
            } else errorCode = UTM_ERROR
        } else {
            val ups = convertMGRSToUPS(MGRSString)
            if (ups != null) {
                latitude = ups.latitude.radians
                longitude = ups.longitude.radians
            } else errorCode = UPS_ERROR
        }
        return errorCode
    }

    /**
     * The function Break_MGRS_String breaks down an MGRS coordinate string into its component parts. Updates
     * last_error.
     *
     * @param MGRSString the MGRS coordinate string
     *
     * @return the corresponding [MGRSComponents] or null.
     */
    private fun breakMGRSString(MGRSString: String): MGRSComponents? {
        var str = MGRSString
        var i = 0
        var errorCode = NO_ERROR
        var zone = 0
        val letters = IntArray(3)
        var easting = 0L
        var northing = 0L
        var precision = 0
        str = str.uppercase().replace("\\s", "")
        var j = i
        while (i < str.length && str[i].isDigit()) i++
        var numDigits = i - j
        if (numDigits <= 2) {
            if (numDigits > 0) {
                /* get zone */
                zone = str.substring(j, i).toInt()
                if (zone < 1 || zone > 60) errorCode = errorCode or STRING_ERROR
            }
        }
        j = i
        while (i < str.length && str[i].isLetter()) i++
        val numLetters = i - j
        if (numLetters == 3) {
            /* get letters */
            letters[0] = alphabet.indexOf(str[j].uppercaseChar())
            if (letters[0] == LETTER_I || letters[0] == LETTER_O) errorCode = errorCode or STRING_ERROR
            letters[1] = alphabet.indexOf(str[j + 1].uppercaseChar())
            if (letters[1] == LETTER_I || letters[1] == LETTER_O) errorCode = errorCode or STRING_ERROR
            letters[2] = alphabet.indexOf(str[j + 2].uppercaseChar())
            if (letters[2] == LETTER_I || letters[2] == LETTER_O) errorCode = errorCode or STRING_ERROR
        } else errorCode = errorCode or STRING_ERROR
        j = i
        while (i < str.length && str[i].isDigit()) i++
        numDigits = i - j
        if (numDigits <= 10 && numDigits % 2 == 0) {
            /* get easting, northing and precision */
            /* get easting & northing */
            val n = numDigits / 2
            precision = n
            if (n > 0) {
                easting = str.substring(j, j + n).toLong()
                northing = str.substring(j + n, j + n + n).toLong()
                val multiplier = 10.0.pow(5 - n).toLong()
                easting *= multiplier
                northing *= multiplier
            } else {
                easting = 0
                northing = 0
            }
        } else errorCode = errorCode or STRING_ERROR
        lastError = errorCode
        return if (errorCode == NO_ERROR) MGRSComponents(
            zone, letters[0], letters[1], letters[2],
            easting.toDouble(), northing.toDouble(), precision
        ) else null
    }

    /**
     * The function Get_Latitude_Band_Min_Northing receives a latitude band letter and uses the Latitude_Band_Table to
     * determine the minimum northing for that latitude band letter. Updates min_northing.
     *
     * @param letter Latitude band letter.
     *
     * @return the error code.
     */
    private fun getLatitudeBandMinNorthing(letter: Int): Int {
        var errorCode = NO_ERROR
        when (letter) {
            in LETTER_C..LETTER_H -> {
                minNorthing = latitudeBandConstants[letter - 2][1]
                northingOffset = latitudeBandConstants[letter - 2][4]
            }
            in LETTER_J..LETTER_N -> {
                minNorthing = latitudeBandConstants[letter - 3][1]
                northingOffset = latitudeBandConstants[letter - 3][4]
            }
            in LETTER_P..LETTER_X -> {
                minNorthing = latitudeBandConstants[letter - 4][1]
                northingOffset = latitudeBandConstants[letter - 4][4]
            }
            else -> errorCode = errorCode or STRING_ERROR
        }
        return errorCode
    }

    /**
     * The function Get_Latitude_Range receives a latitude band letter and uses the Latitude_Band_Table to determine the
     * latitude band boundaries for that latitude band letter. Updates north and south.
     *
     * @param letter the Latitude band letter
     *
     * @return the error code.
     */
    private fun getLatitudeRange(letter: Int): Int {
        var errorCode = NO_ERROR
        when (letter) {
            in LETTER_C..LETTER_H -> {
                north = latitudeBandConstants[letter - 2][2] * DEG_TO_RAD
                south = latitudeBandConstants[letter - 2][3] * DEG_TO_RAD
            }
            in LETTER_J..LETTER_N -> {
                north = latitudeBandConstants[letter - 3][2] * DEG_TO_RAD
                south = latitudeBandConstants[letter - 3][3] * DEG_TO_RAD
            }
            in LETTER_P..LETTER_X -> {
                north = latitudeBandConstants[letter - 4][2] * DEG_TO_RAD
                south = latitudeBandConstants[letter - 4][3] * DEG_TO_RAD
            }
            else -> errorCode = errorCode or STRING_ERROR
        }
        return errorCode
    }

    /**
     * The function convertMGRSToUTM converts an MGRS coordinate string to UTM projection (zone, hemisphere, easting and
     * northing) coordinates according to the current ellipsoid parameters.  Updates last_error if any errors occurred.
     *
     * @param MGRSString the MGRS coordinate string
     *
     * @return the corresponding [UTMCoord] or null.
     */
    private fun convertMGRSToUTM(MGRSString: String): UTMCoord? {
        var errorCode = NO_ERROR
        var utm: UTMCoord? = null
        val mgrs = breakMGRSString(MGRSString)
        if (mgrs == null) errorCode = errorCode or STRING_ERROR else {
            if (mgrs.latitudeBand == LETTER_X && (mgrs.zone == 32 || mgrs.zone == 34 || mgrs.zone == 36))
                errorCode = errorCode or STRING_ERROR
            else {
                val hemisphere = if (mgrs.latitudeBand < LETTER_N) Hemisphere.S else Hemisphere.N
                getGridValues(mgrs.zone)

                // Check that the second letter of the MGRS string is within
                // the range of valid second letter values
                // Also check that the third letter is valid
                if (mgrs.squareLetter1 < ltr2LowValue || mgrs.squareLetter1 > ltr2HighValue ||
                    mgrs.squareLetter2 > LETTER_V) errorCode = errorCode or STRING_ERROR
                if (errorCode == NO_ERROR) {
                    var gridNorthing = mgrs.squareLetter2 * ONE_HT
                    var gridEasting = (mgrs.squareLetter1 - ltr2LowValue + 1) * ONE_HT
                    if (ltr2LowValue == LETTER_J && mgrs.squareLetter1 > LETTER_O) gridEasting -= ONE_HT
                    if (mgrs.squareLetter2 > LETTER_O) gridNorthing -= ONE_HT
                    if (mgrs.squareLetter2 > LETTER_I) gridNorthing -= ONE_HT
                    if (gridNorthing >= TWO_MIL) gridNorthing -= TWO_MIL
                    errorCode = getLatitudeBandMinNorthing(mgrs.latitudeBand)
                    if (errorCode == NO_ERROR) {
                        gridNorthing -= falseNorthing
                        if (gridNorthing < 0.0) gridNorthing += TWO_MIL
                        gridNorthing += northingOffset
                        if (gridNorthing < minNorthing) gridNorthing += TWO_MIL
                        val easting = gridEasting + mgrs.easting
                        val northing = gridNorthing + mgrs.northing
                        try {
                            utm = UTMCoord.fromUTM(mgrs.zone, hemisphere, easting, northing)
                            latitude = utm.latitude.radians
                            val divisor = 10.0.pow(mgrs.precision)
                            errorCode = getLatitudeRange(mgrs.latitudeBand)
                            if (errorCode == NO_ERROR) {
                                if (!(south - DEG_TO_RAD / divisor <= latitude
                                            && latitude <= north + DEG_TO_RAD / divisor)
                                ) errorCode = errorCode or LAT_WARNING
                            }
                        } catch (e: Exception) {
                            errorCode = UTM_ERROR
                        }
                    }
                }
            }
        }
        lastError = errorCode
        return if (errorCode == NO_ERROR || errorCode == LAT_WARNING) utm else null
    }

    /**
     * The function convertGeodeticToMGRS converts Geodetic (latitude and longitude) coordinates to an MGRS coordinate
     * string, according to the current ellipsoid parameters.  If any errors occur, the error code(s) are returned by
     * the function, otherwise MGRS_NO_ERROR is returned.
     *
     * @param latitude  Latitude in radians
     * @param longitude Longitude in radian
     * @param precision Precision level of MGRS string
     *
     * @return error code
     */
    fun convertGeodeticToMGRS(latitude: Double, longitude: Double, precision: Int): Int {
        mgrsString = ""
        var errorCode = NO_ERROR
        if (latitude < -PI_OVER_2 || latitude > PI_OVER_2) errorCode = LAT_ERROR
        if (longitude < -PI || longitude > 2 * PI) errorCode = LON_ERROR
        if (precision < 0 || precision > MAX_PRECISION) errorCode = PRECISION_ERROR
        if (errorCode == NO_ERROR) {
            errorCode = if (latitude < MIN_UTM_LAT || latitude > MAX_UTM_LAT) {
                try {
                    val ups = UPSCoord.fromLatLon(latitude.radians, longitude.radians)
                    errorCode or convertUPSToMGRS(ups.hemisphere, ups.easting, ups.northing, precision)
                } catch (e: Exception) {
                    UPS_ERROR
                }
            } else {
                try {
                    val utm = UTMCoord.fromLatLon(latitude.radians, longitude.radians)
                    errorCode or convertUTMToMGRS(utm.zone, latitude, utm.easting, utm.northing, precision)
                } catch (e: Exception) {
                    UTM_ERROR
                }
            }
        }
        return errorCode
    }

    /**
     * The function Convert_UPS_To_MGRS converts UPS (hemisphere, easting, and northing) coordinates to an MGRS
     * coordinate string according to the current ellipsoid parameters.  If any errors occur, the error code(s) are
     * returned by the function, otherwise MGRS_NO_ERROR is returned.
     *
     * @param hemisphere hemisphere either [Hemisphere.N] of [Hemisphere.S].
     * @param easting    easting/X in meters
     * @param northing   northing/Y in meters
     * @param precision  precision level of MGRS string
     *
     * @return error value
     */
    private fun convertUPSToMGRS(hemisphere: Hemisphere, easting: Double, northing: Double, precision: Int): Int {
        var east = easting
        var north = northing
        val falseEasting: Double /* False easting for 2nd letter                 */
        val falseNorthing: Double /* False northing for 3rd letter                */
        var gridEasting: Double /* easting used to derive 2nd letter of MGRS    */
        var gridNorthing: Double /* northing used to derive 3rd letter of MGRS   */
        val ltr2LowValue: Int /* 2nd letter range - low number                */
        val letters = IntArray(MGRS_LETTERS) /* Number location of 3 letters in alphabet     */
        var errorCode = NO_ERROR
        if (Hemisphere.N != hemisphere && Hemisphere.S != hemisphere) errorCode = errorCode or HEMISPHERE_ERROR
        if (east < MIN_EAST_NORTH || east > MAX_EAST_NORTH) errorCode = errorCode or EASTING_ERROR
        if (north < MIN_EAST_NORTH || north > MAX_EAST_NORTH) errorCode = errorCode or NORTHING_ERROR
        if (precision < 0 || precision > MAX_PRECISION) errorCode = errorCode or PRECISION_ERROR
        if (errorCode == NO_ERROR) {
            val divisor = 10.0.pow(5 - precision)
            east = roundMGRS(east / divisor) * divisor
            north = roundMGRS(north / divisor) * divisor
            if (Hemisphere.N == hemisphere) {
                if (east >= TWO_MIL) letters[0] = LETTER_Z else letters[0] = LETTER_Y
                val index = letters[0] - 22
                ltr2LowValue = upsConstants[index][1]
                falseEasting = upsConstants[index][4].toDouble()
                falseNorthing = upsConstants[index][5].toDouble()
            } else {
                if (east >= TWO_MIL) letters[0] = LETTER_B else letters[0] = LETTER_A
                ltr2LowValue = upsConstants[letters[0]][1]
                falseEasting = upsConstants[letters[0]][4].toDouble()
                falseNorthing = upsConstants[letters[0]][5].toDouble()
            }
            gridNorthing = north
            gridNorthing -= falseNorthing
            letters[2] = (gridNorthing / ONE_HT).toInt()
            if (letters[2] > LETTER_H) letters[2] = letters[2] + 1
            if (letters[2] > LETTER_N) letters[2] = letters[2] + 1
            gridEasting = east
            gridEasting -= falseEasting
            letters[1] = ltr2LowValue + (gridEasting / ONE_HT).toInt()
            if (east < TWO_MIL) {
                if (letters[1] > LETTER_L) letters[1] = letters[1] + 3
                if (letters[1] > LETTER_U) letters[1] = letters[1] + 2
            } else {
                if (letters[1] > LETTER_C) letters[1] = letters[1] + 2
                if (letters[1] > LETTER_H) letters[1] = letters[1] + 1
                if (letters[1] > LETTER_L) letters[1] = letters[1] + 3
            }
            makeMGRSString(0, letters, east, north, precision)
        }
        return errorCode
    }

    /**
     * The function UTM_To_MGRS calculates an MGRS coordinate string based on the zone, latitude, easting and northing.
     *
     * @param zone      Zone number
     * @param latitude  Latitude in radians
     * @param easting   Easting
     * @param northing  Northing
     * @param precision Precision
     *
     * @return error code
     */
    private fun convertUTMToMGRS(zone: Int, latitude: Double, easting: Double, northing: Double, precision: Int): Int {
        var east = easting
        var north = northing
        var gridEasting: Double /* Easting used to derive 2nd letter of MGRS   */
        var gridNorthing: Double /* Northing used to derive 3rd letter of MGRS  */
        val letters = IntArray(MGRS_LETTERS) /* Number location of 3 letters in alphabet    */

        /* Round easting and northing values */
        val divisor = 10.0.pow(5 - precision)
        east = roundMGRS(east / divisor) * divisor
        north = roundMGRS(north / divisor) * divisor
        getGridValues(zone)
        val errorCode = getLatitudeLetter(latitude)
        letters[0] = lastLetter
        if (errorCode == NO_ERROR) {
            gridNorthing = north
            if (gridNorthing == 1e7) gridNorthing -= 1.0
            while (gridNorthing >= TWO_MIL) gridNorthing -= TWO_MIL
            gridNorthing += falseNorthing
            if (gridNorthing >= TWO_MIL) gridNorthing -= TWO_MIL
            letters[2] = (gridNorthing / ONE_HT).toInt()
            if (letters[2] > LETTER_H) letters[2] = letters[2] + 1
            if (letters[2] > LETTER_N) letters[2] = letters[2] + 1
            gridEasting = east
            if (letters[0] == LETTER_V && zone == 31 && gridEasting == 500000.0) gridEasting -= 1.0 /* SUBTRACT 1 METER */
            letters[1] = ltr2LowValue + ((gridEasting / ONE_HT).toInt() - 1)
            if (ltr2LowValue == LETTER_J && letters[1] > LETTER_N) letters[1] = letters[1] + 1
            makeMGRSString(zone, letters, east, north, precision)
        }
        return errorCode
    }

    /**
     * The function Get_Grid_Values sets the letter range used for the 2nd letter in the MGRS coordinate string, based
     * on the set number of the utm zone. It also sets the false northing using a value of A for the second letter of
     * the grid square, based on the grid pattern and set number of the utm zone.
     * <br>
     * Key values that are set in this function include:  ltr2_low_value, ltr2_high_value, and false_northing.
     *
     * @param zone Zone number
     */
    private fun getGridValues(zone: Int) {
        var setNumber = zone % 6 /* Set number (1-6) based on UTM zone number */
        if (setNumber == 0) setNumber = 6
        val aaPattern = if (mgrsEllipsoidCode.compareTo(CLARKE_1866) == 0
            || mgrsEllipsoidCode.compareTo(CLARKE_1880) == 0
            || mgrsEllipsoidCode.compareTo(BESSEL_1841) == 0
            || mgrsEllipsoidCode.compareTo(BESSEL_1841_NAMIBIA) == 0
            ) 0 else 1 /* Pattern based on ellipsoid code */
        if (setNumber == 1 || setNumber == 4) {
            ltr2LowValue = LETTER_A
            ltr2HighValue = LETTER_H
        } else if (setNumber == 2 || setNumber == 5) {
            ltr2LowValue = LETTER_J
            ltr2HighValue = LETTER_R
        } else if (setNumber == 3 || setNumber == 6) {
            ltr2LowValue = LETTER_S
            ltr2HighValue = LETTER_Z
        }

        /* False northing at A for second letter of grid square */
        falseNorthing = if (aaPattern == 1) {
            if (setNumber % 2 == 0) 500000.0
            else 0.0
        } else {
            if (setNumber % 2 == 0) 1500000.0
            else 1000000.00
        }
    }

    /**
     * The function receives a latitude value and uses the Latitude_Band_Table to determine the
     * latitude band letter for that latitude.
     *
     * @param latitude latitude to turn into code
     *
     * @return error code
     */
    private fun getLatitudeLetter(latitude: Double): Int {
        var errorCode = NO_ERROR
        val latDeg = latitude * RAD_TO_DEG
        if (latDeg >= 72 && latDeg < 84.5) lastLetter = LETTER_X
        else if (latDeg > -80.5 && latDeg < 72) {
            val temp = (latitude + 80.0 * DEG_TO_RAD) / (8.0 * DEG_TO_RAD) + 1.0e-12
            lastLetter = latitudeBandConstants[temp.toInt()][0].toInt()
        } else errorCode = errorCode or LAT_ERROR
        return errorCode
    }

    /**
     * The function Round_MGRS rounds the input value to the nearest integer, using the standard engineering rule. The
     * rounded integer value is then returned.
     *
     * @param value Value to be rounded
     *
     * @return rounded double value
     */
    private fun roundMGRS(value: Double): Double {
        val floorValue = floor(value)
        val fraction = value - floorValue
        var intValue = floorValue.toLong()
        if (fraction > 0.5 || fraction == 0.5 && intValue % 2 == 1L) intValue++
        return intValue.toDouble()
    }

    /**
     * The function Make_MGRS_String constructs an MGRS string from its component parts.
     *
     * @param zone      UTM Zone
     * @param letters   MGRS coordinate string letters
     * @param easting   Easting value
     * @param northing  Northing value
     * @param precision Precision level of MGRS string
     */
    private fun makeMGRSString(zone: Int, letters: IntArray, easting: Double, northing: Double, precision: Int) {
        var east = easting
        var north = northing
        mgrsString = if (zone != 0) "%02d".format(zone) else "  "
        for (j in 0..2) {
            if (letters[j] < 0 || letters[j] > 26) return
            mgrsString += alphabet[letters[j]]
        }
        val divisor = 10.0.pow(5 - precision)
        east %= 100000.0
        if (east >= 99999.5) east = 99999.0

        // Here we need to only use the number requesting in the precision
        val iEast = (east / divisor).toInt()
        var sEast = StringBuilder(iEast.toString())
        if (sEast.length > precision) sEast = StringBuilder(sEast.substring(0, precision - 1))
        else for (i in 0 until precision - sEast.length) sEast.insert(0, "0")
        mgrsString = "$mgrsString $sEast"
        north %= 100000.0
        if (north >= 99999.5) north = 99999.0
        val iNorth = (north / divisor).toInt()
        var sNorth = StringBuilder(iNorth.toString())
        if (sNorth.length > precision) sNorth = StringBuilder(sNorth.substring(0, precision - 1))
        else for (i in 0 until precision - sNorth.length) sNorth.insert(0, "0")
        mgrsString = "$mgrsString $sNorth"
    }

    /**
     * The function Convert_MGRS_To_UPS converts an MGRS coordinate string to UPS (hemisphere, easting, and northing)
     * coordinates, according to the current ellipsoid parameters. If any errors occur, the error code(s) are returned
     * by the function, otherwise UPS_NO_ERROR is returned.
     *
     * @param MGRS the MGRS coordinate string.
     *
     * @return a corresponding [UPSCoord] instance.
     */
    private fun convertMGRSToUPS(MGRS: String): UPSCoord? {
        val ltr2HighValue: Int /* 2nd letter range - high number             */
        val ltr3HighValue: Int /* 3rd letter range - high number (UPS)       */
        val ltr2LowValue: Int /* 2nd letter range - low number              */
        val falseEasting: Double /* False easting for 2nd letter               */
        val falseNorthing: Double /* False northing for 3rd letter              */
        var gridEasting: Double /* easting for 100,000 meter grid square      */
        var gridNorthing: Double /* northing for 100,000 meter grid square     */
        var errorCode = NO_ERROR
        val hemisphere: Hemisphere
        var easting: Double
        var northing: Double
        val mgrs = breakMGRSString(MGRS)
        if (mgrs != null) {
            if (mgrs.zone > 0) errorCode = errorCode or STRING_ERROR
            if (errorCode == NO_ERROR) {
                easting = mgrs.easting
                northing = mgrs.northing
                if (mgrs.latitudeBand >= LETTER_Y) {
                    hemisphere = Hemisphere.N
                    val index = mgrs.latitudeBand - 22
                    ltr2LowValue = upsConstants[index][1]
                    ltr2HighValue = upsConstants[index][2]
                    ltr3HighValue = upsConstants[index][3]
                    falseEasting = upsConstants[index][4].toDouble()
                    falseNorthing = upsConstants[index][5].toDouble()
                } else {
                    hemisphere = Hemisphere.S
                    ltr2LowValue = upsConstants[mgrs.latitudeBand][1]
                    ltr2HighValue = upsConstants[mgrs.latitudeBand][2]
                    ltr3HighValue = upsConstants[mgrs.latitudeBand][3]
                    falseEasting = upsConstants[mgrs.latitudeBand][4].toDouble()
                    falseNorthing = upsConstants[mgrs.latitudeBand][5].toDouble()
                }

                // Check that the second letter of the MGRS string is within
                // the range of valid second letter values
                // Also check that the third letter is valid
                if (mgrs.squareLetter1 < ltr2LowValue || mgrs.squareLetter1 > ltr2HighValue ||
                    mgrs.squareLetter1 == LETTER_D || mgrs.squareLetter1 == LETTER_E ||
                    mgrs.squareLetter1 == LETTER_M || mgrs.squareLetter1 == LETTER_N ||
                    mgrs.squareLetter1 == LETTER_V || mgrs.squareLetter1 == LETTER_W ||
                    mgrs.squareLetter2 > ltr3HighValue) errorCode = STRING_ERROR
                if (errorCode == NO_ERROR) {
                    gridNorthing = mgrs.squareLetter2 * ONE_HT + falseNorthing
                    if (mgrs.squareLetter2 > LETTER_I) gridNorthing -= ONE_HT
                    if (mgrs.squareLetter2 > LETTER_O) gridNorthing -= ONE_HT
                    gridEasting = (mgrs.squareLetter1 - ltr2LowValue) * ONE_HT + falseEasting
                    if (ltr2LowValue != LETTER_A) {
                        if (mgrs.squareLetter1 > LETTER_L) gridEasting -= 300000.0
                        if (mgrs.squareLetter1 > LETTER_U) gridEasting -= 200000.0
                    } else {
                        if (mgrs.squareLetter1 > LETTER_C) gridEasting -= 200000.0
                        if (mgrs.squareLetter1 > LETTER_I) gridEasting -= ONE_HT
                        if (mgrs.squareLetter1 > LETTER_L) gridEasting -= 300000.0
                    }
                    easting += gridEasting
                    northing += gridNorthing
                    return UPSCoord.fromUPS(hemisphere, easting, northing)
                }
            }
        }
        return null
    }
}