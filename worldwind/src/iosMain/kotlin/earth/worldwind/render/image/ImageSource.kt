@file:OptIn(ExperimentalForeignApi::class)

package earth.worldwind.render.image

import dev.icerock.moko.resources.ImageResource
import earth.worldwind.util.AbstractSource
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.math.powerOfTwoCeiling
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextAddPath
import platform.CoreGraphics.CGContextSetLineWidth
import platform.CoreGraphics.CGContextSetRGBStrokeColor
import platform.CoreGraphics.CGContextStrokePath
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGRectZero
import platform.CoreGraphics.CGSize
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreFoundation.CFRelease
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetCurrentContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.CoreGraphics.CGPathAddLineToPoint
import platform.CoreGraphics.CGPathCreateMutable
import platform.CoreGraphics.CGPathMoveToPoint
import platform.CoreGraphics.CGSizeMake

/**
 * iOS [ImageSource]. Mirrors the JS / JVM shape: an [AbstractSource] holding either an
 * [ImageResource], a URL string, an arbitrary `Any`, or an [ImageFactory] subclass.
 *
 * The platform extension on [ImageFactory] is `suspend fun createImage(): UIImage?` —
 * apps subclass this to render shapes lazily (e.g. line stipple, custom procedural icons).
 */
actual open class ImageSource protected constructor(source: Any) : AbstractSource(source) {

    /**
     * Factory for delegating construction of images. iOS variant returns `UIImage?` so the
     * existing [ImageDecoder.decodeUIImage] pipeline can convert it into RGBA bytes for GL
     * upload. Apps that need raw bytes should still wrap into a UIImage (cheap on iOS).
     */
    actual interface ImageFactory {
        suspend fun createImage(): UIImage?
    }

    actual companion object {
        protected val lineStippleFactories = mutableMapOf<Long, ImageFactory>()

        actual fun fromResource(imageResource: ImageResource) = ImageSource(imageResource)

        actual fun fromUrlString(urlString: String) = try {
            ImageSource(urlString)
        } catch (e: Exception) {
            logMessage(ERROR, "ImageSource", "fromUrlString", "invalidUrlString", e)
            throw e
        }

        actual fun fromImageFactory(factory: ImageFactory) = ImageSource(factory)

        /**
         * Constructs a stipple-pattern image source. Same algorithm as JS: a 1-row UIImage
         * with `factor`-pixel-wide white segments drawn whenever the corresponding bit in
         * `pattern` is set. Width is rounded up to the next power-of-two so GLES 2 / WebGL 1
         * `GL_REPEAT` works on the texture.
         */
        actual fun fromLineStipple(factor: Int, pattern: Short): ImageSource {
            // Match the JS encoding (`factor.shl(32)`); a 16-bit shift would alias on
            // factor >= 65536.
            val key = factor.toLong() shl 32 or (pattern.toInt() and 0xFFFF).toLong()
            val factory = lineStippleFactories.getOrPut(key) { LineStippleImageFactory(factor, pattern) }
            return ImageSource(factory)
        }

        actual fun fromUnrecognized(source: Any) = when (source) {
            is String -> fromUrlString(source)
            is ImageResource -> fromResource(source)
            is ImageFactory -> fromImageFactory(source)
            is UIImage -> ImageSource(source)
            else -> ImageSource(source)
        }
    }

    val isResource get() = source is ImageResource
    val isUrl get() = source is String
    val isImageFactory get() = source is ImageFactory
    val isUIImage get() = source is UIImage

    fun asResource() = source as ImageResource
    fun asUrl() = source as String
    fun asImageFactory() = source as ImageFactory
    fun asUIImage() = source as UIImage

    override fun toString() = when (source) {
        is ImageResource -> "Resource: $source"
        is String -> "URL: $source"
        is ImageFactory -> "ImageFactory: $source"
        is UIImage -> "UIImage: $source"
        else -> super.toString()
    }
}

/**
 * Renders a 16-bit stipple pattern as a 1-pixel-tall UIImage. Each bit of [pattern] turns
 * on a [factor]-pixel-wide segment; cleared bits leave a transparent gap. Width is rounded
 * up to the next power-of-two for `GL_REPEAT` compatibility on GLES2 / WebGL1.
 */
private open class LineStippleImageFactory(
    protected val factor: Int,
    protected val pattern: Short,
) : ImageSource.ImageFactory {
    override suspend fun createImage(): UIImage? {
        // WebGL1 / GLES2 require POT for GL_REPEAT; same constraint applies here for parity.
        val widthPow2 = if (factor <= 0) 16 else powerOfTwoCeiling(factor * 16)
        val height = 1
        val sizeStruct: CValue<CGSize> = CGSizeMake(
            widthPow2.toDouble(), height.toDouble()
        )

        // 1-pixel-tall image; opaque=false so cleared segments are transparent. Scale 1.0:
        // we don't want UIKit to multiply by mainScreen.scale (this is a procedurally
        // generated texture, not a per-screen UI asset).
        UIGraphicsBeginImageContextWithOptions(sizeStruct, opaque = false, scale = 1.0)
        val ctx = UIGraphicsGetCurrentContext() ?: run {
            UIGraphicsEndImageContext()
            return null
        }

        // White stroke; the engine multiplies the line color in the fragment shader.
        CGContextSetRGBStrokeColor(ctx, 1.0, 1.0, 1.0, 1.0)
        CGContextSetLineWidth(ctx, 1.0)

        // Build a single CGPath of all segments to fill, then stroke once.
        val path = CGPathCreateMutable()
        if (factor <= 0) {
            // Solid line — single segment spanning the full width.
            CGPathMoveToPoint(path, null, 0.0, 0.5)
            CGPathAddLineToPoint(path, null, widthPow2.toDouble(), 0.5)
        } else {
            var offset = 0
            for (bi in 0..15) {
                val bit = pattern.toInt() and (1 shl bi)
                if (bit != 0) {
                    CGPathMoveToPoint(path, null, offset.toDouble(), 0.5)
                    CGPathAddLineToPoint(
                        path, null, (offset + factor).toDouble(), 0.5
                    )
                }
                offset += factor
            }
        }
        CGContextAddPath(ctx, path)
        CGContextStrokePath(ctx)
        CFRelease(path)

        val image = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return image
    }

    override fun toString() = "LineStippleFactory factor=$factor, pattern=" +
        (pattern.toInt() and 0xFFFF).toString(16).uppercase()
}
