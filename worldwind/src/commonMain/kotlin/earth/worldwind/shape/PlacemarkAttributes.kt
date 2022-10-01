package earth.worldwind.shape

import earth.worldwind.geom.Offset
import earth.worldwind.render.Color
import earth.worldwind.render.image.ImageSource
import kotlin.jvm.JvmStatic

/**
 * Holds attributes applied to [Placemark] shapes.
 * The defaults indicate a placemark displayed as a white 1x1 pixel square
 * centered on the placemark's geographic position.
 */
open class PlacemarkAttributes(
    /**
     * Returns the source of the placemark's image. If null, the placemark is drawn as a square whose width and height
     * are the value of this attribute object's [imageScale] property.
     */
    var imageSource: ImageSource?,
    /**
     * Returns the image color. When this attribute bundle has a valid image path the placemark's image is composed with
     * this image color to achieve the final placemark color. Otherwise the placemark is drawn in this color. The color
     * white, the default, causes the image to be drawn in its native colors.
     */
    imageColor: Color,
    /**
     * Returns the location within the placemark's image to align with the placemark's geographic position. The default
     * value centers the image at the geographic position.
     */
    imageOffset: Offset,
    /**
     * Returns the amount to scale the placemark's image. When this attribute bundle has a valid image path the scale is
     * applied to the image's dimensions. Otherwise the scale indicates the dimensions in pixels of a square drawn at
     * the placemark's geographic position. A scale of 0 causes the placemark to disappear; however, the placemark's
     * label, if any, is still drawn.
     */
    var imageScale: Double,
    /**
     * Returns the minimum amount to scale the placemark's image. When a [Placemark.isEyeDistanceScaling] is true,
     * this value controls the minimum size of the rendered placemark. A value of 0 allows the placemark to disappear.
     */
    var minimumImageScale: Double,
    /**
     * Returns whether to draw a placemark's label text.
     */
    var isDrawLabel: Boolean,
    /**
     * Returns whether to draw a line from the placemark's geographic position to the ground.
     */
    var isDrawLeader: Boolean,
    /**
     * Returns whether the placemark should be depth-tested against other objects in the scene. If true, the placemark
     * may be occluded by terrain and other objects in certain viewing situations. If false, the placemark will not be
     * occluded by terrain and other objects. If this value is true, the placemark's label, if any, has an independent
     * depth-test control.
     */
    var isDepthTest: Boolean,
    /**
     * Returns the attributes to apply to the placemark's label
     */
    labelAttributes: TextAttributes,
    /**
     * Returns the attributes to apply to the leader line if it's drawn
     * drawn.
     */
    leaderAttributes: ShapeAttributes
) {
    /**
     * Returns the image color. When this attribute bundle has a valid image path the placemark's image is composed with
     * this image color to achieve the final placemark color. Otherwise the placemark is drawn in this color. The color
     * white, the default, causes the image to be drawn in its native colors.
     */
    var imageColor = imageColor
        set(value) {
            field.copy(value)
        }
    /**
     * Returns the location within the placemark's image to align with the placemark's geographic position. The default
     * value centers the image at the geographic position.
     */
    var imageOffset = imageOffset
        set(value) {
            field.copy(value)
        }
    /**
     * Returns the attributes to apply to the placemark's label
     */
    var labelAttributes = labelAttributes
        set(value) {
            field.copy(value)
        }
    /**
     * Returns the attributes to apply to the leader line if it's drawn
     * drawn.
     */
    var leaderAttributes = leaderAttributes
        set(value) {
            field.copy(value)
        }

    /**
     * Constructs a placemark attributes bundle. The defaults indicate a placemark displayed as a white 1x1 pixel square
     * centered on the placemark's geographic position.
     */
    constructor(): this(
        imageSource = null,
        imageColor = Color(1f, 1f, 1f, 1f), // white
        imageOffset = Offset.center(),
        imageScale = 1.0,
        minimumImageScale = 0.0,
        isDrawLabel = true,
        isDrawLeader = false,
        isDepthTest = true,
        labelAttributes = TextAttributes(),
        leaderAttributes = ShapeAttributes()
    )

    /**
     * Constructs a placemark attribute bundle from the specified attributes. Performs a deep copy of the color, offset,
     * label attributes and leader-line attributes.
     *
     * @param attributes The attributes to be copied.
     */
    constructor(attributes: PlacemarkAttributes): this(
        attributes.imageSource,
        Color(attributes.imageColor),
        Offset(attributes.imageOffset),
        attributes.imageScale,
        attributes.minimumImageScale,
        attributes.isDrawLabel,
        attributes.isDrawLeader,
        attributes.isDepthTest,
        TextAttributes(attributes.labelAttributes),
        ShapeAttributes(attributes.leaderAttributes)
    )

    fun copy(attributes: PlacemarkAttributes) = apply {
        imageSource = attributes.imageSource
        imageColor.copy(attributes.imageColor)
        imageOffset.copy(attributes.imageOffset)
        imageScale = attributes.imageScale
        minimumImageScale = attributes.minimumImageScale
        isDrawLabel = attributes.isDrawLabel
        isDrawLeader = attributes.isDrawLeader
        isDepthTest = attributes.isDepthTest
        labelAttributes.copy(attributes.labelAttributes)
        leaderAttributes.copy(attributes.leaderAttributes)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlacemarkAttributes) return false
        if (imageSource != other.imageSource) return false
        if (imageColor != other.imageColor) return false
        if (imageOffset != other.imageOffset) return false
        if (imageScale != other.imageScale) return false
        if (minimumImageScale != other.minimumImageScale) return false
        if (isDrawLabel != other.isDrawLabel) return false
        if (isDrawLeader != other.isDrawLeader) return false
        if (isDepthTest != other.isDepthTest) return false
        if (labelAttributes != other.labelAttributes) return false
        if (leaderAttributes != other.leaderAttributes) return false

        return true
    }

    override fun hashCode(): Int {
        var result = imageSource?.hashCode() ?: 0
        result = 31 * result + imageColor.hashCode()
        result = 31 * result + imageOffset.hashCode()
        result = 31 * result + imageScale.hashCode()
        result = 31 * result + minimumImageScale.hashCode()
        result = 31 * result + isDrawLabel.hashCode()
        result = 31 * result + isDrawLeader.hashCode()
        result = 31 * result + isDepthTest.hashCode()
        result = 31 * result + labelAttributes.hashCode()
        result = 31 * result + leaderAttributes.hashCode()
        return result
    }

    companion object {
        @JvmStatic
        fun createWithImage(imageSource: ImageSource) = PlacemarkAttributes().apply { this.imageSource = imageSource }

        @JvmStatic
        fun createWithImageAndLeader(imageSource: ImageSource) = PlacemarkAttributes().apply {
            this.imageSource = imageSource
            isDrawLeader = true
        }
    }
}