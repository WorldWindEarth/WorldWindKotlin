@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package earth.worldwind.layer.mvt

import earth.worldwind.render.image.ImageSource
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGImageCreateWithImageInRect
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.UIKit.UIImage

actual class MvtAtlasIconFactory actual constructor(
    actual val atlas: MvtSpriteAtlas,
    actual val entry: MvtSpriteEntry,
) : ImageSource.ImageFactory {

    override suspend fun createImage(): UIImage? {
        val whole = decodedAtlasFor(atlas) ?: return null
        val wholeCg = whole.CGImage ?: return null
        val rect = CGRectMake(
            entry.x.toDouble(), entry.y.toDouble(),
            entry.width.toDouble(), entry.height.toDouble(),
        )
        val cgSub = CGImageCreateWithImageInRect(wholeCg, rect) ?: return null
        return try {
            UIImage.imageWithCGImage(cgSub)
        } finally {
            CGImageRelease(cgSub)
        }
    }

    actual companion object {
        // Single-decode-per-atlas cache. iOS doesn't guarantee single-threaded fetch but the
        // worst-case race is a duplicate decode that overwrites the cache with the same
        // image — the engine's RenderResourceCache de-dups GL texture uploads anyway.
        private val decodeCache = HashMap<MvtSpriteAtlas, UIImage?>()

        private fun decodedAtlasFor(atlas: MvtSpriteAtlas): UIImage? {
            decodeCache[atlas]?.let { return it }
            val bytes = atlas.imageBytes
            val image = bytes.usePinned { pinned ->
                val nsdata = NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
                UIImage.imageWithData(nsdata)
            }
            decodeCache[atlas] = image
            return image
        }

        actual fun releaseDecodedImage(atlas: MvtSpriteAtlas) { decodeCache.remove(atlas) }
    }
}
