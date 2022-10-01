package earth.worldwind.util.math

import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Viewport
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

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
 * Computes the bounding rectangle for a unit square after applying a transformation matrix to the square's four
 * corners.
 *
 * @param unitSquareTransform the matrix to apply to the unit square
 * @param result              a pre-allocated Viewport in which to return the computed bounding rectangle
 *
 * @return the result argument set to the computed bounding rectangle
 */
fun boundingRectForUnitSquare(unitSquareTransform: Matrix4, result: Viewport): Viewport {
    val m = unitSquareTransform.m

    // transform of (0, 0)
    val x1 = m[3]
    val y1 = m[7]

    // transform of (1, 0)
    val x2 = m[0] + m[3]
    val y2 = m[4] + m[7]

    // transform of (0, 1)
    val x3 = m[1] + m[3]
    val y3 = m[5] + m[7]

    // transform of (1, 1)
    val x4 = m[0] + m[1] + m[3]
    val y4 = m[4] + m[5] + m[7]
    val minX = min(min(x1, x2), min(x3, x4)).toInt()
    val maxX = max(max(x1, x2), max(x3, x4)).toInt()
    val minY = min(min(y1, y2), min(y3, y4)).toInt()
    val maxY = max(max(y1, y2), max(y3, y4)).toInt()
    return result.set(minX, minY, maxX - minX, maxY - minY)
}

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
    val pow = floor(ln(value.toDouble()) / ln(2.0)).toInt()
    return 1 shl pow
}