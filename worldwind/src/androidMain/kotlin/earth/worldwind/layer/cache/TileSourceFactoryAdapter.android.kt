package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileBlob
import earth.worldwind.layer.source.TileSource

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import earth.worldwind.render.image.ImageSource

actual fun buildTileSourceImageSource(
    source: TileSource, z: Int, x: Int, y: Int, imageFormat: String,
): ImageSource = ImageSource.fromImageFactory(TileSourceImageFactory(source, z, x, y, imageFormat))

/** Android [ImageSource.NetworkBoundImageFactory] reading bytes from a [TileSource] and
 *  decoding via `BitmapFactory.decodeByteArray`. The cache read ([createCachedBitmap]) and
 *  the network fetch ([createBitmap]) are exposed separately so the render cache can run
 *  them on the local vs remote retrieval lane respectively. */
private class TileSourceImageFactory(
    private val source: TileSource,
    private val z: Int, private val x: Int, private val y: Int,
    @Suppress("unused") private val imageFormat: String,
) : ImageSource.NetworkBoundImageFactory {
    /** Remote lane: cache-then-network fetch with write-through. */
    override suspend fun createBitmap(): Bitmap? = decode(source.fetchTile(z, x, y))

    /** Local lane: cache-only read, `null` on a miss (never touches the network). */
    override suspend fun createCachedBitmap(): Bitmap? = decode(source.tryReadCachedTile(z, x, y))

    private fun decode(blob: TileBlob?): Bitmap? {
        if (blob == null || blob.isEmpty) return null
        return BitmapFactory.decodeByteArray(blob.bytes, 0, blob.bytes.size)
    }

    override fun equals(other: Any?): Boolean = other is TileSourceImageFactory
        && z == other.z && x == other.x && y == other.y && source === other.source

    override fun hashCode(): Int = 31 * 31 * 31 * source.hashCode() + 31 * 31 * z + 31 * x + y

    override fun toString() = "TileSourceImageFactory(z=$z, x=$x, y=$y)"
}
