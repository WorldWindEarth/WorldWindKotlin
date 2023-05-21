package earth.worldwind.render.image

import dev.icerock.moko.resources.ImageResource
import earth.worldwind.util.AbstractSource
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.ResourcePostprocessor
import org.w3c.dom.Image
import org.w3c.dom.url.URL

/**
 * Provides a mechanism for specifying images from a variety of sources. ImageSource retains the image source on behalf
 * of the caller, making this information available to WorldWind components that load images on the caller's behalf.
 * <br>
 * ImageSource supports following source types:
 * - Uniform Resource Locator [URL]
 * - [Image] object
 * - WorldWind [ImageSource.ImageFactory]
 * - Multi-platform resource identifier
 * <br>
 * ImageSource instances are intended to be used as a key into a cache or other data structure that enables sharing of
 * loaded images. Images are compared by reference. URLs with the same string representation considered equals.
 */
actual open class ImageSource protected constructor(source: Any): AbstractSource(source) {
    actual companion object {
        /**
         * Constructs an image source with a multi-platform resource identifier.
         *
         * @param imageResource the multi-platform resource identifier
         *
         * @return the new image source
         */
        actual fun fromResource(imageResource: ImageResource) = ImageSource(imageResource)

        /**
         * Constructs an image source with an [Image]. The image's dimensions should not be greater than 2048 x 2048.
         *
         * @param image the [Image] to use as an image source
         *
         * @return the new image source
         */
        fun fromImage(image: Image) = ImageSource(image)

        /**
         * Constructs an image source with a [ImageFactory]. WorldWind shapes configured with an image factory image source
         * construct their images lazily, typically when the shape becomes visible on screen.
         *
         * @param factory the [ImageFactory] to use as an image source
         *
         * @return the new image source
         */
        fun fromImageFactory(factory: ImageFactory) = ImageSource(factory)

        /**
         * Constructs an image source with an [URL]. The image's dimensions should be no greater than 2048 x 2048.
         *
         * @param url Uniform Resource Locator
         *
         * @return the new image source
         */
        fun fromUrl(url: URL) = ImageSource(url.href)

        /**
         * Constructs an image source with a URL string. The image's dimensions should not be greater than 2048 x 2048.
         *
         * @param urlString complete URL string
         *
         * @return the new image source
         */
        actual fun fromUrlString(urlString: String) = try {
            fromUrl(URL(urlString))
        } catch (e: Exception) {
            logMessage(ERROR, "ImageSource", "fromUrlString", "invalidUrlString", e)
            throw e
        }

        /**
         * Constructs an image source with a line stipple pattern. The result is a one-dimensional image with pixels
         * representing the specified stipple factor and stipple pattern. Line stipple images can be used for displaying
         * dashed shape outlines. See [earth.worldwind.shape.ShapeAttributes.outlineImageSource].
         *
         * @param factor  specifies the number of times each bit in the pattern is repeated before the next bit is used. For
         * example, if the factor is 3, each bit is repeated three times before using the next bit. The
         * specified factor must be either 0 or an integer greater than 0. A factor of 0 indicates no
         * stippling.
         * @param pattern specifies a number whose lower 16 bits define a pattern of which pixels in the image are white and
         * which are transparent. Each bit corresponds to a pixel, and the pattern repeats after every n*16
         * pixels, where n is the factor. For example, if the factor is 3, each bit in the pattern is
         * repeated three times before using the next bit.
         *
         * @return the new image source
         */
        actual fun fromLineStipple(factor: Int, pattern: Short): ImageSource {
            TODO("Not yet implemented")
        }

        /**
         * Constructs an image source with a generic [Any] instance. The source may be any non-null object. This is
         * equivalent to calling one of ImageSource's type-specific factory methods when the source is a recognized type.
         *
         * @param source the generic source
         *
         * @return the new image source
         */
        actual fun fromUnrecognized(source: Any) = when (source) {
            is ImageResource -> fromResource(source)
            is Image -> fromImage(source)
            is ImageFactory -> fromImageFactory(source)
            is URL -> fromUrl(source)
            is String -> fromUrlString(source)
            else -> ImageSource(source)
        }
    }

    /**
     * Image post-processing routine.
     */
    @Suppress("UNCHECKED_CAST")
    val imagePostprocessor get() = postprocessor as ResourcePostprocessor<Image>?
    /**
     * Indicates whether this image source is a multi-platform resource.
     */
    val isResource get() = source is ImageResource
    /**
     * Indicates whether this image source is a [Image].
     */
    val isImage get() = source is Image
    /**
     * Indicates whether this image source is an image factory.
     */
    val isImageFactory get() = source is ImageFactory
    /**
     * Indicates whether this image source is an [URL] string.
     */
    val isUrl get() = source is String

    /**
     * @return the source multi-platform resource identifier. Call isResource to determine whether the source is a
     * Multi-platform resource.
     */
    fun asResource() = source as ImageResource

    /**
     * @return the source [Image]. Call [isImage] to determine whether the source is a [Image].
     */
    fun asImage() = source as Image

    /**
     * @return the source [ImageFactory]. Call isImageFactory to determine whether the source is an image
     * factory.
     */
    fun asImageFactory() = source as ImageFactory

    /**
     * @return the source [URL]. Call [isUrl] to determine whether the source is an [URL] string.
     */
    fun asUrl() = source as String

    override fun toString() = when(source) {
        is ImageResource -> "Resource: $source"
        is Image -> "Image: $source"
        is ImageFactory -> "ImageFactory: $source"
        is String -> "URL: $source"
        else -> super.toString()
    }

    /**
     * Factory for delegating construction of images. WorldWind shapes configured with a ImageFactory construct
     * their images lazily, typically when the shape becomes visible on screen.
     */
    interface ImageFactory {
        /**
         * Image factory runs asynchronously by default, but this behavior can be changed by overriding current attribute.
         */
        val isRunBlocking: Boolean get() = false

        /**
         * Returns the image associated with this factory. This method may be called more than once and may be called
         * from a non-UI thread. Each invocation must return an image with equivalent content, dimensions and
         * configuration. Any side effects applied to the WorldWind scene by the factory must be executed on the main
         * thread.
         *
         * @return the image associated with this factory
         */
        fun createImage(): Image?
    }
}