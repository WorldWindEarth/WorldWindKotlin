package earth.worldwind.render.image

/**
 * Image config indicates the in-memory representation for images displayed by WorldWind components. Images are
 * typically represented in the 32-bit RGBA_8888 configuration, the highest quality available. Components that do
 * not require an alpha channel and want to conserve memory may use the 16-bit RGBA_565 configuration.
 * Accepted values are [RGBA_8888] and [RGB_565].
 */
enum class ImageConfig {
    /**
     * Indicating 32-bit RGBA_8888 image configuration.
     */
    RGBA_8888,
    /**
     * Indicating 16-bit RGBA_565 image configuration.
     */
    RGB_565;
}