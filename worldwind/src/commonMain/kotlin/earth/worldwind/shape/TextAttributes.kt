package earth.worldwind.shape

import earth.worldwind.geom.Offset
import earth.worldwind.render.Color
import earth.worldwind.render.Font

/**
 * Holds attributes applied to text shapes and [Placemark] labels.
 */
open class TextAttributes protected constructor(
    font: Font,
    textColor: Color,
    textOffset: Offset,
    outlineColor: Color,
    var outlineWidth: Float,
    var isOutlineEnabled: Boolean,
    var isDepthTest: Boolean,
    var scale: Double
) {
    var font = font
        set(value) {
            field.copy(value)
        }
    var textColor = textColor
        set(value) {
            field.copy(value)
        }
    var textOffset = textOffset
        set(value) {
            field.copy(value)
        }
    var outlineColor = outlineColor
        set(value) {
            field.copy(value)
        }

    constructor(): this(
        font = Font(),
        textColor = Color(1f, 1f, 1f, 1f),
        textOffset = Offset.bottomCenter(),
        outlineColor = Color(0f, 0f, 0f, 1f),
        outlineWidth = 3f,
        isOutlineEnabled = true,
        isDepthTest = true,
        scale = 1.0
    )

    constructor(attributes: TextAttributes): this(
        attributes.font,
        Color(attributes.textColor),
        Offset(attributes.textOffset),
        Color(attributes.outlineColor),
        attributes.outlineWidth,
        attributes.isOutlineEnabled,
        attributes.isDepthTest,
        attributes.scale
    )

    fun copy(attributes: TextAttributes) = apply {
        font.copy(attributes.font)
        textColor.copy(attributes.textColor)
        textOffset.copy(attributes.textOffset)
        outlineColor.copy(attributes.outlineColor)
        outlineWidth = attributes.outlineWidth
        isOutlineEnabled = attributes.isOutlineEnabled
        isDepthTest = attributes.isDepthTest
        scale = attributes.scale
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TextAttributes) return false
        if (font != other.font) return false
        if (textColor != other.textColor) return false
        if (textOffset != other.textOffset) return false
        if (outlineColor != other.outlineColor) return false
        if (outlineWidth != other.outlineWidth) return false
        if (isOutlineEnabled != other.isOutlineEnabled) return false
        if (isDepthTest != other.isDepthTest) return false
        if (scale != other.scale) return false
        return true
    }

    override fun hashCode(): Int {
        var result = font.hashCode()
        result = 31 * result + textColor.hashCode()
        result = 31 * result + textOffset.hashCode()
        result = 31 * result + outlineColor.hashCode()
        result = 31 * result + outlineWidth.hashCode()
        result = 31 * result + isOutlineEnabled.hashCode()
        result = 31 * result + isDepthTest.hashCode()
        result = 31 * result + scale.hashCode()
        return result
    }
}