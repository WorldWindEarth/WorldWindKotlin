package earth.worldwind.render

actual open class Font(var font: java.awt.Font) {
    actual constructor() : this("Arial", FontWeight.BOLD, DEFAULT_FONT_SIZE)
    actual constructor(family: String, weight: FontWeight, size: Int) :
        this(java.awt.Font.decode("${mapCssFamily(family)}-$weight-$size"))

    companion object {
        // Map common CSS-style logical font names (used by Android Typeface.create) to the
        // names Java AWT's Font.decode recognises. Unknown names pass through; AWT will fall
        // back to its own default if the family doesn't resolve.
        private fun mapCssFamily(family: String): String = when (family.lowercase()) {
            "sans-serif" -> "SansSerif"
            "serif" -> "Serif"
            "monospace" -> "Monospaced"
            else -> family
        }
    }

    actual fun copy(font: Font) { this.font = font.font }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Font
        return font == other.font
    }

    override fun hashCode() = font.hashCode()

    override fun toString() = font.toString()
}