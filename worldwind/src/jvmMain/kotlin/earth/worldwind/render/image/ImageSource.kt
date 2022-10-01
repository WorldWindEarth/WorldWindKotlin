package earth.worldwind.render.image

import dev.icerock.moko.resources.ImageResource
import earth.worldwind.util.AbstractSource
import earth.worldwind.util.DownloadPostprocessor
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import java.awt.image.BufferedImage
import java.io.File
import java.net.MalformedURLException
import java.net.URL

/**
 * Provides a mechanism for specifying images from a variety of sources. ImageSource retains the image source on behalf
 * of the caller, making this information available to WorldWind components that load images on the caller's behalf.
 * <br>
 * ImageSource supports following source types:
 * - Uniform Resource Locator [URL]
 * - Local image [File]
 * - [BufferedImage] object
 * - Multi-platform resource identifier
 * <br>
 * ImageSource instances are intended to be used as a key into a cache or other data structure that enables sharing of
 * loaded images. BufferedImages are compared by reference. File paths and URLs with the same string representation considered equals.
 */
actual open class ImageSource protected constructor(source: Any): AbstractSource<BufferedImage>(source) {
    actual companion object {
        /**
         * Constructs an image source with a multi-platform resource identifier.
         *
         * @param imageResource the multi-platform resource identifier
         *
         * @return the new image source
         */
        @JvmStatic
        actual fun fromResource(imageResource: ImageResource) = ImageSource(imageResource)

        /**
         * Constructs an image source with a [BufferedImage].
         *
         * @param image the [BufferedImage] to use as an image source
         *
         * @return the new image source
         */
        @JvmStatic
        fun fromImage(image: BufferedImage) = ImageSource(image)

        /**
         * Constructs an image source with a [File].
         *
         * @param file the source [File] to use as an image source
         *
         * @return the new image source
         */
        @JvmStatic
        fun fromFile(file: File): ImageSource {
            require(file.isFile) {
                logMessage(ERROR, "ImageSource", "fromFile", "invalidFile")
            }
            return ImageSource(file)
        }

        /**
         * Constructs an image source with a file path.
         *
         * @param filePath complete path name to the file
         *
         * @return the new image source
         */
        @JvmStatic
        fun fromFilePath(filePath: String) = fromFile(File(filePath))

        /**
         * Constructs an image source with an [URL].
         *
         * @param url Uniform Resource Locator
         * @param postprocessor implementation of image post-processing routine
         *
         * @return the new image source
         */
        @JvmStatic @JvmOverloads
        fun fromUrl(url: URL, postprocessor: DownloadPostprocessor<BufferedImage>? = null) =
            ImageSource(url).apply { this.postprocessor = postprocessor }

        /**
         * Constructs an image source with a URL string.
         *
         * @param urlString complete URL string
         * @param postprocessor implementation of image post-processing routine
         *
         * @return the new image source
         */
        @JvmStatic @JvmOverloads @Suppress("UNCHECKED_CAST")
        actual fun fromUrlString(urlString: String, postprocessor: DownloadPostprocessor<*>?) = try {
            fromUrl(URL(urlString), postprocessor as DownloadPostprocessor<BufferedImage>?)
        } catch (e: MalformedURLException) {
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
        @JvmStatic
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
        @JvmStatic
        actual fun fromUnrecognized(source: Any) = when (source) {
            is ImageResource -> fromResource(source)
            is BufferedImage -> fromImage(source)
            is File -> fromFile(source)
            is URL -> fromUrl(source)
            else -> ImageSource(source)
        }
    }

    /**
     * Indicates whether this image source is a multi-platform resource.
     */
    val isResource get() = source is ImageResource
    /**
     * Indicates whether this image source is a [BufferedImage].
     */
    val isImage get() = source is BufferedImage
    /**
     * Indicates whether this image source is a [File].
     */
    val isFile get() = source is File
    /**
     * Indicates whether this image source is an [URL].
     */
    val isUrl get() = source is URL

    /**
     * @return the source multi-platform resource identifier. Call isResource to determine whether the source is an
     * Multi-platform resource.
     */
    fun asResource() = source as ImageResource
    /**
     * @return the source [BufferedImage]. Call [isImage] to determine whether the source is a [BufferedImage].
     */
    fun asImage() = source as BufferedImage

    /**
     * @return the source [File]. Call [isFile] to determine whether the source is a [File].
     */
    fun asFile() = source as File

    /**
     * @return the source [URL]. Call [isUrl] to determine whether the source is an [URL].
     */
    fun asUrl() = source as URL

    override fun toString() = when (source) {
        is ImageResource -> "Resource: $source"
        is BufferedImage -> "BufferedImage: $source"
        is File -> "File: $source"
        is URL -> "URL: $source"
        else -> super.toString()
    }
}