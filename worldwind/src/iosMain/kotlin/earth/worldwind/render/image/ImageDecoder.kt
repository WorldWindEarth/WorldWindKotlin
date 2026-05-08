@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package earth.worldwind.render.image

import dev.icerock.moko.resources.ImageResource
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.log
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.UIKit.UIImage

/**
 * Tightly packed RGBA8888 image carrier — what flows from [ImageDecoder] into
 * [ImageTexture] for GL upload. The byte layout is `RGBARGBA...` with premultiplied alpha,
 * matching what `glTexImage2D(GL_RGBA, GL_UNSIGNED_BYTE, ...)` expects.
 */
class ImageData(val width: Int, val height: Int, val rgba: ByteArray)

/**
 * iOS image decoder. Mirrors the JVM/Android decoder shape (an HTTP client + per-source
 * dispatch), but uses `ImageIO.framework` via UIImage as the decode backend. Output is
 * always RGBA8888 premultiplied — the simplest format to upload through the Kgl
 * `texImage2D(ByteArray)` overload without needing platform-specific GL extensions.
 */
open class ImageDecoder {
    protected val httpClient = DefaultHttpClient()

    open fun close() { httpClient.close() }

    open suspend fun decodeImage(imageSource: ImageSource, imageOptions: ImageOptions?): ImageData? =
        withContext(Dispatchers.Default) {
            val data: ImageData? = when {
                imageSource.isResource -> decodeResource(imageSource.asResource())
                imageSource.isUrl -> decodeUrl(imageSource.asUrl())
                imageSource.isImageFactory -> decodeImageFactory(imageSource.asImageFactory())
                imageSource.isUIImage -> decodeUIImage(imageSource.asUIImage())
                else -> {
                    log(WARN, "Unrecognized image source '$imageSource'")
                    null
                }
            }
            // Postprocessor (e.g. mercator re-projection) operates on the raw RGBA bytes.
            data?.let { imageSource.postprocessor?.process(it) ?: it }
        }

    protected open suspend fun decodeUrl(url: String): ImageData? {
        return try {
            val response = httpClient.get(url) {
                headers {
                    // Several map servers reject requests without these headers.
                    append(HttpHeaders.Accept, "image/*,*/*")
                    append(HttpHeaders.UserAgent,
                        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15")
                }
            }
            val bytes = response.readRawBytes()
            decodeBytes(bytes)
        } catch (e: Throwable) {
            log(WARN, "Image fetch failed for $url: ${e.message}")
            null
        }
    }

    protected open fun decodeResource(resource: ImageResource): ImageData? {
        // moko-resources 0.26.x iOS exposes `toUIImage(): UIImage?` which loads the asset
        // from the bundle (with whatever .car/asset-catalog magic moko applied at build
        // time). Wrap any failure as `null` so retrieval falls back through
        // retrievalFailed → absent list rather than crashing the GL queue.
        return try {
            val uiImage = resource.toUIImage()
            if (uiImage == null) {
                log(WARN, "ImageResource toUIImage returned null for $resource")
                null
            } else {
                decodeUIImage(uiImage)
            }
        } catch (e: Throwable) {
            log(WARN, "ImageResource decode failed: ${e.message}")
            null
        }
    }

    protected open suspend fun decodeImageFactory(factory: ImageSource.ImageFactory): ImageData? {
        return try {
            factory.createImage()?.let { decodeUIImage(it) }
        } catch (e: Throwable) {
            log(WARN, "ImageFactory.createImage failed: ${e.message}")
            null
        }
    }

    protected open fun decodeBytes(bytes: ByteArray): ImageData? {
        if (bytes.isEmpty()) return null
        return bytes.usePinned { pinned ->
            val nsdata = NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
            decodeNSData(nsdata)
        }
    }

    /** Convenience: decode raw image bytes (PNG, JPEG, etc.) into RGBA via UIImage + CGImage. */
    protected open fun decodeNSData(data: NSData): ImageData? {
        val uiImage = UIImage.imageWithData(data) ?: return null
        return decodeUIImage(uiImage)
    }

    /**
     * Convert a [UIImage] into tightly-packed RGBA8 bytes. Draws the underlying `CGImage`
     * into a RGBA8 `CGBitmapContext` whose backing store IS the returned [ByteArray] —
     * saves a copy versus reading back via `CGBitmapContextGetData`.
     */
    protected open fun decodeUIImage(uiImage: UIImage): ImageData? {
        val cgImage = uiImage.CGImage ?: return null
        val width = CGImageGetWidth(cgImage).toInt()
        val height = CGImageGetHeight(cgImage).toInt()
        if (width <= 0 || height <= 0) return null

        val rowBytes = width * 4
        val out = ByteArray(rowBytes * height)
        var success = false
        out.usePinned { pinned ->
            val cs = CGColorSpaceCreateDeviceRGB()
            // PremultipliedLast | byteOrder32Big = RGBA8888 with alpha in the LSB byte.
            val bitmapInfo: UInt =
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big
            val ctx = CGBitmapContextCreate(
                data = pinned.addressOf(0),
                width = width.toULong(),
                height = height.toULong(),
                bitsPerComponent = 8u,
                bytesPerRow = rowBytes.toULong(),
                space = cs,
                bitmapInfo = bitmapInfo,
            )
            if (ctx != null && cs != null) {
                CGContextDrawImage(
                    ctx,
                    CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
                    cgImage
                )
                CFRelease(ctx)
                success = true
            }
            if (cs != null) CFRelease(cs)
        }
        return if (success) ImageData(width, height, out) else null
    }
}
