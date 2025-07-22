package earth.worldwind.util.math

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln

/**
 * Restricts a value to the range [min, max] degrees, clamping values outside the range. Values less than min are
 * returned as min, and values greater than max are returned as max. Values within the range are returned
 * unmodified.
 * <br>
 * The result of this method is undefined if min is greater than max.
 *
 * @param value the values to clamp
 * @param min   the minimum value
 * @param max   the maximum value
 *
 * @return the specified values clamped to the range [min, max] degrees
 */
fun clamp(value: Double, min: Double, max: Double) = value.coerceIn(min, max)

/**
 * Returns the fractional part of a specified number
 *
 * @param value the number whose fractional part to compute
 *
 * @return The fractional part of the specified number: value - floor(value)
 */
fun fract(value: Double) = value - floor(value)

/**
 * Computes the linear interpolation of two values according to a specified fractional amount. The fractional amount
 * is interpreted as a relative proportion of the two values, where 0.0 indicates the first value, 0.5 indicates a
 * 50/50 mix of the two values, and 1.0 indicates the second value.
 * <br>
 * The result of this method is undefined if the amount is outside the range [0, 1].
 *
 * @param amount the fractional proportion of the two values in the range [0, 1]
 * @param value1 the first value
 * @param value2 the second value
 *
 * @return the interpolated value
 */
fun interpolate(amount: Double, value1: Double, value2: Double) = (1 - amount) * value1 + amount * value2

/**
 * Returns the integer modulus of a specified number. This differs from the % operator in that the result is
 * always positive when the modulus is positive. For example -1 % 10 = -1, whereas mod(-1, 10) = 1.
 *
 * @param value   the integer number whose modulus to compute
 * @param modulus the modulus
 *
 * @return the remainder after dividing the number by the modulus
 */
fun mod(value: Int, modulus: Int) = (value % modulus + modulus) % modulus

/**
 * Indicates whether a specified value is a power of two.
 *
 * @param value the value to test
 *
 * @return true if the specified value is a power of two, false othwerwise
 */
fun isPowerOfTwo(value: Int) = value != 0 && value and value - 1 == 0

/**
 * Returns the value that is the nearest power of 2 greater than or equal to the given value.
 *
 * @param value the reference value. The power of 2 returned is greater than or equal to this value.
 *
 * @return the value that is the nearest power of 2 greater than or equal to the reference value
 */
fun powerOfTwoCeiling(value: Int): Int {
    val pow = ceil(ln(value.toDouble()) / ln(2.0)).toInt()
    return 1 shl pow
}

/**
 * Packs vec2 into one float value.
 *
 * @param x x coordinate of vector should be in range -1 to 1
 * @param y y coordinate of vector should be in range -1 to 1
 *
 * @return the float value with encoded coordinates
 */
fun encodeOrientationVector(x: Float, y: Float) = x + 0.5f * y