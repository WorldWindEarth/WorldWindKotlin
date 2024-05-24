package earth.worldwind.geom

import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.format.format
import kotlin.jvm.JvmInline
import kotlin.jvm.JvmStatic
import kotlin.math.*

@JvmInline
value class Angle private constructor(
    /**
     * Size of this angle in degrees.
     */
    val inDegrees: Double
): Comparable<Angle> {
    /**
     * Size of this angle in radians. This may be useful for functions, which
     * generally take radians as trigonometric arguments.
     */
    val inRadians get() = toRadians(inDegrees)
    /**
     * Size of this angle in minutes.
     */
    val inMinutes get() = toMinutes(inDegrees)
    /**
     * Size of this angle in seconds.
     */
    val inSeconds get() = toSeconds(inDegrees)
    /**
     * Size of this angle in milliradians.
     */
    val inMrad get() = toMrad(inDegrees)
    /**
     * Size of this angle in mil NATO.
     */
    val inMilNATO get() = toMilNATO(inDegrees)
    /**
     * Size of this angle in mil Warsaw Pact.
     */
    val inMilWP get() = toMilWP(inDegrees)
    /**
     * Size of this angle in Streck.
     */
    val inStreck get() = toStreck(inDegrees)
    /**
     * Size of this angle in minute of angle (MOA).
     */
    val inMOA get() = toMOA(inDegrees)
    /**
     * Indicates whether this angle is within the normal range of latitude, [-90, 90].
     */
    val isValidLatitude get() = isValidLatitude(inDegrees)
    /**
     * Indicates whether this angle is within the normal range of longitude, [-180, 180].
     */
    val isValidLongitude get() = isValidLongitude(inDegrees)
    /**
     * Return North or South notation depends on sign
     */
    val latitudeLetter get() = if (inDegrees < 0.0) "S" else "N"
    /**
     * Return West or East notation depends on sign
     */
    val longitudeLetter get() = if (inDegrees < 0.0) "W" else "E"
    /**
     * Relative latitude (from wiki.openstreetmap.org)
     */
    val relativeLatitude get() = (1 - ln(tan(inRadians) + 1 / cos(inRadians)) / PI) / 2
    /**
     * Relative longitude (from wiki.openstreetmap.org)
     */
    val relativeLongitude get() = (inDegrees + 180.0) / 360.0

    companion object {
        /** Represents an angle of zero degrees  */
        @JvmStatic val ZERO = 0.0.degrees
        /** Represents a right angle of positive 90 degrees  */
        @JvmStatic val POS90 = 90.0.degrees
        /** Represents a right angle of negative 90 degrees  */
        @JvmStatic val NEG90 = (-90.0).degrees
        /** Represents an angle of positive 180 degrees  */
        @JvmStatic val POS180 = 180.0.degrees
        /** Represents an angle of negative 180 degrees  */
        @JvmStatic val NEG180 = (-180.0).degrees
        /** Represents an angle of positive 360 degrees  */
        @JvmStatic val POS360 = 360.0.degrees
        /** Represents an angle of negative 360 degrees  */
        @JvmStatic val NEG360 = (-360.0).degrees
        /** Represents an angle of 1 minute  */
        @JvmStatic val MINUTE = (1.0 / 60.0).degrees
        /** Represents an angle of 1 second  */
        @JvmStatic val SECOND = (1.0 / 3600.0).degrees
        /**
         * Conversion factor for degrees to radians.
         */
        const val DEGREES_TO_RADIANS = PI / 180.0
        /**
         * Conversion factor for radians to degrees.
         */
        const val RADIANS_TO_DEGREES = 180.0 / PI
        /**
         * Conversion factor for degrees to minutes.
         */
        const val DEGREES_TO_MINUTES = 60.0
        /**
         * Conversion factor for minutes to degrees.
         */
        const val MINUTES_TO_DEGREES = 1.0 / 60.0
        /**
         * Conversion factor for degrees to seconds.
         */
        const val DEGREES_TO_SECONDS = 3600.0
        /**
         * Conversion factor for seconds to degrees.
         */
        const val SECONDS_TO_DEGREES = 1.0 / 3600.0
        /**
         * Conversion factor for degrees to milliradians.
         */
        const val DEGREES_TO_MRAD = PI * 2.0 / 360.0
        /**
         * Conversion factor for milliradians to degrees.
         */
        const val MRAD_TO_DEGREES = 360.0 / PI / 2.0
        /**
         * Conversion factor for degrees to milliradians (NATO).
         */
        const val DEGREES_TO_MIL_NATO = 6400.0 / 360.0
        /**
         * Conversion factor for milliradians (NATO) to degrees.
         */
        const val MIL_NATO_TO_DEGREES = 360.0 / 6400.0
        /**
         * Conversion factor for degrees to milliradians (Warsaw Pact).
         */
        const val DEGREES_TO_MIL_WP = 6000.0 / 360.0
        /**
         * Conversion factor for milliradians (Warsaw Pact) to degrees.
         */
        const val MIL_WP_TO_DEGREES = 360.0 / 6000.0
        /**
         * Conversion factor for degrees to milliradians (Sweden).
         */
        const val DEGREES_TO_STRECK = 6300.0 / 360.0
        /**
         * Conversion factor for milliradians (Sweden) to degrees.
         */
        const val STRECK_TO_DEGREES = 360.0 / 6000.0
        /**
         * Conversion factor for degrees to minute of angle (MOA).
         */
        const val DEGREES_TO_MOA = 21600.0 / 360.0
        /**
         * Conversion factor for minute of angle (MOA) to degrees.
         */
        const val MOA_TO_DEGREES = 360.0 / 21600.0

        /**
         * Returns an Angle equal to this Double number in degrees.
         */
        inline val Double.degrees get() = fromDegrees(this)

        /**
         * Returns an Angle equal to this Double number in radians.
         */
        inline val Double.radians get() = fromRadians(this)

        /**
         * Returns an Angle equal to this Double number in minutes.
         */
        inline val Double.minutes get() = fromMinutes(this)

        /**
         * Returns an Angle equal to this Double number in seconds.
         */
        inline val Double.seconds get() = fromSeconds(this)

        /**
         * Returns an Angle equal to this Double number in milliradians.
         */
        inline val Double.mrad get() = fromMrad(this)

        /**
         * Returns an Angle equal to this Double number in mil NATO.
         */
        inline val Double.milNATO get() = fromMilNATO(this)

        /**
         * Returns an Angle equal to this Double number in mil Warsaw Pact.
         */
        inline val Double.milWP get() = fromMilWP(this)

        /**
         * Returns an Angle equal to this Double number in Streck.
         */
        inline val Double.streck get() = fromStreck(this)

        /**
         * Returns an Angle equal to this Double number in minute of angle (MOA).
         */
        inline val Double.MOA get() = fromMOA(this)

        /**
         * Convert radians to degrees
         *
         * @param radians value in radians
         *
         * @return value in degrees
         */
        @JvmStatic fun toDegrees(radians: Double) = radians * RADIANS_TO_DEGREES

        /**
         * Convert degrees to radians
         *
         * @param degrees value in degrees
         *
         * @return value in radians
         */
        @JvmStatic fun toRadians(degrees: Double) = degrees * DEGREES_TO_RADIANS

        /**
         * Convert degrees to minutes
         *
         * @param degrees value in degrees
         *
         * @return value in minutes
         */
        @JvmStatic fun toMinutes(degrees: Double) = degrees * DEGREES_TO_MINUTES

        /**
         * Convert degrees to seconds
         *
         * @param degrees value in degrees
         *
         * @return value in seconds
         */
        @JvmStatic fun toSeconds(degrees: Double) = degrees * DEGREES_TO_SECONDS

        /**
         * Convert degrees to milliradians
         *
         * @param degrees value in degrees
         *
         * @return value in milliradians
         */
        @JvmStatic fun toMrad(degrees: Double) = degrees * DEGREES_TO_MRAD

        /**
         * Convert degrees to mil NATO
         *
         * @param degrees value in degrees
         *
         * @return value in mil NATO
         */
        @JvmStatic fun toMilNATO(degrees: Double) = degrees * DEGREES_TO_MIL_NATO

        /**
         * Convert degrees to mil Warsaw Pact
         *
         * @param degrees value in degrees
         *
         * @return value in mil Warsaw Pact
         */
        @JvmStatic fun toMilWP(degrees: Double) = degrees * DEGREES_TO_MIL_WP

        /**
         * Convert degrees to Streck
         *
         * @param degrees value in degrees
         *
         * @return value in mil Streck
         */
        @JvmStatic fun toStreck(degrees: Double) = degrees * DEGREES_TO_STRECK

        /**
         * Convert degrees to minute of angle (MOA)
         *
         * @param degrees value in degrees
         *
         * @return value in minute of angle (MOA)
         */
        @JvmStatic fun toMOA(degrees: Double) = degrees * DEGREES_TO_MOA

        /**
         * Obtains an angle from a specified number of degrees.
         *
         * @param degrees the size in degrees of the angle to be obtained
         *
         * @return a new angle, whose size in degrees is given by [degrees]
         */
        @JvmStatic fun fromDegrees(degrees: Double) = Angle(degrees)

        /**
         * Obtains an angle from a specified number of radians.
         *
         * @param radians the size in radians of the angle to be obtained.
         *
         * @return a new angle, whose size in radians is given by [radians].
         */
        @JvmStatic fun fromRadians(radians: Double) = Angle(radians * RADIANS_TO_DEGREES)

        /**
         * Obtains an angle from a specified number of minutes.
         *
         * @param minutes the size in minutes of the angle to be obtained.
         *
         * @return a new angle, whose size in minutes is given by [minutes].
         */
        @JvmStatic fun fromMinutes(minutes: Double) = Angle(minutes * MINUTES_TO_DEGREES)

        /**
         * Obtains an angle from a specified number of seconds.
         *
         * @param seconds the size in seconds of the angle to be obtained.
         *
         * @return a new angle, whose size in seconds is given by [seconds].
         */
        @JvmStatic fun fromSeconds(seconds: Double) = Angle(seconds * SECONDS_TO_DEGREES)

        /**
         * Obtains an angle from a specified number of milliradians.
         *
         * @param mrad the size in milliradians of the angle to be obtained.
         *
         * @return a new angle, whose size in milliradians is given by [mrad].
         */
        @JvmStatic fun fromMrad(mrad: Double) = Angle(mrad * MRAD_TO_DEGREES)

        /**
         * Obtains an angle from a specified number of mil NATO.
         *
         * @param milNATO the size in mil NATO of the angle to be obtained.
         *
         * @return a new angle, whose size in mil NATO is given by [milNATO].
         */
        @JvmStatic fun fromMilNATO(milNATO: Double) = Angle(milNATO * MIL_NATO_TO_DEGREES)

        /**
         * Obtains an angle from a specified number of mil Warsaw Pact.
         *
         * @param milWP the size in mil WP of the angle to be obtained.
         *
         * @return a new angle, whose size in mil WP is given by [milWP].
         */
        @JvmStatic fun fromMilWP(milWP: Double) = Angle(milWP * MIL_WP_TO_DEGREES)

        /**
         * Obtains an angle from a specified number of Streck.
         *
         * @param streck the size in Streck of the angle to be obtained.
         *
         * @return a new angle, whose size in Streck is given by [streck].
         */
        @JvmStatic fun fromStreck(streck: Double) = Angle(streck * STRECK_TO_DEGREES)

        /**
         * Obtains an angle from a specified number of minute of angle (MOA).
         *
         * @param moa the size in MOA of the angle to be obtained.
         *
         * @return a new angle, whose size in MOA is given by [moa].
         */
        @JvmStatic fun fromMOA(moa: Double) = Angle(moa * MOA_TO_DEGREES)

        /**
         * Obtains an angle from rectangular coordinates.
         *
         * @param x the abscissa coordinate.
         * @param y the ordinate coordinate.
         *
         * @return a new angle, whose size is determined from `x` and `y`.
         */
        @JvmStatic fun fromXY(x: Double, y: Double) = atan2(y, x).radians

        /**
         * Obtain an angle from a given number of positive degrees, minutes and seconds.
         *
         * @param degrees integer number of degrees, positive.
         * @param minutes integer number of minutes, positive only between 0 and 60.
         * @param seconds integer number of seconds, positive only between 0 and 60.
         *
         * @return a new angle whose size in degrees is given by `degrees`, `minutes` and `seconds`.
         *
         * @throws IllegalArgumentException if minutes or seconds are outside the 0-60 range or the degrees is negative.
         */
        @JvmStatic
        fun fromDMS(degrees: Int, minutes: Int, seconds: Double): Angle {
            require(degrees >= 0) {
                logMessage(ERROR, "Angle", "fromDMS", "invalidDegrees")
            }
            require(minutes in 0 until 60) {
                logMessage(ERROR, "Angle", "fromDMS", "invalidMinutes")
            }
            require(seconds >= 0 && seconds < 60) {
                logMessage(ERROR, "Angle", "fromDMS", "invalidSeconds")
            }
            return fromDegrees(degrees + minutes / 60.0 + seconds / 3600.0)
        }

        /**
         * Obtain an angle from a given number of positive degrees and decimal minutes.
         *
         * @param degrees integer number of degrees, positive.
         * @param minutes double representing the decimal representation of minutes and seconds.
         *
         * @return a new angle whose size in degrees is given by `degrees` and decimal `minutes`.
         *
         * @throws IllegalArgumentException if minutes or seconds are outside the 0-60 range or the degrees is negative.
         */
        @JvmStatic
        fun fromDM(degrees: Int, minutes: Double): Angle {
            require(degrees >= 0) {
                logMessage(ERROR, "Angle", "fromDM", "invalidDegrees")
            }
            require(minutes >= 0 && minutes < 60) {
                logMessage(ERROR, "Angle", "fromDM", "invalidMinutes")
            }
            return fromDegrees(degrees + minutes / 60.0)
        }

        /**
         * Obtain an angle from a degrees, minute and seconds character string.
         *
         * eg:<pre>
         * 123 34 42
         * -123* 34' 42" (where * stands for the degree symbol)
         * +45* 12' 30" (where * stands for the degree symbol)
         * 45 12 30 S
         * 45 12 30 N
         * </pre>
         * For a string containing both a sign and compass direction, the compass direction will take precedence.
         *
         * @param dmsString the degrees, minute and second character string.
         *
         * @return the corresponding angle.
         *
         * @throws IllegalArgumentException if dmsString is not properly formatted.
         */
        @JvmStatic
        fun fromDMS(dmsString: String): Angle {
            var dms = dmsString
            // Check for string format validity
            val regex = Regex("([-+]?\\d{1,3}[dD°\\s](\\s*\\d{1,2}['’\\s])?(\\s*\\d{1,2}[\"”\\s])?\\s*([NnSsEeWw])?\\s?)")
            require(regex.matches("$dms ")) {
                logMessage(ERROR, "Angle", "fromDMS", "invalidFormat")
            }
            // Replace degree, min and sec signs with space
            dms = dms.replace("[Dd°'’\"”]".toRegex(), " ")
            // Replace multiple spaces with single ones
            dms = dms.replace("\\s+".toRegex(), " ")
            dms = dms.trim { it <= ' ' }

            // Check for sign prefix and suffix
            var sign = 1
            val suffix = dms.uppercase()[dms.length - 1]
            val prefix = dms[0]
            if (!suffix.isDigit()) {
                sign = if (suffix == 'S' || suffix == 'W') -1 else 1
                dms = dms.substring(0, dms.length - 1)
                dms = dms.trim { it <= ' ' }

                // check and trim the prefix if it is erroneously included
                if (!prefix.isDigit()) {
                    dms = dms.substring(1, dms.length)
                    dms = dms.trim { it <= ' ' }
                }
            } else if (!prefix.isDigit()) {
                sign *= if (prefix == '-') -1 else 1
                dms = dms.substring(1, dms.length)
            }

            // Extract degrees, minutes and seconds
            val dmsArray = dms.split(" ")
            val d = dmsArray[0].toInt()
            val m = if (dmsArray.size > 1) dmsArray[1].toInt() else 0
            val s = if (dmsArray.size > 2) dmsArray[2].toDouble() else 0.0
            return fromDMS(d, m, s) * sign.toDouble()
        }

        /**
         * Restricts an angle to the range [-180, +180] degrees, wrapping angles outside the range. Wrapping takes place as
         * though traversing the edge of a unit circle; angles less than -180 wrap back to +180, while angles greater than
         * +180 wrap back to -180.
         *
         * @param degrees the angle to wrap in degrees
         *
         * @return the specified angle wrapped to [-180, +180] degrees
         */
        @JvmStatic
        fun normalizeAngle180(degrees: Double): Double {
            val angle = degrees % 360
            return if (angle > 180) angle - 360 else if (angle < -180) 360 + angle else angle
        }

        /**
         * Restricts an angle to the range [0, 360] degrees, wrapping angles outside the range. Wrapping takes place as
         * though traversing the edge of a unit circle; angles less than 0 wrap back to 360, while angles greater than 360
         * wrap back to 0.
         *
         * @param degrees the angle to wrap in degrees
         *
         * @return the specified angle wrapped to [0, 360] degrees
         */
        @JvmStatic
        fun normalizeAngle360(degrees: Double): Double {
            val angle = degrees % 360
            return if (angle >= 0) angle else 360 + angle
        }

        /**
         * Restricts an angle to the range [-90, +90] degrees, wrapping angles outside the range. Wrapping takes place along
         * a line of constant longitude which may pass through the poles. In which case, 135 degrees normalizes to 45
         * degrees; 181 degrees normalizes to -1 degree.
         *
         * @param degrees the angle to wrap in degrees
         *
         * @return the specified angle wrapped to the range [-90, +90] degrees
         */
        @JvmStatic
        fun normalizeLatitude(degrees: Double): Double {
            val lat = degrees % 180
            val normalizedLat = if (lat > 90) 180 - lat else if (lat < -90) -180 - lat else lat
            // Determine whether the latitude is in the north or south hemisphere
            val numEquatorCrosses = (degrees / 180).toInt()
            return if (numEquatorCrosses % 2 == 0) normalizedLat else -normalizedLat
        }

        /**
         * Restricts an angle to the range [-180, +180] degrees, wrapping angles outside the range. Wrapping takes place as
         * though traversing a line of constant latitude which may pass through the antimeridian; angles less than -180 wrap
         * back to +180, while angles greater than +180 wrap back to -180.
         *
         * @param degrees the angle to wrap in degrees
         *
         * @return the specified angle wrapped to the range [-180, +180] degrees
         */
        @JvmStatic
        fun normalizeLongitude(degrees: Double): Double {
            val lon = degrees % 360
            return if (lon > 180) lon - 360 else if (lon < -180) 360 + lon else lon
        }

        /**
         * Restricts an angle to the range [-180, +180] degrees, clamping angles outside the range. Angles less than -180
         * are returned as -180, and angles greater than +180 are returned as +180. Angles within the range are returned
         * unmodified.
         *
         * @param degrees the angle to clamp in degrees
         *
         * @return the specified angle clamped to the range [-180, +180] degrees
         */
        @JvmStatic
        fun clampAngle180(degrees: Double) = degrees.coerceIn(-180.0, 180.0)

        /**
         * Restricts an angle to the range [0, 360] degrees, clamping angles outside the range. Angles less than 0 are
         * returned as 0, and angles greater than 360 are returned as 360. Angles within the range are returned unmodified.
         *
         * @param degrees the angle to clamp in degrees
         *
         * @return the specified angle clamped to the range [0, 360] degrees
         */
        @JvmStatic
        fun clampAngle360(degrees: Double) = degrees.coerceIn(0.0, 360.0)

        /**
         * Restricts an angle to the range [-90, +90] degrees, clamping angles outside the range. Angles less than -90 are
         * returned as -90, and angles greater than +90 are returned as +90. Angles within the range are returned
         * unmodified.
         *
         * @param degrees the angle to clamp in degrees
         *
         * @return the specified angle clamped to the range [-90, +90] degrees
         */
        @JvmStatic
        fun clampLatitude(degrees: Double) = degrees.coerceIn(-90.0, 90.0)

        /**
         * Restricts an angle to the range [-180, +180] degrees, clamping angles outside the range. Angles less than -180
         * are returned as 0, and angles greater than +180 are returned as +180. Angles within the range are returned
         * unmodified.
         *
         * @param degrees the angle to clamp in degrees
         *
         * @return the specified angle clamped to the range [-180, +180] degrees
         */
        @JvmStatic
        fun clampLongitude(degrees: Double) = degrees.coerceIn(-180.0, 180.0)

        /**
         * Computes the linear interpolation of two angles in the range [-180, +180] degrees according to a specified
         * fractional amount. The fractional amount is interpreted as a relative proportion of the two angles, where 0.0
         * indicates the first angle, 0.5 indicates an angle half way between the two angles, and 1.0 indicates the second
         * angle.
         * <br>
         * The result of this method is undefined if the amount is outside the range [0, 1].
         *
         * @param amount the fractional proportion of the two angles in the range [0, 1]
         * @param angle1 the first angle in degrees
         * @param angle2 the second angle in degrees
         *
         * @return the interpolated angle in the range [-180, +180] degrees
         */
        @JvmStatic
        fun interpolateAngle180(amount: Double, angle1: Angle, angle2: Angle): Angle {
            // Normalize the two angles to the range [-180, +180].
            var normalizedAngle1 = normalizeAngle180(angle1.inDegrees)
            var normalizedAngle2 = normalizeAngle180(angle2.inDegrees)

            // If the shortest arc between the two angles crosses the -180/+180 degree boundary, add 360 degrees to the
            // smaller of the two angles then interpolate.
            if (normalizedAngle1 - normalizedAngle2 > 180) normalizedAngle2 += 360.0
            else if (normalizedAngle1 - normalizedAngle2 < -180) normalizedAngle1 += 360.0

            // Linearly interpolate between the two angles then normalize the interpolated result. Normalizing the result is
            // necessary when we have added 360 degrees to either angle in order to interpolate along the shortest arc.
            val angle = (1 - amount) * normalizedAngle1 + amount * normalizedAngle2
            return normalizeAngle180(angle).degrees
        }

        /**
         * Computes the linear interpolation of two angles in the range [0, 360] degrees according to a specified fractional
         * amount. The fractional amount is interpreted as a relative proportion of the two angles, where 0.0 indicates the
         * first angle, 0.5 indicates an angle half way between the two angles, and 1.0 indicates the second angle.
         * <br>
         * The result of this method is undefined if the amount is outside the range [0, 1].
         *
         * @param amount the fractional proportion of the two angles in the range [0, 1]
         * @param angle1 the first angle
         * @param angle2 the second angle
         *
         * @return the interpolated angle in the range [0, 360] degrees
         */
        @JvmStatic
        fun interpolateAngle360(amount: Double, angle1: Angle, angle2: Angle): Angle {
            // Normalize the two angles to the range [-180, +180].
            var normalizedAngle1 = normalizeAngle180(angle1.inDegrees)
            var normalizedAngle2 = normalizeAngle180(angle2.inDegrees)

            // If the shortest arc between the two angles crosses the -180/+180 degree boundary, add 360 degrees to the
            // smaller of the two angles then interpolate.
            if (normalizedAngle1 - normalizedAngle2 > 180) normalizedAngle2 += 360.0
            else if (normalizedAngle1 - normalizedAngle2 < -180) normalizedAngle1 += 360.0

            // Linearly interpolate between the two angles then normalize the interpolated result. Normalizing the result is
            // necessary when we have added 360 degrees to either angle in order to interpolate along the shortest arc.
            val angle = (1 - amount) * normalizedAngle1 + amount * normalizedAngle2
            return normalizeAngle360(angle).degrees
        }

        /**
         * Obtains the average of two angles. This method is commutative, so `midAngle(m, n)` and
         * `midAngle(n, m)` are equivalent.
         *
         * @param a1 the first angle.
         * @param a2 the second angle.
         *
         * @return the average of `a1` and `a2`
         */
        @JvmStatic
        fun average(a1: Angle, a2: Angle) = fromDegrees(0.5 * (a1.inDegrees + a2.inDegrees))

        @JvmStatic
        fun max(a: Angle, b: Angle) = if (a.inDegrees >= b.inDegrees) a else b

        @JvmStatic
        fun min(a: Angle, b: Angle) = if (a.inDegrees <= b.inDegrees) a else b

        /**
         * Indicates whether a specified value is within the normal range of latitude, [-90, 90].
         * @param degrees The value to test, in degrees.
         * @returns true if the value is within the normal range of latitude, otherwise false.
         */
        @JvmStatic
        fun isValidLatitude(degrees: Double) = degrees >= -90 && degrees <= 90

        /**
         * Indicates whether a specified value is within the normal range of longitude, [-180, 180].
         * @param degrees The value to test, in degrees.
         * @returns true if the value is within the normal range of longitude, otherwise false.
         */
        @JvmStatic
        fun isValidLongitude(degrees: Double) = degrees >= -180 && degrees <= 180
    }

    init {
        // NaN value is not suppoted due to unpredictable `compareTo(NaN)` behavior
        require(!inDegrees.isNaN()) {
            logMessage(ERROR, "Angle", "init", "NaN is not supported!")
        }
    }

    /**
     * Obtains the sum of these two angles.
     * This method is commutative, so `a.add(b)` and `b.add(a)` are equivalent.
     * Neither this angle nor angle is changed, instead the result is returned as a new angle.
     *
     * @param angle the angle to add to this one.
     *
     * @return an angle whose size is the total of these angles and angles size.
     */
    operator fun plus(angle: Angle) = fromDegrees(inDegrees + angle.inDegrees)
    fun plusDegrees(degrees: Double) = fromDegrees(this.inDegrees + degrees)
    fun plusRadians(radians: Double) = fromRadians(this.inRadians + radians)

    /**
     * Obtains the difference of these two angles. This method is not commutative.
     * Neither this angle nor angle is changed, instead the result is returned as a new angle.
     *
     * @param angle the angle to subtract from this angle.
     *
     * @return a new angle corresponding to this angle's size minus angle's size.
     */
    operator fun minus(angle: Angle) = fromDegrees(inDegrees - angle.inDegrees)
    fun minusDegrees(degrees: Double) = fromDegrees(this.inDegrees - degrees)
    fun minusRadians(radians: Double) = fromRadians(this.inRadians - radians)

    /**
     * Multiplies this angle by another angle.
     * This method is commutative, so `a.multiply(b)` and `b.multiply(a)` are equivalent.
     * This angle remains unchanged. The result is returned as a new angle.
     *
     * @param angle the angle by which to multiply.
     *
     * @return a new angle whose size equals this angle's size multiplied by angle's size.
     */
    operator fun times(angle: Angle) = this * angle.inDegrees

    /**
     * Multiplies this angle by `multiplier`.
     * This method is commutative, so `a.multiply(b)` and `b.multiply(a)` are equivalent.
     * This angle remains unchanged. The result is returned as a new angle.
     *
     * @param multiplier a scalar by which this angle is multiplied.
     *
     * @return a new angle whose size equals this angle's size multiplied by `multiplier`.
     */
    operator fun times(multiplier: Number) = fromDegrees(inDegrees * multiplier.toDouble())

    /**
     * Divides this angle by another angle.
     * This angle remains unchanged. The result is returned as a new angle.
     *
     * @param angle the angle by which to divide.
     *
     * @return this angle's degrees divided by angle's degrees.
     */
    operator fun div(angle: Angle) = this / angle.inDegrees

    /**
     * Divides this angle by another angle.
     * This angle remains unchanged. The result is returned as a new angle.
     *
     * @param divisor a scalar by which to divide.
     *
     * @return this angle's degrees divided by divisor.
     */
    operator fun div(divisor: Number): Angle {
        require(divisor != 0.0) {
            logMessage(ERROR, "Angle", "div", "divideByZero")
        }
        return fromDegrees(inDegrees / divisor.toDouble())
    }

    /**
     * Returns new angle with opposite sign.
     */
    operator fun unaryMinus() = Angle(-inDegrees)

    /**
     * Computes the shortest distance between this and angle, as an angle.
     *
     * @param angle the angle to measure angular distance to.
     *
     * @return the angular distance between this and `value`.
     */
    fun distanceTo(angle: Angle): Angle {
        var distance = angle.inDegrees - inDegrees
        if (distance < -180) distance += 360.0 else if (distance > 180) distance -= 360.0
        return abs(distance).degrees
    }

    fun normalize180() = if (inDegrees in -180.0..180.0) this else normalizeAngle180(inDegrees).degrees

    fun normalize360() = if (inDegrees in 0.0..360.0) this else normalizeAngle360(inDegrees).degrees

    fun normalizeLatitude() = if (inDegrees in -90.0..90.0) this else normalizeLatitude(inDegrees).degrees

    fun normalizeLongitude() = if (inDegrees in -180.0..180.0) this else normalizeLongitude(inDegrees).degrees

    fun clampAngle180() = inDegrees.coerceIn(-180.0, 180.0).degrees

    fun clampAngle360() = inDegrees.coerceIn(0.0, 360.0).degrees

    fun clampLatitude() = inDegrees.coerceIn(-90.0, 90.0).degrees

    fun clampLongitude() = inDegrees.coerceIn(-180.0, 180.0).degrees

    fun toDMS(): DoubleArray {
        var angle = inDegrees
        val sign = sign(angle)
        angle *= sign
        var d = floor(angle)
        angle = (angle - d) * 60.0
        var m = floor(angle)
        angle = (angle - m) * 60.0
        var s = round(angle * 100) / 100 // keep two decimals for seconds
        if (s == 60.0) {
            m++
            s = 0.0
        }
        if (m == 60.0) {
            d++
            m = 0.0
        }
        return doubleArrayOf(sign, d, m, s)
    }

    /**
     * Forms a decimal degrees [String] representation of this [Angle].
     *
     * @param digits the number of digits past the decimal point to include in the string.
     *
     * @return the value of this angle in decimal degrees as a string with the specified number of digits beyond the
     * decimal point. The string is padded with trailing zeros to fill the number of post-decimal point
     * positions requested.
     */
    fun toDDString(digits: Int = 6): String {
        require(digits in 0..15) {
            logMessage(ERROR, "Angle", "toDecimalDegreesString", "outOfRange")
        }
        return "%.${digits}f°".format(inDegrees)
    }

    /**
     * Obtains a [String] representation of this [Angle] formatted as degrees and decimal minutes.
     *
     * @param digits the number of digits past the decimal point to include in the string.
     *
     * @return the value of this angle in degrees and decimal minutes as a string.
     */
    fun toDMString(digits: Int = 3): String {
        val dms = toDMS()
        val mf = if (dms[3] == 0.0) dms[2] else dms[2] + dms[3] / 60.0
        return "${if (dms[0] < 0) "-" else ""}%d° %2.${digits}f’".format(dms[1], mf)
    }

    /**
     * Obtains a [String] representation of this [Angle] formatted as degrees, minutes and seconds.
     *
     * @param digits the number of digits past the decimal point to include in the string.
     *
     * @return the value of this angle in degrees, minutes and seconds as a string.
     */
    fun toDMSString(digits: Int = 1): String {
        val dms = toDMS()
        return "${if (dms[0] < 0) "-" else ""}%d° %2d’ %2.${digits}f”".format(dms[1], dms[2], dms[3])
    }

    /**
     * Compares this [Angle] with another. Returns a negative integer if this is the smaller angle, a positive
     * integer if this is the larger, and zero if both angles are equal.
     *
     * @param other the angle to compare against.
     *
     * @return -1 if this angle is smaller, 0 if both are equal and +1 if this angle is larger.
     */
    override operator fun compareTo(other: Angle) = inDegrees.compareTo(other.inDegrees)

    override fun toString() = "$inDegrees°"
}