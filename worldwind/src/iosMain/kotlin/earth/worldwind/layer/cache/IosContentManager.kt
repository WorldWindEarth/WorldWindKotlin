@file:OptIn(ExperimentalForeignApi::class, LowLevelCacheApi::class)

package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileSource

import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.util.ContentManager
import earth.worldwind.util.LevelSet
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import kotlin.time.Instant
import earth.worldwind.layer.cache.LowLevelCacheApi
import earth.worldwind.util.Logger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Filesystem-backed [ContentManager] for iOS. Everything persists under [baseDirectory]
 * (defaults to `~/Library/Caches/worldwind-cache`); one top-level directory per content key.
 *
 * Directory layout:
 * ```
 * ${baseDirectory}/
 *   ${contentKey}/
 *     _web_service.json                       (web-service metadata, optional)
 *     _data_type.txt                          (data-type fingerprint — TILES / VECTOR_TILES / …)
 *     ${z}/${x}/${y}.bin                       (tile bytes — image/vector/coverage)
 *     ${z}/${x}/${y}.meta                      (etag + last-modified, only when set)
 *     features/bulk.json                       (bulk feature rows — WFS/Shapefile)
 *     features/tile_${z}_${x}_${y}.json        (tile-addressed rows — OsmBuildings)
 * ```
 *
 * cached-layer dispatcher (`OpenCachedLayer.kt` in commonMain) routes reopen via reified `openLayer` / `openElevationCoverage` plus `CachedLayerRegistry`. The WFS
 * reconstruction path depends on `mil.nga.sf`. Callers on iOS can still inspect
 * [listEntries] / [findEntry] and construct their own layers per entry type.
 */
class IosContentManager(
    baseDirectory: String = defaultCachesDirectory(),
) : ContentManager {

    override val pathName: String = baseDirectory
    override val isReadOnly: Boolean = false

    init { ensureDirectory(pathName) }

    private var closed: Boolean = false
    override val isClosed get() = closed

    /** No file handle to release on iOS — the filesystem store doesn't keep one open.
     *  We still flip the flag so subsequent calls behave consistently with the JVM/JS
     *  implementations (further reads return empty, further writes are no-ops). */
    override suspend fun close() { closed = true }

    override suspend fun contentSize(): Long = withContext(Dispatchers.Default) {
        directorySizeBytes(pathName)
    }

    override suspend fun sizeBytesOf(contentKey: String): Long = withContext(Dispatchers.Default) {
        directorySizeBytes("$pathName/$contentKey")
    }

    /** Most-recent _web_service.json modification time across all content keys. Per-content
     *  timestamps are surfaced via [listEntries] / [findEntry]. */
    override suspend fun lastModifiedDate(): Instant? = withContext(Dispatchers.Default) {
        val fm = NSFileManager.defaultManager
        val enumerator = fm.enumeratorAtPath(pathName) ?: return@withContext null
        var maxMs = 0L
        while (true) {
            val name = enumerator.nextObject() as? String ?: break
            if (name.contains('/')) continue
            val ms = contentKeyLastModifiedMs(name) ?: continue
            if (ms > maxMs) maxMs = ms
        }
        if (maxMs > 0L) Instant.fromEpochMilliseconds(maxMs) else null
    }

    /**
     * Filesystem-backed [BlobStore] for 3D Tiles payloads. Persists under
     * `${baseDirectory}/${contentKey}/blobs/<fnv1a-hex>.bin` with a JSON `.meta` sidecar.
     */
    override suspend fun openBlobStore(
        contentKey: String,
        evictionPolicy: CachePolicy,
        displayName: String?,
    ): BlobStore {
        rejectIfPreviouslyTypedAs(contentKey, CacheEntry.DataType.OGC_3D_TILES)
        rememberDataType(contentKey, CacheEntry.DataType.OGC_3D_TILES)
        if (displayName != null) rememberDisplayName(contentKey, displayName)
        return FileSystemBlobStore(pathName, contentKey, evictionPolicy).also {
            if (!evictionPolicy.isUnbounded) runCatching { it.evict() }
        }
    }

    override suspend fun openImageTileStore(
        contentKey: String,
        levelSet: LevelSet,
        imageFormat: String,
        isTransparent: Boolean,
        cachePolicy: CachePolicy,
        displayName: String?,
    ): TileStore {
        rejectIfPreviouslyTypedAs(contentKey, CacheEntry.DataType.TILES)
        rememberDataType(contentKey, CacheEntry.DataType.TILES)
        if (displayName != null) rememberDisplayName(contentKey, displayName)
        return FileSystemTileStore(pathName, contentKey, cachePolicy)
            .also { if (!cachePolicy.isUnbounded) runCatching { it.evict() } }
    }

    override suspend fun openVectorTileStore(
        contentKey: String,
        levelSet: LevelSet,
        cachePolicy: CachePolicy,
        displayName: String?,
    ): TileStore {
        rejectIfPreviouslyTypedAs(contentKey, CacheEntry.DataType.VECTOR_TILES)
        rememberDataType(contentKey, CacheEntry.DataType.VECTOR_TILES)
        if (displayName != null) rememberDisplayName(contentKey, displayName)
        return FileSystemTileStore(pathName, contentKey, cachePolicy)
            .also { if (!cachePolicy.isUnbounded) runCatching { it.evict() } }
    }

    override suspend fun createElevationSourceFactory(
        contentKey: String,
        tileMatrixSet: TileMatrixSet,
        networkSource: TileSource?,
        outputFormat: String,
        isFloat: Boolean,
        cachePolicy: CachePolicy,
        displayName: String?,
    ): ElevationSourceFactory {
        // The tile blob store is the existing per-content FileSystemTileStore (raw bytes,
        // .meta sidecars for revalidation). Transcoded blobs land in the same files with
        // per-tile (scale, offset) in .ancillary sidecars; the codec runs the
        // encode/decode pipeline.
        rejectIfPreviouslyTypedAs(contentKey, CacheEntry.DataType.COVERAGE)
        rememberDataType(contentKey, CacheEntry.DataType.COVERAGE)
        if (displayName != null) rememberDisplayName(contentKey, displayName)
        rememberIsFloat(contentKey, isFloat)
        val tileStore = FileSystemTileStore(pathName, contentKey, cachePolicy)
            .also { if (!cachePolicy.isUnbounded) runCatching { it.evict() } }
        val backend = FileSystemElevationBackend(
            baseDirectory = pathName,
            contentKey = contentKey,
            tileStore = tileStore,
        )
        // Honor the stored isFloat on reopen — caller value is a creation-time hint.
        val storedIsFloat = findEntry(contentKey)?.isFloat
        return CachedElevationSourceFactory(
            backend = backend,
            networkSource = networkSource,
            networkDecoder = IosElevationNetworkDecoder,
            outputFormat = outputFormat,
            isFloat = storedIsFloat ?: isFloat,
            tileMatrixSet = tileMatrixSet,
            staleAfter = cachePolicy.staleAfter,
        )
    }

    /** Persist the coverage's storage-layout choice in an `_is_float.txt` sidecar.
     *  First-write-wins — the file is the source of truth on reopen, matching the
     *  gpkg `gpkg_2d_gridded_coverage_ancillary.dataType` contract on JVM. */
    private suspend fun rememberIsFloat(contentKey: String, isFloat: Boolean) = withContext(Dispatchers.Default) {
        ensureDirectory("$pathName/$contentKey")
        val path = isFloatPathFor(contentKey)
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) return@withContext
        isFloat.toString().encodeToByteArray().toNsData().writeToFile(path, atomically = true)
    }

    private fun isFloatPathFor(contentKey: String): String = "$pathName/$contentKey/_is_float.txt"

    override suspend fun openFeatureStore(
        contentKey: String,
        cachePolicy: CachePolicy,
        displayName: String?,
    ): FeatureStore {
        rejectIfPreviouslyTypedAs(contentKey, CacheEntry.DataType.FEATURES)
        rememberDataType(contentKey, CacheEntry.DataType.FEATURES)
        if (displayName != null) rememberDisplayName(contentKey, displayName)
        return FileSystemFeatureStore(pathName, contentKey, cachePolicy)
            .also { if (!cachePolicy.isUnbounded) runCatching { it.evict() } }
    }

    override suspend fun registerWebService(contentKey: String, info: WebServiceInfo): Unit = withContext(Dispatchers.Default) {
        try {
            ensureDirectory("$pathName/$contentKey")
            val text = JSON.encodeToString(WebServiceInfoSurrogate.serializer(), WebServiceInfoSurrogate.from(info))
            text.encodeToByteArray().toNsData().writeToFile(metaPathFor(contentKey), atomically = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.log(
                Logger.WARN,
                "IosContentManager.registerWebService('$contentKey') failed: ${e.message}",
            )
        }
    }

    /** Read the persisted [WebServiceInfo] sidecar JSON. Private — public callers go
     *  through [findEntry] / [listEntries]. */
    private suspend fun loadWebServiceInfo(contentKey: String): WebServiceInfo? = withContext(Dispatchers.Default) {
        val path = metaPathFor(contentKey)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return@withContext null
        val data = NSData.dataWithContentsOfFile(path) ?: return@withContext null
        val text = data.toByteArray().decodeToString()
        runCatching {
            JSON.decodeFromString(WebServiceInfoSurrogate.serializer(), text).toInfo()
        }.getOrNull()
    }

    override suspend fun listEntries(): List<CacheEntry> = withContext(Dispatchers.Default) {
        val out = mutableListOf<CacheEntry>()
        // contentsOfDirectoryAtPath is top-level only — the prior enumeratorAtPath walked
        // every leaf which scaled poorly on populated caches (10k tiles → 10k callbacks).
        val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(pathName, null) ?: return@withContext out
        for (raw in names) {
            val name = raw as? String ?: continue
            val info = loadWebServiceInfo(name) ?: continue
            out += buildHandle(name, info)
        }
        out
    }

    override suspend fun findEntry(contentKey: String): CacheEntry? {
        val info = loadWebServiceInfo(contentKey) ?: return null
        return buildHandle(contentKey, info)
    }

    private suspend fun buildHandle(contentKey: String, info: WebServiceInfo): CacheEntry {
        val dataType = readDataType(contentKey) ?: serviceTypeToDataType(info.type)
        val isFloat = dataType == CacheEntry.DataType.COVERAGE && readIsFloat(contentKey) == true
        return CacheEntry(
            contentKey = contentKey,
            dataType = dataType,
            service = info,
            boundingSector = null,
            lastModified = contentKeyLastModifiedMs(contentKey)?.let { Instant.fromEpochMilliseconds(it) },
            displayName = readDisplayName(contentKey) ?: contentKey,
            isFloat = isFloat,
        )
    }

    private suspend fun readIsFloat(contentKey: String): Boolean? = withContext(Dispatchers.Default) {
        val path = isFloatPathFor(contentKey)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return@withContext null
        val data = NSData.dataWithContentsOfFile(path) ?: return@withContext null
        when (data.toByteArray().decodeToString().trim()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    override suspend fun clearEntry(contentKey: String): Unit = withContext(Dispatchers.Default) {
        try {
            val root = "$pathName/$contentKey"
            val fm = NSFileManager.defaultManager
            val entries = fm.contentsOfDirectoryAtPath(root, null) ?: return@withContext
            for (raw in entries) {
                val name = raw as? String ?: continue
                // Preserve sidecar files (`_web_service.json`, `_data_type.txt`,
                // `_display_name.txt`, `_is_float.txt`). Everything else — zoom-level
                // dirs (`0`, `1`, ...), the `features/` dir — is tile payload and goes.
                if (name.startsWith("_")) continue
                fm.removeItemAtPath("$root/$name", null)
            }
        } catch (e: Throwable) {
            Logger.log(
                Logger.WARN,
                "IosContentManager.clearEntry('$contentKey') failed: ${e.message}",
            )
        }
    }

    override suspend fun deleteEntry(contentKey: String): Unit = withContext(Dispatchers.Default) {
        NSFileManager.defaultManager.removeItemAtPath("$pathName/$contentKey", null)
    }

    override suspend fun setDisplayName(contentKey: String, displayName: String): Unit = withContext(Dispatchers.Default) {
        ensureDirectory("$pathName/$contentKey")
        displayName.encodeToByteArray().toNsData()
            .writeToFile(displayNamePathFor(contentKey), atomically = true)
    }

    // ---------------- Data-type tracking ----------------

    private suspend fun rejectIfPreviouslyTypedAs(
        contentKey: String, requested: CacheEntry.DataType,
    ) {
        val existing = readDataType(contentKey) ?: return
        require(existing == requested) {
            "Content key '$contentKey' is already typed as $existing; cannot reopen as $requested"
        }
    }

    private suspend fun rememberDataType(
        contentKey: String, dataType: CacheEntry.DataType,
    ) = withContext(Dispatchers.Default) {
        ensureDirectory("$pathName/$contentKey")
        dataType.name.encodeToByteArray().toNsData()
            .writeToFile(dataTypePathFor(contentKey), atomically = true)
    }

    private suspend fun readDataType(
        contentKey: String,
    ): CacheEntry.DataType? = withContext(Dispatchers.Default) {
        val path = dataTypePathFor(contentKey)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return@withContext null
        val data = NSData.dataWithContentsOfFile(path) ?: return@withContext null
        val name = data.toByteArray().decodeToString().trim()
        runCatching { CacheEntry.DataType.valueOf(name) }.getOrNull()
    }

    /** Persist the user-visible name in a `_display_name.txt` sidecar. First-write-wins —
     *  re-opens never clobber an existing name (matches gpkg_contents.identifier's stability). */
    private suspend fun rememberDisplayName(
        contentKey: String, displayName: String,
    ) = withContext(Dispatchers.Default) {
        ensureDirectory("$pathName/$contentKey")
        val path = displayNamePathFor(contentKey)
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) return@withContext
        displayName.encodeToByteArray().toNsData().writeToFile(path, atomically = true)
    }

    private suspend fun readDisplayName(
        contentKey: String,
    ): String? = withContext(Dispatchers.Default) {
        val path = displayNamePathFor(contentKey)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return@withContext null
        val data = NSData.dataWithContentsOfFile(path) ?: return@withContext null
        data.toByteArray().decodeToString().trim().takeIf { it.isNotBlank() }
    }

    private fun displayNamePathFor(contentKey: String): String =
        "$pathName/$contentKey/_display_name.txt"

    private fun contentKeyLastModifiedMs(contentKey: String): Long? {
        val attrs = NSFileManager.defaultManager
            .attributesOfItemAtPath("$pathName/$contentKey", null) ?: return null
        val date = attrs[NSFileModificationDate] as? NSDate ?: return null
        return (date.timeIntervalSince1970 * 1000).toLong()
    }

    private fun metaPathFor(contentKey: String): String = "$pathName/$contentKey/_web_service.json"
    private fun dataTypePathFor(contentKey: String): String = "$pathName/$contentKey/_data_type.txt"

    private fun serviceTypeToDataType(type: String?): CacheEntry.DataType = when (type) {
        "WCS 1.0.0", "WCS 2.0.1" -> CacheEntry.DataType.COVERAGE
        "MVT" -> CacheEntry.DataType.VECTOR_TILES
        "WFS", "Shapefile", "OsmBuildings" -> CacheEntry.DataType.FEATURES
        "3DTiles" -> CacheEntry.DataType.OGC_3D_TILES
        else -> CacheEntry.DataType.TILES
    }

    /** [WebServiceInfo] isn't `@Serializable`; this surrogate is. */
    @Serializable
    private data class WebServiceInfoSurrogate(
        val type: String,
        val address: String,
        val layerName: String? = null,
        val outputFormat: String? = null,
        val metadata: String? = null,
        val isTransparent: Boolean = false,
    ) {
        fun toInfo() = WebServiceInfo(type, address, layerName, outputFormat, metadata, isTransparent)
        companion object {
            fun from(info: WebServiceInfo) = WebServiceInfoSurrogate(
                info.type, info.address, info.layerName, info.outputFormat, info.metadata, info.isTransparent,
            )
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = false }

        /** Default base under iOS app's Caches directory. */
        fun defaultCachesDirectory(): String {
            val caches = NSSearchPathForDirectoriesInDomains(
                NSCachesDirectory,
                NSUserDomainMask,
                expandTilde = true,
            )
            val root = caches.firstOrNull() as? String
                ?: error("Could not locate NSCachesDirectory")
            return "$root/worldwind-cache"
        }
    }
}
