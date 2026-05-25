@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileBlob

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * Filesystem-backed [TileStore] for iOS. One root directory per content key under
 * [baseDirectory]; tiles live at `${baseDirectory}/${contentKey}/${z}/${x}/${y}.bin`.
 *
 * ETag / Last-Modified ride alongside in a `${y}.meta` companion file written only when
 * at least one of them is set on the blob — most XYZ servers don't supply either, so the
 * common case stays one-file-per-tile.
 *
 * No locking — concurrent writes to the same tile are last-writer-wins. Acceptable for
 * the LRU-driven tile-pyramid pipeline where double-write means double-network and not
 * data corruption.
 *
 * Eviction is a no-op: the iOS port stays UNBOUNDED. Wire an explicit policy via
 * [CacheEvictionPolicy] only when a JS/iOS use case for it materialises.
 */
class FileSystemTileStore(
    private val baseDirectory: String,
    private val contentKey: String,
    override val evictionPolicy: CacheEvictionPolicy,
) : TileStore, CachedSourceInfoProvider {

    private val contentRoot: String = "$baseDirectory/$contentKey"

    override val cacheInfo: CachedSourceInfo
        get() = CachedSourceInfo(contentKey = contentKey, contentPath = baseDirectory)

    init { ensureDirectory(contentRoot) }

    override suspend fun readTile(z: Int, x: Int, y: Int): TileBlob? = withContext(Dispatchers.Default) {
        val path = tilePath(z, x, y)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return@withContext null
        val data = NSData.dataWithContentsOfFile(path) ?: return@withContext null
        val bytes = data.toByteArray()
        if (bytes.isEmpty()) return@withContext TileBlob.EMPTY
        val (etag, lastModified) = readMeta(z, x, y)
        TileBlob(bytes, etag, lastModified)
    }

    override suspend fun writeTile(z: Int, x: Int, y: Int, blob: TileBlob): Unit = withContext(Dispatchers.Default) {
        ensureDirectory("$contentRoot/$z/$x")
        val data = blob.bytes.toNsData()
        data.writeToFile(tilePath(z, x, y), atomically = true)
        if (blob.etag != null || blob.lastModified != null) writeMeta(z, x, y, blob.etag, blob.lastModified)
    }

    override suspend fun deleteTile(z: Int, x: Int, y: Int): Unit = withContext(Dispatchers.Default) {
        NSFileManager.defaultManager.removeItemAtPath(tilePath(z, x, y), null)
        NSFileManager.defaultManager.removeItemAtPath(metaPath(z, x, y), null)
    }

    override suspend fun sizeBytes(): Long = withContext(Dispatchers.Default) {
        directorySizeBytes(contentRoot)
    }

    private fun tilePath(z: Int, x: Int, y: Int): String = "$contentRoot/$z/$x/$y.bin"
    private fun metaPath(z: Int, x: Int, y: Int): String = "$contentRoot/$z/$x/${y}.meta"

    private fun readMeta(z: Int, x: Int, y: Int): Pair<String?, String?> {
        val path = metaPath(z, x, y)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null to null
        val data = NSData.dataWithContentsOfFile(path) ?: return null to null
        val lines = data.toByteArray().decodeToString().split('\n')
        // Two-line format: line 0 = ETag, line 1 = Last-Modified. Empty line means "not set".
        val etag = lines.getOrNull(0)?.takeIf { it.isNotEmpty() }
        val lastModified = lines.getOrNull(1)?.takeIf { it.isNotEmpty() }
        return etag to lastModified
    }

    private fun writeMeta(z: Int, x: Int, y: Int, etag: String?, lastModified: String?) {
        val text = "${etag.orEmpty()}\n${lastModified.orEmpty()}"
        val bytes = text.encodeToByteArray()
        bytes.toNsData().writeToFile(metaPath(z, x, y), atomically = true)
    }
}

internal fun ensureDirectory(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(
        path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
}

/** Recursive total file size under [path]. Single-pass enumerator; iOS-native traversal. */
internal fun directorySizeBytes(path: String): Long {
    val enumerator = NSFileManager.defaultManager.enumeratorAtPath(path) ?: return 0L
    var total = 0L
    while (true) {
        val name = enumerator.nextObject() as? String ?: break
        val full = "$path/$name"
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(full, null) ?: continue
        val size = (attrs[NSFileSize] as? NSNumber)?.longLongValue ?: 0L
        total += size
    }
    return total
}

internal fun ByteArray.toNsData(): NSData = if (isEmpty()) NSData() else usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}

internal fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { pinned ->
        platform.posix.memcpy(pinned.addressOf(0), bytes, length)
    }
    return out
}
