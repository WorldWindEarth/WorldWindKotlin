package earth.worldwind.shape.milstd2525

import kotlin.math.*

object MilStd2525Util {

    private const val ZERO_LEVEL_PX = 256

    fun computeNearestLoD(equatorialRadius: Double, scale: Double): Int {
        return (ln(2 * PI * equatorialRadius / ZERO_LEVEL_PX / scale) / ln(2.0)).roundToInt()
    }

    fun computeLoDScale(equatorialRadius: Double, lod: Int): Double {
        return 2 * PI * equatorialRadius / ZERO_LEVEL_PX / (1 shl lod)
    }

    fun formatAltitudeMSL(alt: Double): String {
        val feetPerMeter = 3.28084
        var result = "MSL"
        if (alt > 0) {
            result = (alt * feetPerMeter).roundToLong().toString() + " FT A" + result
        } else if (alt < 0) {
            result = (alt * -feetPerMeter).roundToLong().toString() + " FT B" + result
        }
        return result
    }

    fun formatSpeed(speed: Float): String {
        // TODO A text modifier for units and equipment that displays velocity as set forth in MIL-STD-6040.
        return speed.toString(2)
    }

    fun formatLocationDegrees(lat: Double, lon: Double): String {
        var result = ""
        result += lat.toString(5)
        result += if (lat >= 0) "N" else "S"
        result += lon.toString(5)
        result += if (lon >= 0) "E" else "W"
        return result
    }

    /**
     * Return the number receiver as a string display with numOfDec after the decimal (rounded)
     *
     * @param numOfDec number of decimal places to show
     * @return the String representation of the receiver up to numOfDec decimal places
     */
    private fun Number.toString(numOfDec: Int): String {
        val integerDigits = this.toInt()
        val decimalDigits = ((this.toDouble() - integerDigits) * 10.0.pow(numOfDec)).roundToInt()
        return "${integerDigits}.${decimalDigits}"
    }

}