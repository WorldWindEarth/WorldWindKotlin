@file:OptIn(ExperimentalForeignApi::class)

package earth.worldwind.layer.cache
import earth.worldwind.geom.Sector
import earth.worldwind.layer.source.CachedFeatureRow
import earth.worldwind.layer.source.CachedGeometry

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.WARN

/**
 * Filesystem-backed [FeatureStore] for iOS. One directory per content key under
 * [baseDirectory]; layout:
 *
 *   * `${baseDirectory}/${contentKey}/features/bulk.json`     — entire bulk-row set, one JSON list.
 *   * `${baseDirectory}/${contentKey}/features/tile_${z}_${x}_${y}.json` — per-tile JSON list.
 *
 * `CachedGeometry` is `@Serializable` already so rows go in / out as a single JSON encode /
 * decode. The negative-cache sentinel (`fetched-but-empty`) writes a `[]` for the tile.
 */
class FileSystemFeatureStore(
    private val baseDirectory: String,
    private val contentKey: String,
    override val cachePolicy: CachePolicy,
) : FeatureStore, CachedSourceInfoProvider {

    override val cacheInfo: CachedSourceInfo
        get() = CachedSourceInfo(contentKey = contentKey, contentPath = baseDirectory)

    private val featuresRoot: String = "$baseDirectory/$contentKey/features"

    init { ensureDirectory(featuresRoot) }

    override suspend fun readAll(): Flow<CachedFeatureRow> = withContext(Dispatchers.Default) {
        val path = bulkPath()
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return@withContext emptyFlow()
        val text = readUtf8(path) ?: return@withContext emptyFlow()
        val list = runCatching { JSON.decodeFromString(rowListSerializer, text) }
            .onFailure { e ->
                Logger.log(WARN, "FileSystemFeatureStore.readAll('$contentKey') decode failed: " +
                    "${e::class.simpleName}: ${e.message}; discarding stale bulk cache")
                // Schema drift from an older build — self-heal so the next fetch repopulates.
                NSFileManager.defaultManager.removeItemAtPath(path, null)
            }
            .getOrNull()
            ?: return@withContext emptyFlow()
        list.asFlow()
    }

    override suspend fun replaceAll(rows: Flow<CachedFeatureRow>): Unit = withContext(Dispatchers.Default) {
        val materialised = rows.toList()
        val text = JSON.encodeToString(rowListSerializer, materialised)
        writeUtf8(bulkPath(), text)
    }

    // sector is unused — tiles are keyed per (z, x, y) on disk, no spatial bbox query.
    override suspend fun readTile(z: Int, x: Int, y: Int, sector: Sector?): Flow<CachedFeatureRow>? = withContext(Dispatchers.Default) {
        val path = tilePath(z, x, y)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return@withContext null
        val text = readUtf8(path) ?: return@withContext null
        val list = runCatching { JSON.decodeFromString(rowListSerializer, text) }.getOrNull()
            ?: return@withContext null
        if (list.isEmpty()) emptyFlow() else list.asFlow()
    }

    override suspend fun writeTile(z: Int, x: Int, y: Int, rows: Flow<CachedFeatureRow>, sector: Sector?): Unit = withContext(Dispatchers.Default) {
        val materialised = rows.toList()
        writeUtf8(tilePath(z, x, y), JSON.encodeToString(rowListSerializer, materialised))
    }

    override suspend fun deleteTile(z: Int, x: Int, y: Int): Unit = withContext(Dispatchers.Default) {
        NSFileManager.defaultManager.removeItemAtPath(tilePath(z, x, y), null)
    }

    override suspend fun readTileLastModified(z: Int, x: Int, y: Int): Long? = withContext(Dispatchers.Default) {
        fileModifiedMillis(tilePath(z, x, y))
    }

    /** Capacity eviction: drop whole per-tile files oldest-first (by mtime) until within
     *  [CachePolicy.maxEntries] (tile count). `bulk.json` (an atomic snapshot) is never touched.
     *  staleAfter never deletes. */
    override suspend fun evict(): Unit = withContext(Dispatchers.Default) {
        if (cachePolicy.isUnbounded) return@withContext
        val fm = NSFileManager.defaultManager
        val names = fm.contentsOfDirectoryAtPath(featuresRoot, null) ?: return@withContext
        data class TileFile(val path: String, val mtime: Long)
        val tiles = ArrayList<TileFile>()
        for (entry in names) {
            val name = entry as? String ?: continue
            if (!name.startsWith("tile_") || !name.endsWith(".json")) continue // skip bulk.json
            val path = "$featuresRoot/$name"
            val attrs = fm.attributesOfItemAtPath(path, null) ?: continue
            val mtime = (attrs[NSFileModificationDate] as? NSDate)?.let { (it.timeIntervalSince1970 * 1000.0).toLong() } ?: 0L
            tiles.add(TileFile(path, mtime))
        }
        var keptCount = 0L
        for (tile in tiles.sortedByDescending { it.mtime }) {
            if (keptCount >= cachePolicy.maxEntries) fm.removeItemAtPath(tile.path, null)
            else keptCount++
        }
    }

    private fun fileModifiedMillis(path: String): Long? {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return null
        val date = attrs[NSFileModificationDate] as? NSDate ?: return null
        return (date.timeIntervalSince1970 * 1000.0).toLong()
    }

    override suspend fun sizeBytes(): Long = withContext(Dispatchers.Default) {
        directorySizeBytes(featuresRoot)
    }

    private fun bulkPath(): String = "$featuresRoot/bulk.json"
    private fun tilePath(z: Int, x: Int, y: Int): String = "$featuresRoot/tile_${z}_${x}_${y}.json"

    private fun readUtf8(path: String): String? {
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        return data.toByteArray().decodeToString()
    }

    private fun writeUtf8(path: String, text: String) {
        val bytes = text.encodeToByteArray()
        bytes.toNsData().writeToFile(path, atomically = true)
    }

    private companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private val rowListSerializer = ListSerializer(CachedFeatureRow.serializer())
    }
}
