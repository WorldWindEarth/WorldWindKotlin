package earth.worldwind.render

actual open class Font(var font: java.awt.Font) {
    actual constructor() : this("Arial", FontWeight.BOLD, 14)
    actual constructor(family: String, weight: FontWeight, size: Int) : this(java.awt.Font.decode("$family-$weight-$size"))

    actual fun copy(font: Font) { this.font = font.font }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Font
        if (font != other.font) return false
        return true
    }

    override fun hashCode() = font.hashCode()

    override fun toString() = font.toString()
}