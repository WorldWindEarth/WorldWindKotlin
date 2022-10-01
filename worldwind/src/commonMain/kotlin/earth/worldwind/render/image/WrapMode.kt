package earth.worldwind.render.image

/**
 * Wrap mode indicates how WorldWind displays the contents of an image when attempting to draw a region outside of
 * the image bounds. Accepted values are [CLAMP] and [REPEAT].
 */
enum class WrapMode {
    /**
     * Indicating that the image's edge pixels should be displayed outside of the image bounds.
     */
    CLAMP,
    /**
     * Indicating that the image should display as a repeating pattern outside of the image bounds.
     */
    REPEAT;
}