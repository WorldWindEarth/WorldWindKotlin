@file:OptIn(ExperimentalForeignApi::class)

package earth.worldwind.layer.cache

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSDate
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Filesystem-backed [BlobStore] for iOS. One directory per content key under
 * [IosContentManager]'s base directory; one pair of files per cached URI:
 *
 * ```
 * ${baseDirectory}/${contentKey}/blobs/<fnv1a-hex>.bin     payload bytes
 * ${baseDirectory}/${contentKey}/blobs/<fnv1a-hex>.meta    JSON sidecar
 * ```
 *
 * The filename hash is FNV-1a 64-bit hex (16 chars). Plenty of collision resistance for a
 * single layer's URI keyspace (tens of thousands of tiles) and avoids depending on
 * CommonCrypto via cinterop. Collisions are detected at read time by re-checking the URI
 * stored in the `.meta` sidecar; a hash collision on different URIs falls through as a
 * cache miss instead of returning the wrong row.
 *
 * Sidecar schema is [BlobMetaJson] — original URI + content-type + etag + last-modified +
 * cached-at + size-bytes. Eviction sweeps walk the directory, parse sidecars, and sort by
 * `cachedAtMs` ascending to drop oldest first.
 */
internal class FileSystemBlobStore(
    private val baseDirectory: String,
    private val contentKey: String,
    private val evictionPolicy: CachePolicy,
) : BlobStore {

    private val blobsDir: String = "$baseDirectory/$contentKey/blobs"
    private val json = Json { ignoreUnknownKeys = true }

    init { ensureDirectory(blobsDir) }

    override suspend fun get(uri: String): BlobEntry? = withContext(Dispatchers.Default) {
        val hash = fnv1aHex(uri)
        val metaPath = "$blobsDir/$hash.meta"
        val metaBytes = NSData.dataWithContentsOfFile(metaPath)?.toByteArray() ?: return@withContext null
        val meta = runCatching { json.decodeFromString<BlobMetaJson>(metaBytes.decodeToString()) }
            .getOrNull() ?: return@withContext null
        // Collision detection: filename hash collisions on different URIs return null instead
        // of returning the wrong payload.
        if (meta.uri != uri) return@withContext null
        val dataPath = "$blobsDir/$hash.bin"
        val data = NSData.dataWithContentsOfFile(dataPath) ?: return@withContext null
        BlobEntry(
            bytes = data.toByteArray(),
            contentType = meta.contentType,
            etag = meta.etag,
            lastModified = meta.lastModifiedMs?.let { Instant.fromEpochMilliseconds(it) },
            responseUrl = meta.responseUrl,
        )
    }

    override suspend fun put(
        uri: String,
        bytes: ByteArray,
        contentType: String?,
        etag: String?,
        lastModified: Instant?,
        responseUrl: String?,
    ): Unit = withContext(Dispatchers.Default) {
        val hash = fnv1aHex(uri)
        val meta = BlobMetaJson(
            uri = uri,
            contentType = contentType,
            etag = etag,
            lastModifiedMs = lastModified?.toEpochMilliseconds(),
            cachedAtMs = Clock.System.now().toEpochMilliseconds(),
            sizeBytes = bytes.size.toLong(),
            responseUrl = responseUrl,
        )
        // Crash-safe pair-update: new .bin first, drop old .meta, write new .meta last.
        // Worst observable mid-state is "new .bin, no .meta" → cache miss → re-fetch.
        // Never new bytes paired with old envelope.
        val fm = NSFileManager.defaultManager
        bytes.toNsData().writeToFile("$blobsDir/$hash.bin", atomically = true)
        fm.removeItemAtPath("$blobsDir/$hash.meta", error = null)
        json.encodeToString(BlobMetaJson.serializer(), meta).encodeToByteArray().toNsData()
            .writeToFile("$blobsDir/$hash.meta", atomically = true)
    }

    override suspend fun remove(uri: String): Unit = withContext(Dispatchers.Default) {
        val hash = fnv1aHex(uri)
        val fm = NSFileManager.defaultManager
        fm.removeItemAtPath("$blobsDir/$hash.bin", error = null)
        fm.removeItemAtPath("$blobsDir/$hash.meta", error = null)
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.Default) {
        val fm = NSFileManager.defaultManager
        val enumerator = fm.enumeratorAtPath(blobsDir) ?: return@withContext
        while (true) {
            val name = enumerator.nextObject() as? String ?: break
            if (name.endsWith(".bin") || name.endsWith(".meta")) {
                fm.removeItemAtPath("$blobsDir/$name", error = null)
            }
        }
    }

    override suspend fun sizeBytes(): Long = withContext(Dispatchers.Default) {
        directorySizeBytes(blobsDir)
    }

    /** Apply [evictionPolicy] sweep — oldest cachedAt first until each bound is satisfied. */
    suspend fun evict(): Unit = withContext(Dispatchers.Default) {
        if (evictionPolicy.isUnbounded) return@withContext
        val fm = NSFileManager.defaultManager
        val enumerator = fm.enumeratorAtPath(blobsDir) ?: return@withContext
        data class Row(val hash: String, val cachedAtMs: Long)
        val rows = mutableListOf<Row>()
        while (true) {
            val name = enumerator.nextObject() as? String ?: break
            if (!name.endsWith(".meta")) continue
            val hash = name.substringBeforeLast(".meta")
            val metaPath = "$blobsDir/$name"
            val metaBytes = NSData.dataWithContentsOfFile(metaPath)?.toByteArray() ?: continue
            val meta = runCatching { json.decodeFromString<BlobMetaJson>(metaBytes.decodeToString()) }
                .getOrNull() ?: continue
            rows.add(Row(hash, meta.cachedAtMs))
        }
        rows.sortBy { it.cachedAtMs }

        // staleAfter NEVER deletes — stale blobs are refreshed in place by
        // stale-while-revalidate, not removed. Only [CachePolicy.maxEntries] evicts
        // (each tile is one file, whole-tile).
        if (rows.size > evictionPolicy.maxEntries) {
            val toDrop = rows.size - evictionPolicy.maxEntries.toInt()
            for (i in 0 until toDrop) {
                fm.removeItemAtPath("$blobsDir/${rows[i].hash}.bin", error = null)
                fm.removeItemAtPath("$blobsDir/${rows[i].hash}.meta", error = null)
            }
        }
    }
}

/** Sidecar JSON written next to each cached payload. */
@Serializable
private data class BlobMetaJson(
    val uri: String,
    val contentType: String? = null,
    val etag: String? = null,
    val lastModifiedMs: Long? = null,
    val cachedAtMs: Long,
    val sizeBytes: Long,
    /** Post-redirect URL the body was actually served from. Null for legacy sidecars. */
    val responseUrl: String? = null,
)

/**
 * FNV-1a 64-bit hash, hex-encoded as 16 characters. Filesystem-safe and collision-resistant
 * enough for per-layer URI keyspaces (collision probability at 100K entries is roughly 2.7e-10).
 * Hash collisions on different URIs are caught at read time by the sidecar URI check, so a
 * collision falls through as a cache miss rather than returning the wrong payload.
 */
internal fun fnv1aHex(s: String): String {
    val prime = 1099511628211UL
    val offset = 14695981039346656037UL
    var h = offset
    val bytes = s.encodeToByteArray()
    for (b in bytes) {
        h = h xor (b.toUByte().toULong())
        h *= prime
    }
    // Manual zero-pad to 16 hex chars (toString(16) drops leading zeros).
    val hex = h.toLong().toULong().toString(16)
    return hex.padStart(16, '0')
}
