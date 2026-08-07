package earth.worldwind.render

import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlinx.browser.document
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement

/**
 * Holds attributes controlling the style, size and other attributes of [earth.worldwind.shape.Label] shapes and
 * the textual features of [earth.worldwind.shape.Placemark] and other shapes. The values used for these attributes are those
 * defined by the <a href="http://www.w3schools.com/cssref/pr_font_font.asp">CSS Font property</a>.
 */
actual open class Font(
    /**
     * The font size.
     */
    var size: Int,
    /**
     * The font family.
     * @see <a href="http://www.w3schools.com/cssref/pr_font_font-family.asp">CSS font-family</a> for defined values.
     */
    var family: String = "sans-serif",
    /**
     * The font weight.
     * @see <a href="http://www.w3schools.com/cssref/pr_font_weight.asp">CSS font-weight</a> for defined values.
     */
    var weight: String = "normal",
    /**
     * The font style.
     * @see <a href="http://www.w3schools.com/cssref/pr_font_font-style.asp">CSS font-style</a> for defined values.
     */
    var style: String = "normal",
    /**
     * The font variant.
     * @see <a href="http://www.w3schools.com/cssref/pr_font_font-variant.asp">CSS font-variant</a> for defined values.
     */
    var variant: String = "normal"
) {
    actual constructor(): this(DEFAULT_FONT_SIZE)
    actual constructor(family: String, weight: FontWeight, size: Int): this(
        size, family,
        if (weight == FontWeight.BOLD) "bold" else "normal",
        if (weight == FontWeight.ITALIC) "italic" else "normal",
    )

    init {
        require(size > 0) {
            logMessage(ERROR, "Font", "constructor", "invalidSize");
        }
    }

    actual fun measureText(text: String): Float {
        if (text.isEmpty()) return 0f
        val ctx = sharedMeasurementContext()
        ctx.font = toString()
        return ctx.measureText(text).width.toFloat()
    }

    actual fun measureChars(text: String): FloatArray {
        if (text.isEmpty()) return FloatArray(0)
        val ctx = sharedMeasurementContext()
        ctx.font = toString()
        return FloatArray(text.length) { i ->
            ctx.measureText(text[i].toString()).width.toFloat()
        }
    }

    actual fun copy(font: Font) {
        size = font.size
        style = font.style
        variant = font.variant
        weight = font.weight
        family = font.family
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Font) return false
        if (size != other.size) return false
        if (style != other.style) return false
        if (variant != other.variant) return false
        if (weight != other.weight) return false
        if (family != other.family) return false
        return true
    }

    override fun hashCode(): Int {
        var result = size
        result = 31 * result + style.hashCode()
        result = 31 * result + variant.hashCode()
        result = 31 * result + weight.hashCode()
        result = 31 * result + family.hashCode()
        return result
    }

    /**
     * A string representing this font's style, weight, size and family properties, suitable for
     * passing directly to a 2D canvas context.
     */
    override fun toString() = "$style $variant $weight ${size}px $family"

    private companion object {
        // Single offscreen 2D context used for text measurement. JS is single-threaded so
        // sharing one context is safe; constructing a fresh canvas per call would be much
        // slower for the per-glyph layout path.
        private var sharedCtx: CanvasRenderingContext2D? = null

        fun sharedMeasurementContext(): CanvasRenderingContext2D {
            sharedCtx?.let { return it }
            val canvas = document.createElement("canvas") as HTMLCanvasElement
            canvas.width = 1
            canvas.height = 1
            val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
            sharedCtx = ctx
            return ctx
        }
    }
}