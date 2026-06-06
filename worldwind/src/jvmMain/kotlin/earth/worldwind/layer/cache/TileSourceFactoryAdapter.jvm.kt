package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileBlob
import earth.worldwind.layer.source.TileSource

import earth.worldwind.render.image.ImageSource
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

actual fun buildTileSourceImageSource(
    source: TileSource, z: Int, x: Int, y: Int, imageFormat: String,
): ImageSource = ImageSource.fromImageFactory(TileSourceImageFactory(source, z, x, y, imageFormat))

/** JVM [ImageSource.NetworkBoundImageFactory] reading bytes from a [TileSource] and decoding
 *  via `javax.imageio.ImageIO`. The cache read ([createCachedImage]) and the network fetch
 *  ([createImage]) are exposed separately so the render cache can run them on the local vs
 *  remote retrieval lane respectively. Cache write-through happens inside the source decorator
 *  (e.g. [CachedTileSource]) — no postprocessor needed on this side. */
private class TileSourceImageFactory(
    private val source: TileSource,
    private val z: Int, private val x: Int, private val y: Int,
    @Suppress("unused") private val imageFormat: String,
) : ImageSource.NetworkBoundImageFactory {
    /** Remote lane: cache-then-network fetch with write-through. */
    override suspend fun createImage(): BufferedImage? = decode(source.fetchTile(z, x, y))

    /** Local lane: cache-only read, `null` on a miss (never touches the network). */
    override suspend fun createCachedImage(): BufferedImage? = decode(source.tryReadCachedTile(z, x, y))

    private fun decode(blob: TileBlob?): BufferedImage? {
        if (blob == null || blob.isEmpty) return null
        return ByteArrayInputStream(blob.bytes).use { ImageIO.read(it) }
    }

    override fun equals(other: Any?): Boolean = other is TileSourceImageFactory
        && z == other.z && x == other.x && y == other.y && source === other.source

    override fun hashCode(): Int = 31 * 31 * 31 * source.hashCode() + 31 * 31 * z + 31 * x + y

    override fun toString() = "TileSourceImageFactory(z=$z, x=$x, y=$y)"
}
