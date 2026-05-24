package earth.worldwind.render

const val DEFAULT_FONT_SIZE = 14

expect open class Font {
    constructor()
    constructor(family: String, weight: FontWeight, size: Int)
    fun copy(font: Font)

    /**
     * Advance width of [text] in raw pixels using this font's typeface + size. Includes
     * kerning where the platform supports it. Returns 0 for empty input. Used by per-glyph
     * placement (e.g. baseline-following text along a curve).
     */
    fun measureText(text: String): Float

    /**
     * Per-character advance widths for [text] in raw pixels, parallel to the input chars.
     * Sum of the array roughly equals [measureText] (small difference allowed for kerning
     * adjustments between adjacent glyphs).
     */
    fun measureChars(text: String): FloatArray
}