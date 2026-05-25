package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileSource

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import earth.worldwind.render.image.ImageSource

actual fun buildTileSourceImageSource(
    source: TileSource, z: Int, x: Int, y: Int, imageFormat: String,
): ImageSource = ImageSource.fromImageFactory(TileSourceImageFactory(source, z, x, y, imageFormat))

/** Android [ImageSource.ImageFactory] reading bytes from a [TileSource] and decoding via
 *  `BitmapFactory.decodeByteArray`. */
private class TileSourceImageFactory(
    private val source: TileSource,
    private val z: Int, private val x: Int, private val y: Int,
    @Suppress("unused") private val imageFormat: String,
) : ImageSource.ImageFactory {
    override suspend fun createBitmap(): Bitmap? {
        val blob = source.fetchTile(z, x, y) ?: return null
        if (blob.isEmpty) return null
        return BitmapFactory.decodeByteArray(blob.bytes, 0, blob.bytes.size)
    }

    override fun equals(other: Any?): Boolean = other is TileSourceImageFactory
        && z == other.z && x == other.x && y == other.y && source === other.source

    override fun hashCode(): Int = 31 * 31 * 31 * source.hashCode() + 31 * 31 * z + 31 * x + y

    override fun toString() = "TileSourceImageFactory(z=$z, x=$x, y=$y)"
}
