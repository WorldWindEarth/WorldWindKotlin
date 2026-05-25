package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileSource

import earth.worldwind.render.image.ImageSource
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import org.khronos.webgl.TexImageSource
import org.w3c.dom.ImageBitmap
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import kotlin.js.Promise

actual fun buildTileSourceImageSource(
    source: TileSource, z: Int, x: Int, y: Int, imageFormat: String,
): ImageSource = ImageSource.fromImageFactory(TileSourceImageFactory(source, z, x, y, imageFormat))

/** JS [ImageSource.ImageFactory] decoding via [createImageBitmap]. */
private class TileSourceImageFactory(
    private val source: TileSource,
    private val z: Int, private val x: Int, private val y: Int,
    private val imageFormat: String,
) : ImageSource.ImageFactory {
    override suspend fun createImage(): TexImageSource? {
        val blob = source.fetchTile(z, x, y) ?: return null
        if (blob.isEmpty) return null
        // Zero-copy view: ByteArray is backed by Int8Array on Kotlin/JS, bit-compatible
        // with Uint8Array. The element-wise loop here was a per-tile O(N) dynamic-dispatch
        // hot path — for a 50 KB tile, ~50,000 dynamic-property writes per decode.
        val u8 = blob.bytes.toUint8Array()
        val arr = js("[u8]")
        val webBlob = Blob(arr.unsafeCast<Array<dynamic>>(), BlobPropertyBag(imageFormat))
        return try {
            js("self.createImageBitmap(webBlob)").unsafeCast<Promise<ImageBitmap>>().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log(WARN, "createImageBitmap failed for tile (z=$z, x=$x, y=$y): ${e.message}")
            null
        }
    }

    override fun equals(other: Any?): Boolean = other is TileSourceImageFactory
        && z == other.z && x == other.x && y == other.y && source === other.source

    override fun hashCode(): Int = 31 * 31 * 31 * source.hashCode() + 31 * 31 * z + 31 * x + y

    override fun toString() = "TileSourceImageFactory(z=$z, x=$x, y=$y)"
}
