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
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.posix.memcpy
import kotlin.time.Duration

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
 * [evict] honors a bounded [CachePolicy] by dropping the oldest tile files (by mtime); an
 * unbounded policy is a no-op.
 */
class FileSystemTileStore(
    private val baseDirectory: String,
    private val contentKey: String,
    override val cachePolicy: CachePolicy,
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
        // Surface write time (the tile file's mtime) only when freshness tracking is on
        // (finite staleAfter), to drive stale-while-revalidate in CachedTileSource / elevation factory.
        val cachedAt = if (cachePolicy.staleAfter != Duration.INFINITE) fileModifiedMillis(path) else null
        TileBlob(bytes, etag, lastModified, cachedAt = cachedAt)
    }

    private fun fileModifiedMillis(path: String): Long? {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return null
        val date = attrs[NSFileModificationDate] as? NSDate ?: return null
        return (date.timeIntervalSince1970 * 1000.0).toLong()
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

    // 304 path: cachedAt is the tile file's mtime, so touch it to now — keeps bytes / meta intact.
    override suspend fun bumpValidatedAt(z: Int, x: Int, y: Int): Unit = withContext(Dispatchers.Default) {
        val path = tilePath(z, x, y)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return@withContext
        NSFileManager.defaultManager.setAttributes(
            mapOf<Any?, Any?>(NSFileModificationDate to NSDate()),
            ofItemAtPath = path,
            error = null,
        )
    }

    /** Capacity eviction: drop tile files oldest-first (by mtime) until within
     *  [CachePolicy.maxEntries] (tile count) and [CachePolicy.maxBytes], removing each `.meta`
     *  sidecar with its `.bin`. staleAfter never deletes. */
    override suspend fun evict(): Unit = withContext(Dispatchers.Default) {
        if (cachePolicy.isUnbounded) return@withContext
        val fm = NSFileManager.defaultManager
        val enumerator = fm.enumeratorAtPath(contentRoot) ?: return@withContext
        data class TileFile(val path: String, val mtime: Long, val size: Long)
        val tiles = ArrayList<TileFile>()
        while (true) {
            val name = enumerator.nextObject() as? String ?: break
            if (!name.endsWith(".bin")) continue // skip directories + .meta sidecars
            val path = "$contentRoot/$name"
            val attrs = fm.attributesOfItemAtPath(path, null) ?: continue
            val mtime = (attrs[NSFileModificationDate] as? NSDate)?.let { (it.timeIntervalSince1970 * 1000.0).toLong() } ?: 0L
            val size = (attrs[NSFileSize] as? NSNumber)?.longLongValue ?: 0L
            tiles.add(TileFile(path, mtime, size))
        }
        var keptCount = 0L
        var keptBytes = 0L
        for (tile in tiles.sortedByDescending { it.mtime }) {
            val overCount = keptCount >= cachePolicy.maxEntries
            val overBytes = cachePolicy.maxBytes != Long.MAX_VALUE && keptBytes + tile.size > cachePolicy.maxBytes
            if (overCount || overBytes) {
                fm.removeItemAtPath(tile.path, null)
                fm.removeItemAtPath(tile.path.removeSuffix(".bin") + ".meta", null)
            } else {
                keptCount++; keptBytes += tile.size
            }
        }
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
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return out
}
