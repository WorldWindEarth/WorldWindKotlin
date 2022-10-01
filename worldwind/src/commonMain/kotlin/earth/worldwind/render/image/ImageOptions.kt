package earth.worldwind.render.image

import kotlin.jvm.JvmOverloads

/**
 * Options for images displayed by WorldWind components.
 */
open class ImageOptions @JvmOverloads constructor(
    /**
     * Indicates the in-memory configuration for images displayed by WorldWind components. By default, images are
     * represented in the 32-bit RGBA_8888 configuration, the highest quality available. Components that do not require
     * an alpha channel and want to conserve memory may use the 16-bit RGBA_565 configuration. Accepted values are
     * [ImageConfig.RGBA_8888] and [ImageConfig.RGB_565].
     */
    var imageConfig: ImageConfig = ImageConfig.RGBA_8888
) {
    /**
     * Indicates the image sampling algorithm used by WorldWind to display images that appear larger or smaller on
     * screen than their native resolution. Accepted values are [ResamplingMode.BILINEAR] and [ResamplingMode.NEAREST_NEIGHBOR].
     */
    var resamplingMode = ResamplingMode.BILINEAR
    /**
     * Indicates how WorldWind displays the contents of an image when attempting to draw a region outside of the image
     * bounds. Accepted values are [WrapMode.CLAMP] and [WrapMode.REPEAT].
     */
    var wrapMode = WrapMode.CLAMP
    /**
     * Initial width for image that has no dimensions (e.g. SVG image)
     */
    var initialWidth = 0
    /**
     * Initial height for image that has no dimensions (e.g. SVG image)
     */
    var initialHeight = 0
}