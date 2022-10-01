package earth.worldwind.util.format

import kotlin.math.*

class ExponentFormatter(val value: Double) {
    var mantissa: Double
        private set
    var exponent: Int
        private set

    private val mstr: String
    private val strExponent: String

    init {
        val x = abs(value)
        exponent = log10(x).toInt()
        if (exponent < 0) exponent--
        println("$x e: $exponent ${10.0.pow(exponent)} ${x / 10.0.pow(exponent)}")
        mantissa = x / 10.0.pow(exponent)
        if (value < 0) mantissa = -mantissa
        mstr = mantissa.toString()
        strExponent = "e$exponent"
        println("   / $mantissa $exponent ($strExponent)")
    }

    fun scientific(width: Int, fractionWidth: Int = -1): String {
        val minLength = if (mantissa < 0) 2 else 1

        // Get the desired part of mantissa with proper bounding and rounding
        // it wirks only for "normalized" mantissa that is always has d[.ddddd] form, e.g.  integer
        // part is always 1 digit long
        fun mpart(length: Int): String {
            var l = length
            if (l > mstr.length) l = mstr.length
            val result = StringBuilder(mstr.slice(0 until l))
            // exact value, no rounding:
            if (result.length == mstr.length) return result.toString()

            // last visible digit index
            var lastIndex = result.length - 1
            if (result[lastIndex] == '.') lastIndex--

            // next significant digit
            var nextDigit = mstr[result.length]
            if (nextDigit == '.') {
                if (result.length + 1 >= mstr.length) return result.toString()
                nextDigit = mstr[result.length + 1]
            }
            if (nextDigit in "56789") result[lastIndex] = result[lastIndex] + 1
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

        // fractionWitdth > 0, +1 for decimal dot
        return mpart(minLength + 1 + fractionWidth) + strExponent
    }


    override fun toString() = "${mantissa}e${exponent}"
}

fun scientificFormat(value: Double, width: Int, fractionPartLength: Int = -1) =
    ExponentFormatter(value).scientific(width, fractionPartLength)

fun fractionalFormat(_value: Double, width: Int, fractionPartLength: Int = -1): String {
    var value = _value
    val result = StringBuilder()

    if (abs(value) >= 1) {
        val i = if (fractionPartLength == 0) value.roundToLong() else value.toLong()
        result.append(i)
        result.append('.')
        value -= i
    } else result.append((if (value < 0) "-0." else "0."))

    //val result = StringBuilder(if( value < 0) "-0." else "0.")
    var fl = if (fractionPartLength < 0) {
        if (width < 0) 6
        else width - result.length
    } else fractionPartLength
    var rest = value * 10
    while (fl-- > 0) {
        val d = if (fl > 0) rest.toInt() else rest.roundToInt()
        result.append(abs(d))
        rest = (rest - d) * 10
    }
    return result.toString()
}
