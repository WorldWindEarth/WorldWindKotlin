package earth.worldwind.render

import android.content.res.Resources
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue.COMPLEX_UNIT_SP
import android.util.TypedValue.applyDimension

actual open class Font(var size: Float, var typeface: Typeface? = null) {
    actual constructor() : this(spToPx(DEFAULT_FONT_SIZE.toFloat()))
    actual constructor(family: String, weight: FontWeight, size: Int) : this(
        spToPx(size.toFloat()),
        Typeface.create(family, when(weight) {
            FontWeight.NORMAL -> Typeface.NORMAL
            FontWeight.BOLD -> Typeface.BOLD
            FontWeight.ITALIC -> Typeface.ITALIC
        })
    )

    // Per-instance scratch Paint so measureText/measureChars don't share mutable state with
    // the rasteriser. Paint is not thread-safe; the layer calls measure* on the fetch
    // coroutine or render thread, never concurrently for the same Font instance.
    private val measurementPaint: Paint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = this@Font.size
            typeface = this@Font.typeface
        }
    }

    actual fun measureText(text: String): Float {
        if (text.isEmpty()) return 0f
        return measurementPaint.measureText(text)
    }

    actual fun measureChars(text: String): FloatArray {
        if (text.isEmpty()) return FloatArray(0)
        val widths = FloatArray(text.length)
        measurementPaint.getTextWidths(text, widths)
        return widths
    }

    actual fun copy(font: Font) {
        size = font.size
        typeface = font.typeface
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Font
        if (size != other.size) return false
        if (typeface != other.typeface) return false
        return true
    }

    override fun hashCode(): Int {
        var result = size.hashCode()
        result = 31 * result + (typeface?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "Font(size=$size, typeface=$typeface)"
    }

    private companion object {
        // Resources.getSystem() returns null under the android.jar stub used by host
        // unit tests (build.gradle.kts: isReturnDefaultValues = true). Fall back to the
        // sp value unscaled so host-side commonTest code can construct a Font.
        fun spToPx(sizeSp: Float): Float =
            Resources.getSystem()?.displayMetrics
                ?.let { applyDimension(COMPLEX_UNIT_SP, sizeSp, it) }
                ?: sizeSp
    }
}