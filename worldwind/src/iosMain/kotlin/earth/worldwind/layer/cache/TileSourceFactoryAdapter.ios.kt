@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileBlob
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

/** iOS [ImageSource.NetworkBoundImageFactory] decoding via `UIImage(data:)`. The cache read
 *  ([createCachedImage]) and the network fetch ([createImage]) are exposed separately so the
 *  render cache can run them on the local vs remote retrieval lane respectively. */
private class TileSourceImageFactory(
    private val source: TileSource,
    private val z: Int, private val x: Int, private val y: Int,
    @Suppress("unused") private val imageFormat: String,
) : ImageSource.NetworkBoundImageFactory {
    /** Remote lane: cache-then-network fetch with write-through. */
    override suspend fun createImage(): UIImage? = decode(source.fetchTile(z, x, y))

    /** Local lane: cache-only read, `null` on a miss (never touches the network). */
    override suspend fun createCachedImage(): UIImage? = decode(source.tryReadCachedTile(z, x, y))

    private fun decode(blob: TileBlob?): UIImage? {
        if (blob == null || blob.isEmpty) return null
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
