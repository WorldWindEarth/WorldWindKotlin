@file:OptIn(ExperimentalForeignApi::class)

package earth.worldwind.layer.cache
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
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
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
                    "${e::class.simpleName}: ${e.message}")
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

    override suspend fun readTile(z: Int, x: Int, y: Int): Flow<CachedFeatureRow>? = withContext(Dispatchers.Default) {
        val path = tilePath(z, x, y)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return@withContext null
        val text = readUtf8(path) ?: return@withContext null
        val list = runCatching { JSON.decodeFromString(rowListSerializer, text) }.getOrNull()
            ?: return@withContext null
        if (list.isEmpty()) emptyFlow() else list.asFlow()
    }

    override suspend fun writeTile(z: Int, x: Int, y: Int, rows: Flow<CachedFeatureRow>): Unit = withContext(Dispatchers.Default) {
        val materialised = rows.toList()
        writeUtf8(tilePath(z, x, y), JSON.encodeToString(rowListSerializer, materialised))
    }

    override suspend fun deleteTile(z: Int, x: Int, y: Int): Unit = withContext(Dispatchers.Default) {
        NSFileManager.defaultManager.removeItemAtPath(tilePath(z, x, y), null)
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
