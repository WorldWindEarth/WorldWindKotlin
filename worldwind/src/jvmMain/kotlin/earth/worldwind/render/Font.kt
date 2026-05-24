package earth.worldwind.render

actual open class Font(var font: java.awt.Font) {
    actual constructor() : this("Arial", FontWeight.BOLD, DEFAULT_FONT_SIZE)
    actual constructor(family: String, weight: FontWeight, size: Int) :
        this(java.awt.Font.decode("${mapCssFamily(family)}-$weight-$size"))

    actual fun measureText(text: String): Float {
        if (text.isEmpty()) return 0f
        val fm = sharedFontMetrics(font)
        return fm.stringWidth(text).toFloat()
    }

    actual fun measureChars(text: String): FloatArray {
        if (text.isEmpty()) return FloatArray(0)
        val fm = sharedFontMetrics(font)
        return FloatArray(text.length) { i -> fm.charWidth(text[i]).toFloat() }
    }

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

        // A throwaway 1x1 ARGB image gives us a Graphics2D + FontMetrics without paying
        // the cost of allocating one per measurement call. Per-font cache keyed by the
        // AWT Font reference (Font is value-equal but caching by identity is safe — the
        // engine reuses Font instances for the same (family, weight, size) tuple).
        private val probe = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        private val metricsCache = HashMap<java.awt.Font, java.awt.FontMetrics>()

        @Synchronized
        private fun sharedFontMetrics(font: java.awt.Font): java.awt.FontMetrics =
            metricsCache.getOrPut(font) { probe.createGraphics().also { it.font = font }.fontMetrics }
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