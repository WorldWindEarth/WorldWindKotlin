@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileBlob

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * Filesystem-backed [ElevationStoreBackend] for iOS. Wraps an existing [FileSystemTileStore]
 * for tile-blob I/O; per-tile `(scale, offset)` lives in a sidecar
 * `${z}/${x}/${y}.ancillary` file (8 bytes: 4-byte LE Float scale + 4-byte LE Float
 * offset; absent for the default `(1, 0)`).
 *
 * Combined with a [CachedElevationSourceFactory] so the cross-platform codec runs the
 * encode/decode pipeline.
 */
internal class FileSystemElevationBackend(
    private val baseDirectory: String,
    private val contentKey: String,
    private val tileStore: FileSystemTileStore,
) : ElevationStoreBackend {

    override val isReadOnly: Boolean = false

    override val cacheInfo: CachedSourceInfo
        get() = CachedSourceInfo(contentKey = contentKey, contentPath = baseDirectory)

    private val contentRoot: String = "$baseDirectory/$contentKey"

    override suspend fun readTile(z: Int, x: Int, y: Int): CachedTile? {
        val blob = tileStore.readTile(z, x, y) ?: return null
        if (blob.isEmpty) return null
        val (scale, offset) = readAncillary(z, x, y) ?: (1f to 0f)
        return CachedTile(bytes = blob.bytes, tileScale = scale, tileOffset = offset)
    }

    override suspend fun writeTile(
        z: Int, x: Int, y: Int,
        bytes: ByteArray,
        tileScale: Float,
        tileOffset: Float,
    ) {
        tileStore.writeTile(z, x, y, TileBlob(bytes = bytes))
        if (tileScale != 1f || tileOffset != 0f) {
            writeAncillary(z, x, y, tileScale, tileOffset)
        }
    }

    private suspend fun readAncillary(z: Int, x: Int, y: Int): Pair<Float, Float>? =
        withContext(Dispatchers.Default) {
            val path = ancillaryPath(z, x, y)
            if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return@withContext null
            val data = NSData.dataWithContentsOfFile(path) ?: return@withContext null
            val bytes = data.toByteArray()
            if (bytes.size < 8) return@withContext null
            val scale = bytes.readFloatLE(0)
            val offset = bytes.readFloatLE(4)
            scale to offset
        }

    private suspend fun writeAncillary(z: Int, x: Int, y: Int, scale: Float, offset: Float) =
        withContext(Dispatchers.Default) {
            ensureDirectory("$contentRoot/$z/$x")
            val bytes = ByteArray(8)
            bytes.writeFloatLE(0, scale)
            bytes.writeFloatLE(4, offset)
            bytes.toNsData().writeToFile(ancillaryPath(z, x, y), atomically = true)
            Unit
        }

    private fun ancillaryPath(z: Int, x: Int, y: Int): String =
        "$contentRoot/$z/$x/$y.ancillary"

    private fun ByteArray.readFloatLE(off: Int): Float {
        val raw = (this[off].toInt() and 0xFF) or
            ((this[off + 1].toInt() and 0xFF) shl 8) or
            ((this[off + 2].toInt() and 0xFF) shl 16) or
            ((this[off + 3].toInt() and 0xFF) shl 24)
        return Float.fromBits(raw)
    }

    private fun ByteArray.writeFloatLE(off: Int, value: Float) {
        val raw = value.toRawBits()
        this[off] = (raw and 0xFF).toByte()
        this[off + 1] = ((raw ushr 8) and 0xFF).toByte()
        this[off + 2] = ((raw ushr 16) and 0xFF).toByte()
        this[off + 3] = ((raw ushr 24) and 0xFF).toByte()
    }
}
