package earth.worldwind.render

import android.content.res.Resources
import android.graphics.Typeface
import android.util.TypedValue

actual open class Font(var typeface: Typeface?, var size: Float) {
    actual constructor() : this(null, 24f)
    actual constructor(family: String, weight: FontWeight, size: Int) : this(
        Typeface.create(family, when(weight) {
            FontWeight.NORMAL -> Typeface.NORMAL
            FontWeight.BOLD -> Typeface.BOLD
            FontWeight.ITALIC -> Typeface.ITALIC
        }), TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size.toFloat(), Resources.getSystem().displayMetrics)
    )

    actual fun copy(font: Font) {
        typeface = font.typeface
        size = font.size
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Font
        if (typeface != other.typeface) return false
        if (size != other.size) return false
        return true
    }

    override fun hashCode(): Int {
        var result = typeface?.hashCode() ?: 0
        result = 31 * result + size.hashCode()
        return result
    }

    override fun toString(): String {
        return "Font(typeface=$typeface, size=$size)"
    }
}