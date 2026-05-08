package earth.worldwind.render

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
