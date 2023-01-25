package earth.worldwind.util.format

import kotlin.math.*

class ExponentFormatter(val value: Double) {
    var mantissa: Double
        private set
    var exponent: Int = 0
        private set(value) {
            field = value
            strExponent = "e$exponent"
        }

    private val mstr: String
    private var strExponent: String

    init {
        val x = abs(value)
        exponent = log10(x).toInt()
        if (exponent < 0) exponent--
        mantissa = x / 10.0.pow(exponent)
        if (value < 0) mantissa = -mantissa
        mstr = mantissa.toString()
        strExponent = "e$exponent"
    }

    fun scientific(width: Int, fractionWidth: Int = -1): String {
        val minLength = if (mantissa < 0) 2 else 1

        // Get the desired part of mantissa with proper bounding and rounding
        // it works only for "normalized" mantissa that is always has d[.ddddd] form, e.g.  integer
        // part is always 1 digit long
        //
        // ERROR: this rounding does not work with trailint (***9)8 - like variants
        //
        fun mpart(length: Int): String {
            var l = length
            if (l > mstr.length) l = mstr.length
            val result = StringBuilder(mstr.slice(0 until l))
            // exact value, no rounding:
            if (result.length == mstr.length) return result.toString()

            // next significant digit
            var nextDigit = mstr[result.length]
            if (nextDigit == '.') {
                if (result.length + 1 >= mstr.length) return result.toString()
                nextDigit = mstr[result.length + 1]
            }
            if (nextDigit in "56789") {
                val (m, ovf) = roundUp(result)
                if( !ovf ) return m
                // overflow: exponent should grow
                exponent++
                // and the point position should be fixed
                val pointPos = m.indexOf('.')
                val mb = StringBuilder(m)
                if( pointPos == -1 )
                    return m // it was the last letter and was therefore removed by roundUp
                return mb.deleteAt(pointPos).insert(pointPos - 1, '.').toString()
            }
            return result.toString()
        }

        if (width == 0) return mstr + strExponent

        if (fractionWidth < 0 && width > 0) {
            var l = width - strExponent.length
            if (l < minLength) l = minLength
            return mpart(l) + strExponent
        }

        if (fractionWidth < 0 && width < 0) return mstr + strExponent

        // fractionWidth >= 0
        if (fractionWidth == 0) return "${mstr[0]}$strExponent"

        // fractionWidth > 0, +1 for decimal dot
        return mpart(minLength + 1 + fractionWidth) + strExponent
    }


    override fun toString() = "${mantissa}e${exponent}"
}

internal fun scientificFormat(value: Double, width: Int, fractionPartLength: Int = -1) =
    ExponentFormatter(value).scientific(width, fractionPartLength)

internal fun fractionalFormat(_value: Double, width: Int, fractionPartLength: Int = -1): String {
    var value = _value
    val result = StringBuilder()

    if (abs(value) >= 1) {
        val i = if (fractionPartLength == 0) value.roundToLong() else value.toLong()
        result.append(i)
        value -= i
    } else result.append((if (value < 0) "-0" else "0"))

    var fl = if (fractionPartLength < 0) {
        if (width < 0) 6
        else width - result.length - 1
    } else fractionPartLength

    if (fl != 0) result.append('.')

    var rest = value * 10
    while (fl-- > 0) {
        val d = rest.toInt()
        result.append(abs(d))
        rest = (rest - d) * 10
    }
    // now we might need to round it up:
    return if( rest.toInt().absoluteValue < 5 ) result.toString() else roundUp(result, keepWidth = false).first
}

/**
 * Round up the mantissa part (call it with default arguments to start).
 * @return rounded mantissa and overflow flag (set when 9,99 -> 10,00 and like)
 */
private fun roundUp(
    result: StringBuilder,
    length: Int = result.length,
    pos: Int = result.length - 1,
    keepWidth: Boolean = true
): Pair<String,Boolean> {
    if (pos < 0) {
        // if we get there, it means the number of digits should grow, like "9.99" -> "10.00"
        // but we need to keep the length so "10.0":
        result.insert(0, '1')
        if (keepWidth) result.deleteAt(length)
        return result.toString() to true
    }
    // not the first digit: perform rounding:
    val d = result[pos]
    // it could be a decimal point we ignore and continue with rounding
    if (d == '.') return roundUp(result, length, pos - 1, keepWidth)

    // Small number add one "0.19" -> "0.2"
    // Simple case: alter only the current digit
    if (d != '9') {
        result[pos] = d + 1
        return result.toString() to false
    }
    // Complex case:  9->0 and propagate changes up.
    result[pos] = '0'
    return roundUp(result, length, pos - 1, keepWidth)
}