package earth.worldwind.render

import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.jvm.JvmOverloads
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Color with red, green, blue and alpha components. Each RGB component is a number between 0.0 and 1.0 indicating the
 * component's intensity. The alpha component is a number between 0.0 (fully transparent) and 1.0 (fully opaque)
 * indicating the color's opacity.
 */
open class Color @JvmOverloads constructor(
    /**
     * The color's red component.
     */
    var red: Float,
    /**
     * The color's green component.
     */
    var green: Float,
    /**
     * The color's blue component.
     */
    var blue: Float,
    /**
     * The color's alpha component.
     */
    var alpha: Float = 1f
) {
    /**
     * Constructs a color with red, green, blue and alpha all 1.0.
     */
    constructor(): this(red = 1f, green = 1f, blue = 1f, alpha = 1f)

    /**
     * Constructs a color from integer components
     *
     * @param red   the new red component
     * @param green the new green component
     * @param blue  the new blue component
     * @param alpha the new alpha component
     *
     * @return this color with its components set to the specified values
     */
    @JvmOverloads
    constructor(red: Int, green: Int, blue: Int, alpha: Int = 255): this(
        red = red / 255f,
        green = green / 255f,
        blue = blue / 255f,
        alpha = alpha / 255f
    )

    /**
     * Constructs a color with components stored in a color int. Color ints are stored as packed ints as follows:
     * `(alpha << 24) | (red << 16) | (green << 8) | (blue)`. Each component is an 8 bit number between 0 and
     * 255 with 0 indicating the component's intensity.
     *
     * @param colorInt the color int specifying the components
     */
    constructor(colorInt: Int): this(
        red = red(colorInt) / 0xFF.toFloat(),
        green = green(colorInt) / 0xFF.toFloat(),
        blue = blue(colorInt) / 0xFF.toFloat(),
        alpha = alpha(colorInt) / 0xFF.toFloat()
    )

    /**
     * Constructs a color with the components of a specified color.
     *
     * @param color the color specifying the components
     */
    constructor(color: Color): this(color.red, color.green, color.blue, color.alpha)

    /**
     * Sets this color to the specified components.
     *
     * @param red   the new red component
     * @param green the new green component
     * @param blue  the new blue component
     * @param alpha the new alpha component
     *
     * @return this color with its components set to the specified values
     */
    fun set(red: Float, green: Float, blue: Float, alpha: Float) = apply {
        this.red = red
        this.green = green
        this.blue = blue
        this.alpha = alpha
    }

    /**
     * Sets this color to the components stored in a color int. Color ints are stored as packed ints as follows:
     * `(alpha << 24) | (red << 16) | (green << 8) | (blue)`. Each component is an 8 bit number between 0 and
     * 255 with 0 indicating the component's intensity.
     *
     * @param colorInt the color int specifying the new components
     *
     * @return this color with its components set to those of the specified color int
     */
    fun set(colorInt: Int) = set(
        red = red(colorInt) / 0xFF.toFloat(),
        green = green(colorInt) / 0xFF.toFloat(),
        blue= blue(colorInt) / 0xFF.toFloat(),
        alpha = alpha(colorInt) / 0xFF.toFloat()
    )

    /**
     * Sets this color to the components of a specified color.
     *
     * @param color the color specifying the new components
     *
     * @return this color with its components set to that of the specified color
     */
    fun copy(color: Color) = set(color.red, color.green, color.blue, color.alpha)

    /**
     * Copies this color's components to the specified array. The result is compatible with GLSL uniform vectors, and
     * can be passed to the function glUniform4fv.
     *
     * @param result a pre-allocated array of length 4 in which to return the components
     * @param offset a starting index in the result array
     *
     * @return the result argument set to this color's components
     */
    fun toArray(result: FloatArray, offset: Int): FloatArray {
        var o = offset
        require(result.size - o >= 4) {
            logMessage(ERROR, "Color", "toArray", "missingArray")
        }
        result[o++] = red
        result[o++] = green
        result[o++] = blue
        result[o] = alpha
        return result
    }

    /**
     * Returns this color's components as a color int. Color ints are stored as packed ints as follows: `(alpha <<
     * 24) | (red << 16) | (green << 8) | (blue)`. Each component is an 8 bit number between 0 and 255 with 0
     * indicating the component's intensity.
     *
     * @return this color converted to a color int
     */
    fun toColorInt(): Int {
        val r8 = (red * 0xFF).roundToInt()
        val g8 = (green * 0xFF).roundToInt()
        val b8 = (blue * 0xFF).roundToInt()
        val a8 = (alpha * 0xFF).roundToInt()
        return argb(a8, r8, g8, b8)
    }

    /**
     * Convert the argb color to its HSV components.
     *     hsv[0] is Hue [0 .. 360)
     *     hsv[1] is Saturation [0...1]
     *     hsv[2] is Value [0...1]
     * @param hsv  3 element array which holds the resulting HSV components.
     */
    fun toHSV(hsv: FloatArray) {
        val v = red.coerceAtLeast(green).coerceAtLeast(blue)
        val diff = v - red.coerceAtMost(green).coerceAtMost(blue)
        var h: Float
        val s: Float
        if (diff == 0f) {
            h = 0f
            s = 0f
        } else {
            s = diff / v
            val rr = (v - red) / 6f / diff + 1f / 2f
            val gg = (v - green) / 6f / diff + 1f / 2f
            val bb = (v - blue) / 6f / diff + 1f / 2f

            h = if (red == v) bb - gg
            else if (green == v) (1f / 3f) + rr - bb
            else if (blue == v) (2f / 3f) + gg - rr
            else 0f
            if (h < 0f) h += 1f else if (h > 1f) h -= 1f
        }
        hsv[0] = h * 360f
        hsv[1] = s
        hsv[2] = v
    }

    /**
     * Premultiplies this color in place. The RGB components are multiplied by the alpha component.
     *
     * @return this color with its RGB components multiplied by its alpha component
     */
    fun premultiply() = apply {
        red *= alpha
        green *= alpha
        blue *= alpha
    }

    /**
     * Premultiplies the specified color and stores the result in this color. This color's RGB components are set to the
     * product of the specified color's RGB components and its alpha component. This color's alpha component is set to
     * the specified color's alpha.
     *
     * @param color the color with components to premultiply and store in this color
     *
     * @return this color set to the premultiplied components of the specified color
     */
    fun premultiplyColor(color: Color) = apply {
        red = color.red * color.alpha
        green = color.green * color.alpha
        blue = color.blue * color.alpha
        alpha = color.alpha
    }

    /**
     * Copies this color's premultiplied components to the specified array. The result is compatible with GLSL uniform
     * vectors, and can be passed to the function glUniform4fv.
     *
     * @param result a pre-allocated array of length 4 in which to return the components
     * @param offset a starting index in the result array
     *
     * @return the result argument set to this color's premultiplied components
     */
    fun premultiplyToArray(result: FloatArray, offset: Int): FloatArray {
        var o = offset
        require(result.size - o >= 4) {
            logMessage(ERROR, "Color", "premultiplyToArray", "missingArray")
        }
        result[o++] = red * alpha
        result[o++] = green * alpha
        result[o++] = blue * alpha
        result[o] = alpha
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Color) return false
        return red == other.red && green == other.green && blue == other.blue && alpha == other.alpha
    }

    override fun hashCode(): Int {
        var result = red.hashCode()
        result = 31 * result + green.hashCode()
        result = 31 * result + blue.hashCode()
        result = 31 * result + alpha.hashCode()
        return result
    }

    override fun toString() = "Color(red=$red, green=$green, blue=$blue, alpha=$alpha)"

    /**
     * Returns a string representation of this color, indicating the byte values corresponding to this color's
     * floating-point component values.
     *
     * @returns Byte string
     */
    fun toByteString(): String {
        val rb = (red * 255).roundToInt()
        val gb = (green * 255).roundToInt()
        val bb = (blue * 255).roundToInt()
        val ab = (alpha * 255).roundToInt()
        return "($rb,$gb,$bb,$ab)"
    }

    /**
     * Create a hex color string that CSS can use. Optionally, inhibit capturing alpha,
     * because some uses reject a four-component color specification.
     *
     * @param isUsingAlpha Enable the use of an alpha component.
     * @param argb If true use #AARRGGBB sequence, otherwise use #RRGGBBAA
     * @returns A color string suitable for CSS.
     */
    fun toHexString(isUsingAlpha: Boolean = false, argb: Boolean = false): String {
        // Use Math.ceil() to get 0.75 to map to 0xc0. This is important if the display is dithering.
        val redHex = ceil(red * 255).toInt().toString(16)
        val greenHex = ceil(green * 255).toInt().toString(16)
        val blueHex = ceil(blue * 255).toInt().toString(16)
        val alphaHex = ceil(alpha * 255).toInt().toString(16)

        var result = "#"
        if (isUsingAlpha && argb) result += if (alphaHex.length < 2) ("0$alphaHex") else alphaHex
        result += if (redHex.length < 2) ("0$redHex") else redHex
        result += if (greenHex.length < 2) ("0$greenHex") else greenHex
        result += if (blueHex.length < 2) ("0$blueHex") else blueHex
        if (isUsingAlpha && !argb) result += if (alphaHex.length < 2) ("0$alphaHex") else alphaHex
        return result
    }

    /**
     * Create a rgba color string that conforms to CSS Color Module Level 3 specification.
     * @returns A color string suitable for CSS.
     */
    fun toCssColorString(): String {
        val red = (red * 255).roundToInt()
        val green = (green * 255).roundToInt()
        val blue = (blue * 255).roundToInt()

        // Per the CSS Color Module Level 3 specification, alpha is expressed as floating point value between 0 - 1
        return "rgba($red, $green, $blue, $alpha)"
    }

    companion object {
        /**
         * @param hexString representing hex value
         * (formatted "0xRRGGBB" i.e. "0xFFFFFF")
         * OR
         * formatted "0xAARRGGBB" i.e. "0x00FFFFFF" for a color with an alpha value
         * I will also put up with "RRGGBB" and "AARRGGBB" without the starting "0x"
         * @param argb If true use #AARRGGBB sequence, otherwise use #RRGGBBAA
         * @return color represented by hex string
         */
        fun fromHexString(hexString: String, argb: Boolean = false): Color {
            val hexValue = when {
                hexString[0] == '#' -> hexString.substring(1)
                hexString.substring(0, 2).equals("0x", true) -> hexString.substring(2)
                else -> hexString
            }.uppercase()

            val length = hexValue.length

            return if (length == 8 || length == 6) {
                val hexAlphabet = "0123456789ABCDEF"
                val value = intArrayOf(0, 0, 0, 0)
                for ((k, i) in (0 until length step 2).withIndex()) {
                    val int1 = hexAlphabet.indexOf(hexValue[i])
                    val int2 = hexAlphabet.indexOf(hexValue[i + 1])
                    value[k] = int1 * 16 + int2
                }

                when (length) {
                    8 -> if (argb) Color(value[1],value[2],value[3],value[0]) else Color(value[0],value[1],value[2],value[3])
                    6 -> Color(value[0],value[1],value[2])
                    else -> error("Bad hex value: $hexString")
                }
            } else error("Bad hex value: $hexString")
        }

        /**
         * Return the alpha component of a color int. This is the same as saying
         * color >>> 24
         */
        private fun alpha(color: Int) = color ushr 24

        /**
         * Return the red component of a color int. This is the same as saying
         * (color >> 16) & 0xFF
         */
        private fun red(color: Int) = color shr 16 and 0xFF

        /**
         * Return the green component of a color int. This is the same as saying
         * (color >> 8) & 0xFF
         */
        private fun green(color: Int) = color shr 8 and 0xFF

        /**
         * Return the blue component of a color int. This is the same as saying
         * color & 0xFF
         */
        private fun blue(color: Int) = color and 0xFF

        /**
         * Return a color-int from alpha, red, green, blue components.
         * These component values should be \([0..255]\), but there is no
         * range check performed, so if they are out of range, the
         * returned color is undefined.
         * @param alpha Alpha component \([0..255]\) of the color
         * @param red Red component \([0..255]\) of the color
         * @param green Green component \([0..255]\) of the color
         * @param blue Blue component \([0..255]\) of the color
         */
        private fun argb(alpha: Int, red: Int, green: Int, blue: Int) = alpha shl 24 or (red shl 16) or (green shl 8) or blue
    }
}