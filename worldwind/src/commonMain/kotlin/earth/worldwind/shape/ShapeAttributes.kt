package earth.worldwind.shape

import earth.worldwind.render.Color
import earth.worldwind.render.image.ImageSource

/**
 * Holds attributes applied to geographic shapes.
 */
open class ShapeAttributes(
    /**
     * Indicates whether shape interiors are enabled.
     */
    var isDrawInterior: Boolean,
    /**
     * Indicates whether shape outlines are enabled.
     */
    var isDrawOutline: Boolean,
    /**
     * Indicates whether shape vertical outlines are enabled. Not all shapes display vertical outlines. Those that do
     * not ignore this property. When enabled, those that do display vertical lines extending from the shape's specified
     * positions to the ground.
     */
    var isDrawVerticals: Boolean,
    /**
     * Indicates whether shape depth-testing is enabled. When true, shapes may be occluded by terrain and other shapes
     * in certain viewing situations. When false, shapes will not be occluded by terrain and other shapes.
     */
    var isDepthTest: Boolean,
    /**
     * Indicates whether depth write enabled.
     */
    var isDepthWrite: Boolean,
    /**
     * Sets whether shape lighting is enabled. When true, the appearance of a shape's color and image source may be
     * modified by shading applied from a global light source.
     */
    var isLightingEnabled: Boolean,
    /**
     * Indicates the color and opacity of shape interiors.
     */
    interiorColor: Color,
    /**
     * Indicates the color and opacity of shape outlines.
     */
    outlineColor: Color,
    /**
     * Indicates the width of shape outlines.
     */
    var outlineWidth: Float,
    /**
     * Indicates the image source applied to shape interiors. When null, shape interiors are displayed in the interior
     * color. When non-null, image pixels appear in shape interiors, with each image pixel multiplied by the interior
     * RGBA color. Use a white interior color to display unmodified image pixels.
     * <br>
     * By default, interior image sources are displayed as a repeating pattern across shape interiors. The pattern
     * matches image pixels to screen pixels, such that the image appears to repeat in screen coordinates.
     */
    var interiorImageSource: ImageSource?,
    /**
     * Indicates the image source applied to shape outlines.
     */
    var outlineImageSource: ImageSource?,
    /**
     * Allows to pick interior elements of shape
     */
    var isPickInterior: Boolean,
    /**
     * Allows to pick outline elements of shape
     */
    var isPickOutline: Boolean,
) {
    /**
     * Indicates the color and opacity of shape interiors.
     */
    var interiorColor = interiorColor
        set(value) {
            field.copy(value)
        }
    /**
     * Indicates the color and opacity of shape outlines.
     */
    var outlineColor = outlineColor
        set(value) {
            field.copy(value)
        }

    constructor(): this(
        isDrawInterior = true,
        isDrawOutline = true,
        isDrawVerticals = false,
        isDepthTest = true,
        isDepthWrite = true,
        isLightingEnabled = false,
        interiorColor = Color(1f, 1f, 1f, 1f), // white
        outlineColor = Color(1f, 0f, 0f, 1f), // red
        outlineWidth = 1.0f,
        interiorImageSource = null,
        outlineImageSource = null,
        isPickInterior = true,
        isPickOutline = true,
    )

    constructor(attributes: ShapeAttributes): this(
        attributes.isDrawInterior,
        attributes.isDrawOutline,
        attributes.isDrawVerticals,
        attributes.isDepthTest,
        attributes.isDepthWrite,
        attributes.isLightingEnabled,
        Color(attributes.interiorColor),
        Color(attributes.outlineColor),
        attributes.outlineWidth,
        attributes.interiorImageSource,
        attributes.outlineImageSource,
        attributes.isPickInterior,
        attributes.isPickOutline,
    )

    fun copy(attributes: ShapeAttributes) = apply {
        isDrawInterior = attributes.isDrawInterior
        isDrawOutline = attributes.isDrawOutline
        isDrawVerticals = attributes.isDrawVerticals
        isDepthTest = attributes.isDepthTest
        isDepthWrite = attributes.isDepthWrite
        isLightingEnabled = attributes.isLightingEnabled
        interiorColor.copy(attributes.interiorColor)
        outlineColor.copy(attributes.outlineColor)
        outlineWidth = attributes.outlineWidth
        interiorImageSource = attributes.interiorImageSource
        outlineImageSource = attributes.outlineImageSource
        isPickInterior = attributes.isPickInterior
        isPickOutline = attributes.isPickOutline
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShapeAttributes) return false
        if (isDrawInterior != other.isDrawInterior) return false
        if (isDrawOutline != other.isDrawOutline) return false
        if (isDrawVerticals != other.isDrawVerticals) return false
        if (isDepthTest != other.isDepthTest) return false
        if (isDepthWrite != other.isDepthWrite) return false
        if (isLightingEnabled != other.isLightingEnabled) return false
        if (interiorColor != other.interiorColor) return false
        if (outlineColor != other.outlineColor) return false
        if (outlineWidth != other.outlineWidth) return false
        if (interiorImageSource != other.interiorImageSource) return false
        if (outlineImageSource != other.outlineImageSource) return false
        if (isPickInterior != other.isPickInterior) return false
        if (isPickOutline != other.isPickOutline) return false

        return true
    }

    override fun hashCode(): Int {
        var result = isDrawInterior.hashCode()
        result = 31 * result + isDrawOutline.hashCode()
        result = 31 * result + isDrawVerticals.hashCode()
        result = 31 * result + isDepthTest.hashCode()
        result = 31 * result + isDepthWrite.hashCode()
        result = 31 * result + isLightingEnabled.hashCode()
        result = 31 * result + interiorColor.hashCode()
        result = 31 * result + outlineColor.hashCode()
        result = 31 * result + outlineWidth.hashCode()
        result = 31 * result + (interiorImageSource?.hashCode() ?: 0)
        result = 31 * result + (outlineImageSource?.hashCode() ?: 0)
        result = 31 * result + isPickInterior.hashCode()
        result = 31 * result + isPickOutline.hashCode()
        return result
    }
}