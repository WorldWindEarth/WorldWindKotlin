package earth.worldwind.render

expect open class Font {
    constructor()
    constructor(family: String, weight: FontWeight, size: Int)
    fun copy(font: Font)
}