@file:OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)

package earth.worldwind.render

import kotlinx.cinterop.useContents
import platform.Foundation.NSAttributedString
import platform.Foundation.create
import platform.UIKit.NSFontAttributeName
import platform.UIKit.UIFont
import platform.UIKit.size
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi

actual open class Font {
    var family: String = "Helvetica"
    var weight: FontWeight = FontWeight.NORMAL
    var size: Int = DEFAULT_FONT_SIZE

    actual constructor()

    actual constructor(family: String, weight: FontWeight, size: Int) {
        this.family = family
        this.weight = weight
        this.size = size
    }

    actual fun copy(font: Font) {
        family = font.family
        weight = font.weight
        size = font.size
    }

    actual fun measureText(text: String): Float {
        if (text.isEmpty()) return 0f
        val attrs = mapOf<Any?, Any>(NSFontAttributeName to toUIFont())
        val attributed = NSAttributedString.create(string = text, attributes = attrs)
        var result = 0f
        attributed.size().useContents { result = width.toFloat() }
        return result
    }

    actual fun measureChars(text: String): FloatArray {
        if (text.isEmpty()) return FloatArray(0)
        val attrs = mapOf<Any?, Any>(NSFontAttributeName to toUIFont())
        return FloatArray(text.length) { i ->
            val s = NSAttributedString.create(string = text[i].toString(), attributes = attrs)
            var w = 0f
            s.size().useContents { w = width.toFloat() }
            w
        }
    }

    private fun toUIFont(): UIFont {
        val sz = size.toDouble()
        return when (weight) {
            FontWeight.BOLD -> UIFont.boldSystemFontOfSize(sz)
            FontWeight.ITALIC -> UIFont.italicSystemFontOfSize(sz)
            else -> UIFont.fontWithName(family, sz) ?: UIFont.systemFontOfSize(sz)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Font) return false
        return family == other.family && weight == other.weight && size == other.size
    }

    override fun hashCode(): Int {
        var result = family.hashCode()
        result = 31 * result + weight.hashCode()
        result = 31 * result + size
        return result
    }

    override fun toString() = "$family $weight ${size}px"
}
