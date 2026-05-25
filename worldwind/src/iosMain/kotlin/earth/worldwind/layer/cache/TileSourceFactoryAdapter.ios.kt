@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileSource

import earth.worldwind.render.image.ImageSource
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIImage

actual fun buildTileSourceImageSource(
    source: TileSource, z: Int, x: Int, y: Int, imageFormat: String,
): ImageSource = ImageSource.fromImageFactory(TileSourceImageFactory(source, z, x, y, imageFormat))

/** iOS [ImageSource.ImageFactory] decoding via `UIImage(data:)`. */
private class TileSourceImageFactory(
    private val source: TileSource,
    private val z: Int, private val x: Int, private val y: Int,
    @Suppress("unused") private val imageFormat: String,
) : ImageSource.ImageFactory {
    override suspend fun createImage(): UIImage? {
        val blob = source.fetchTile(z, x, y) ?: return null
        if (blob.isEmpty) return null
        return blob.bytes.usePinned { pinned ->
            val data = NSData.create(bytes = pinned.addressOf(0), length = blob.bytes.size.toULong())
            UIImage.imageWithData(data)
        }
    }

    override fun equals(other: Any?): Boolean = other is TileSourceImageFactory
        && z == other.z && x == other.x && y == other.y && source === other.source

    override fun hashCode(): Int = 31 * 31 * 31 * source.hashCode() + 31 * 31 * z + 31 * x + y

    override fun toString() = "TileSourceImageFactory(z=$z, x=$x, y=$y)"
}
