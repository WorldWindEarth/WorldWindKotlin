@file:OptIn(ExperimentalForeignApi::class)

package earth.worldwind.formats.nitf

import earth.worldwind.render.image.ImageSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.UIKit.UIImage

actual fun NitfImage.toImageSource(): ImageSource {
    // Re-pack 0xAARRGGBB ints into RGBA bytes, the layout Core Graphics expects
    // when the bitmap context is configured with
    // `kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big`. Alpha is
    // always opaque in NITF output so premultiplication is a no-op.
    val n = width * height
    val rgba = ByteArray(n * 4)
    var di = 0
    for (i in 0 until n) {
        val v = argb[i]
        rgba[di] = ((v ushr 16) and 0xFF).toByte()     // R
        rgba[di + 1] = ((v ushr 8) and 0xFF).toByte()  // G
        rgba[di + 2] = (v and 0xFF).toByte()           // B
        rgba[di + 3] = ((v ushr 24) and 0xFF).toByte() // A
        di += 4
    }

    val colorSpace = CGColorSpaceCreateDeviceRGB()
    val uiImage: UIImage? = rgba.usePinned { pinned ->
        val bitmapInfo: UInt =
            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big
        val ctx = CGBitmapContextCreate(
            data = pinned.addressOf(0),
            width = width.toULong(),
            height = height.toULong(),
            bitsPerComponent = 8u,
            bytesPerRow = (width * 4).toULong(),
            space = colorSpace,
            bitmapInfo = bitmapInfo,
        ) ?: return@usePinned null
        val cgImage = CGBitmapContextCreateImage(ctx)
        CFRelease(ctx)
        if (cgImage == null) null else UIImage.imageWithCGImage(cgImage).also { CFRelease(cgImage) }
    }
    CFRelease(colorSpace)
    return uiImage?.let { ImageSource.fromUnrecognized(it) }
        ?: error("Failed to build UIImage from NITF pixels")
}
