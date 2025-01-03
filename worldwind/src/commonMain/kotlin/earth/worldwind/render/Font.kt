package earth.worldwind.render

const val DEFAULT_FONT_SIZE = 14

expect open class Font {
    constructor()
    constructor(family: String, weight: FontWeight, size: Int)
    fun copy(font: Font)
}