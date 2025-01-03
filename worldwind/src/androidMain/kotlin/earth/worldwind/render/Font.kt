package earth.worldwind.render

import android.content.res.Resources
import android.graphics.Typeface
import android.util.TypedValue.COMPLEX_UNIT_SP
import android.util.TypedValue.applyDimension

actual open class Font(var size: Float, var typeface: Typeface? = null) {
    actual constructor() : this(
        applyDimension(COMPLEX_UNIT_SP, DEFAULT_FONT_SIZE.toFloat(), Resources.getSystem().displayMetrics)
    )
    actual constructor(family: String, weight: FontWeight, size: Int) : this(
        applyDimension(COMPLEX_UNIT_SP, size.toFloat(), Resources.getSystem().displayMetrics),
        Typeface.create(family, when(weight) {
            FontWeight.NORMAL -> Typeface.NORMAL
            FontWeight.BOLD -> Typeface.BOLD
            FontWeight.ITALIC -> Typeface.ITALIC
        })
    )

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
}