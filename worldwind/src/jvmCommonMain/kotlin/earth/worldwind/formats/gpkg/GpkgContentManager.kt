@file:OptIn(earth.worldwind.layer.cache.LowLevelCacheApi::class)

package earth.worldwind.formats.gpkg

import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.layer.cache.CacheEvictionPolicy
import earth.worldwind.layer.cache.CacheEntry
import earth.worldwind.layer.cache.FeatureStore
import earth.worldwind.layer.cache.GpkgFeatureStore
import earth.worldwind.layer.cache.GpkgTileStore
import earth.worldwind.layer.source.TileSource
import earth.worldwind.layer.cache.TileStore
import earth.worldwind.layer.cache.WebServiceInfo
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.formats.gpkg.GeoPackage
import earth.worldwind.formats.gpkg.GeoPackage.Companion.COVERAGE
import earth.worldwind.formats.gpkg.GeoPackage.Companion.EPSG_3857
import earth.worldwind.formats.gpkg.GeoPackage.Companion.FEATURES
import earth.worldwind.formats.gpkg.GeoPackage.Companion.FLOAT
import earth.worldwind.formats.gpkg.GeoPackage.Companion.INTEGER
import earth.worldwind.formats.gpkg.GeoPackage.Companion.TILES
import earth.worldwind.formats.gpkg.GeoPackage.Companion.VECTOR_TILES
import earth.worldwind.formats.gpkg.GpkgContent
import earth.worldwind.util.ContentManager
import earth.worldwind.util.LevelSet
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import mil.nga.geopackage.extension.WebPExtension
import mil.nga.geopackage.extension.im.vector_tiles.VectorTilesMapboxExtension
import mil.nga.geopackage.tiles.user.TileTable
import java.io.File
import kotlin.time.Instant

/**
 * GeoPackage-backed [ContentManager]. One instance binds to one `.gpkg` file. Stores
 * stay narrow — they read/write the cache; the manager is the orchestration layer that
 * opens stores, registers web-service metadata, and enumerates what's cached.
 *
 * The previous layer-typed API (`getFeatureLayers`, `setupFeatureLayerCache`, etc.) was
 * removed in the 3.0 redesign. Callers now build sources + layers themselves over the
 * stores this manager hands out.
 */
class GpkgContentManager(
    override val pathName: String,
    override val isReadOnly: Boolean = false,
) : ContentManager {

    init {
        // Ensure the parent directory exists before the GeoPackage open. NGA's GeoPackage
        // library fails the SQLite open with "path to '...' does not exist" rather than
        // creating intermediate directories — so we do it here. Skipped when the GPKG is
        // opened read-only (in which case the parent must already exist; caller intent is
        // clearly "open an existing file").
        if (!isReadOnly) File(pathName).parentFile?.mkdirs()
    }

    internal val geoPackage = GeoPackage(pathName, isReadOnly)

    /**
     * All SQLite work is dispatched on this scope so [close] can cancel in-flight reads
     * before the underlying handle is shut down. Without this coordination, a wrapper
     * lookup that blocks on the IO dispatcher can resume *after* the gpkg is closed and
     * throw "attempt to re-open an already-closed object" deep in SQLite.
     *
     * [SupervisorJob] so one read failing doesn't cancel siblings.
     */
    private val managerJob = SupervisorJob()
    private val managerScope =
        CoroutineScope(managerJob + Dispatchers.IO + CoroutineName("GpkgContentManager:$pathName"))

    override val isClosed get() = !managerJob.isActive

    override suspend fun close() {
        if (!managerJob.isActive) return
        // Cancel every in-flight read/write and wait for them to unwind. Children launched
        // on [managerScope] observe the cancellation cooperatively; the [runScoped] /
        // [runScopedOrThrow] helpers translate the resulting CancellationException into a
        // "closed" return / error for the caller.
        managerJob.cancelAndJoin()
        // NonCancellable so the actual SQLite shutdown isn't interrupted by *our* caller's
        // scope getting cancelled mid-close. By this point all reads are quiesced so the
        // shutdown call itself is the last thing touching the handle.
        withContext(NonCancellable + Dispatchers.IO) {
            if (!geoPackage.isShutdown) geoPackage.shutdown()
        }
    }

    /** Run [block] on the manager's scope. If the manager closes (scope cancelled) while
     *  the read is in flight, the caller sees [default] instead of CancellationException —
     *  appropriate for "metadata fetch returned nothing because the file is gone".
     *  Genuine caller-driven cancellation (the *caller's* coroutine context cancelled) is
     *  re-thrown so cooperative cancellation works normally. */
    private suspend fun <T> runScoped(default: T, block: suspend CoroutineScope.() -> T): T {
        if (!managerJob.isActive) return default
        val deferred = managerScope.async(block = block)
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            // Caller's scope was cancelled while we awaited → propagate (cooperative).
            coroutineContext.ensureActive()
            // Manager scope was cancelled (close in progress) → benign "closed" condition.
            default
        }
    }

    /** [runScoped] variant for operations where there's no sensible default (store-opening
     *  factory methods). Throws [IllegalStateException] when the manager is already closed
     *  or closed mid-operation. */
    private suspend fun <T> runScopedOrThrow(block: suspend CoroutineScope.() -> T): T {
        check(managerJob.isActive) { "GpkgContentManager($pathName) is closed" }
        val deferred = managerScope.async(block = block)
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            coroutineContext.ensureActive()
            throw IllegalStateException("GpkgContentManager($pathName) closed during operation", e)
        }
    }

    // Pure file I/O — not gated on SQLite lifecycle, so it stays on Dispatchers.IO directly
    // and continues to work for callers polling file size after close (rare but harmless).
    override suspend fun contentSize(): Long = withContext(Dispatchers.IO) { File(pathName).length() }

    override suspend fun sizeBytesOf(contentKey: String): Long = runScoped(default = 0L) {
        val content = geoPackage.getContent(contentKey) ?: return@runScoped 0L
        when (content.dataTypeName.lowercase()) {
            TILES, VECTOR_TILES, COVERAGE -> geoPackage.readTilesDataSize(content.tableName)
            FEATURES -> geoPackage.readFeaturesDataSize(content.tableName)
            else -> 0L
        }
    }

    override suspend fun lastModifiedDate(): Instant? = withContext(Dispatchers.IO) {
        val file = File(pathName)
        if (file.exists()) Instant.fromEpochMilliseconds(file.lastModified()) else null
    }

    // --- Tile stores ----------------------------------------------------------------

    override suspend fun openImageTileStore(
        contentKey: String,
        levelSet: LevelSet,
        imageFormat: String,
        isTransparent: Boolean,
        evictionPolicy: CacheEvictionPolicy,
        displayName: String?,
    ): TileStore = runScopedOrThrow {
        val content = openOrCreateTileContent(
            contentKey, TILES, levelSet, imageFormat = imageFormat, displayName = displayName,
        )
        GpkgTileStore(geoPackage, content, evictionPolicy).also {
            if (!evictionPolicy.isUnbounded) runCatching { it.evict() }
        }
    }

    override suspend fun openVectorTileStore(
        contentKey: String,
        levelSet: LevelSet,
        evictionPolicy: CacheEvictionPolicy,
        displayName: String?,
    ): TileStore = runScopedOrThrow {
        val content = geoPackage.getContent(contentKey)?.also {
            // Pre-eviction vector-tile tables may not have last_modified yet; migrate on reopen.
            if (!geoPackage.isReadOnly) geoPackage.ensureLastModifiedColumn(it.tableName)
        } ?: geoPackage.setupVectorTilesContent(
            tableName = contentKey,
            levelSet = levelSet,
            displayName = displayName,
            encoding = VectorTilesMapboxExtension(geoPackage.core),
        )
        require(content.dataTypeName.equals(VECTOR_TILES, ignoreCase = true)) {
            "Content '$contentKey' is not a vector-tiles table (was '${content.dataTypeName}')"
        }
        GpkgTileStore(geoPackage, content, evictionPolicy).also {
            if (!evictionPolicy.isUnbounded) runCatching { it.evict() }
        }
    }

    override suspend fun createElevationSourceFactory(
        contentKey: String,
        tileMatrixSet: TileMatrixSet,
        networkSource: TileSource?,
        outputFormat: String,
        isFloat: Boolean,
        evictionPolicy: CacheEvictionPolicy,
        displayName: String?,
    ): ElevationSourceFactory = runScopedOrThrow {
        val content = openOrCreateCoverageContent(contentKey, tileMatrixSet, isFloat, displayName)
        // Honor the *stored* dataType — see openOrCreateCoverageContent's reconciliation:
        // the caller's [isFloat] is a creation-time hint that loses to whatever's persisted.
        val effectiveIsFloat = when (geoPackage.getGriddedCoverage(content)?.dataType) {
            FLOAT -> true
            INTEGER -> false
            else -> isFloat
        }
        if (!evictionPolicy.isUnbounded) runCatching { geoPackage.evictTiles(content, evictionPolicy) }
        GpkgCachedElevationSourceFactory(
            geoPackage = geoPackage,
            content = content,
            networkSource = networkSource,
            outputFormat = outputFormat,
            isFloat = effectiveIsFloat,
            tileMatrixSet = tileMatrixSet,
        )
    }

    /**
     * Open the existing coverage content row (validating sector + reconciling storage
     * layout) or create it on first use. Shared between [openCoverageStore] and
     * [createElevationSourceFactory].
     */
    private suspend fun openOrCreateCoverageContent(
        contentKey: String,
        tileMatrixSet: TileMatrixSet,
        isFloat: Boolean,
        displayName: String?,
    ): GpkgContent = geoPackage.getContent(contentKey)?.also { existing ->
        require(existing.dataTypeName.equals(COVERAGE, ignoreCase = true)) {
            "Content '$contentKey' is not a coverage table (was '${existing.dataTypeName}')"
        }
        val current = geoPackage.buildTileMatrixSet(existing)
        require(current.sector.equals(tileMatrixSet.sector, TOLERANCE)) {
            "Coverage '$contentKey' sector mismatch — opened ${current.sector} vs requested ${tileMatrixSet.sector}"
        }
        // Storage-layout reconciliation on reopen. gpkg's stored dataType is the source of
        // truth — it describes what the on-disk bytes actually are. A caller value that
        // disagrees is logged as WARN, not thrown: a failed open here cascades to a failed
        // coverage and "terrain at 0 altitude" across the pyramid, far worse than a logged
        // mismatch. The stored value still wins; callers can resync by reading
        // findEntry(contentKey)?.metadata.
        val storedType = geoPackage.getGriddedCoverage(existing)?.dataType
        val expected = if (isFloat) FLOAT else INTEGER
        if (storedType != null && storedType != expected) {
            logMessage(
                WARN, "GpkgContentManager", "openOrCreateCoverageContent",
                "Coverage '$contentKey' storage layout mismatch — gpkg stores '$storedType' but " +
                    "caller requested '$expected'. Honoring the stored value; read it back via " +
                    "ContentManager.findEntry(key)?.isFloat."
            )
        }
        if (!geoPackage.isReadOnly && current.entries.size < tileMatrixSet.entries.size) {
            geoPackage.setupTileMatrices(existing, tileMatrixSet)
        }
        if (!geoPackage.isReadOnly) geoPackage.ensureLastModifiedColumn(existing.tableName)
    } ?: geoPackage.setupGriddedCoverageContent(
        tableName = contentKey,
        tileMatrixSet = tileMatrixSet,
        displayName = displayName,
        isFloat = isFloat,
    )

    // --- Feature store --------------------------------------------------------------

    override suspend fun openFeatureStore(
        contentKey: String,
        evictionPolicy: CacheEvictionPolicy,
        displayName: String?,
    ): FeatureStore = runScopedOrThrow {
        val content = geoPackage.getContent(contentKey)?.also { existing ->
            require(existing.dataTypeName.equals(FEATURES, ignoreCase = true)) {
                "Content '$contentKey' is not a features table (was '${existing.dataTypeName}')"
            }
        } ?: geoPackage.setupFeaturesContent(contentKey, displayName = displayName)
        GpkgFeatureStore(geoPackage, content, evictionPolicy).also {
            if (!evictionPolicy.isUnbounded) runCatching { it.evict() }
        }
    }

    // --- Web-service registry -------------------------------------------------------

    override suspend fun registerWebService(contentKey: String, info: WebServiceInfo): Unit =
        runScoped(default = Unit) {
            // Read-only reopen: the metadata is already on disk; never attempt to persist it.
            if (geoPackage.isReadOnly) return@runScoped
            try {
                geoPackage.setupWebService(
                    tableName = contentKey,
                    type = info.type,
                    address = info.address,
                    layerName = info.layerName,
                    outputFormat = info.outputFormat,
                    metadata = info.metadata,
                    isTransparent = info.isTransparent,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logMessage(
                    WARN, "GpkgContentManager", "registerWebService",
                    "Failed to persist web-service metadata for '$contentKey' " +
                        "(${e::class.simpleName}: ${e.message}); layer wiring is still valid in-memory."
                )
            }
        }

    // --- Enumeration ----------------------------------------------------------------

    override suspend fun listEntries(): List<CacheEntry> = runScoped(default = emptyList()) {
        val all = mutableListOf<CacheEntry>()
        for (dataType in listOf(TILES, VECTOR_TILES, COVERAGE, FEATURES)) {
            for (content in geoPackage.getContent(dataType, null)) {
                all += content.toHandle()
            }
        }
        all
    }

    override suspend fun findEntry(contentKey: String): CacheEntry? =
        runScoped<CacheEntry?>(default = null) {
            geoPackage.getContent(contentKey)?.toHandle()
        }

    override suspend fun tryRecoverLevelSet(contentKey: String): LevelSet? =
        runScoped<LevelSet?>(default = null) {
            val content = geoPackage.getContent(contentKey) ?: return@runScoped null
            runCatching {
                val config = geoPackage.buildLevelSetConfig(content)
                if (content.srs?.organizationCoordsysId == EPSG_3857) {
                    LevelSet(
                        sector = MercatorSector.fromSector(config.sector),
                        tileOrigin = MercatorSector.fromSector(config.tileOrigin),
                        firstLevelDelta = config.firstLevelDelta,
                        numLevels = config.numLevels,
                        tileWidth = config.tileWidth,
                        tileHeight = config.tileHeight,
                        levelOffset = config.levelOffset,
                        firstLevelNumber = config.firstLevelNumber,
                    )
                } else {
                    LevelSet(config)
                }
            }.getOrNull()
        }

    override suspend fun tryRecoverTileMatrixSet(contentKey: String): TileMatrixSet? =
        runScoped<TileMatrixSet?>(default = null) {
            val content = geoPackage.getContent(contentKey) ?: return@runScoped null
            runCatching { geoPackage.buildTileMatrixSet(content) }.getOrNull()
        }

    override suspend fun tryOpenNativeContent(entry: CacheEntry): Any? =
        runScoped<Any?>(default = null) { openGpkgNative(entry) }

    override suspend fun clearEntry(contentKey: String): Unit = runScoped(default = Unit) {
        runCatching { geoPackage.clearEntry(contentKey) }.onFailure {
            logMessage(
                WARN, "GpkgContentManager", "clearEntry",
                "clearEntry('$contentKey') failed: ${it.message}",
            )
        }
    }

    override suspend fun deleteEntry(contentKey: String): Unit = runScoped(default = Unit) {
        runCatching { geoPackage.deleteEntry(contentKey) }.onFailure {
            logMessage(WARN, "GpkgContentManager", "deleteEntry",
                "deleteEntry('$contentKey') failed: ${it.message}")
        }
    }

    override suspend fun setDisplayName(contentKey: String, displayName: String): Unit = runScoped(default = Unit) {
        runCatching { geoPackage.setDisplayName(contentKey, displayName) }.onFailure {
            logMessage(WARN, "GpkgContentManager", "setDisplayName",
                "setDisplayName('$contentKey') failed: ${it.message}")
        }
    }

    // --- Internal helpers -----------------------------------------------------------

    /** Open-or-create the image-tile pyramid. Validates schema compat on re-open. */
    private suspend fun openOrCreateTileContent(
        contentKey: String,
        dataType: String,
        levelSet: LevelSet,
        imageFormat: String,
        displayName: String?,
    ): GpkgContent {
        val existing = geoPackage.getContent(contentKey)
        if (existing != null) {
            require(existing.dataTypeName.equals(dataType, ignoreCase = true)) {
                "Content '$contentKey' is not a $dataType table (was '${existing.dataTypeName}')"
            }
            val current = geoPackage.buildLevelSetConfig(existing)
            require(current.tileWidth == levelSet.tileWidth && current.tileHeight == levelSet.tileHeight) {
                "Tile size mismatch for '$contentKey'"
            }
            require(current.tileOrigin.equals(levelSet.tileOrigin, TOLERANCE)) {
                "Tile origin mismatch for '$contentKey'"
            }
            require(current.firstLevelNumber <= levelSet.firstLevel.levelNumber) {
                "First level number mismatch for '$contentKey'"
            }
            val divider = 1 shl (levelSet.firstLevel.levelNumber - current.firstLevelNumber)
            val firstLevelDelta = Location(
                current.firstLevelDelta.latitude / divider,
                current.firstLevelDelta.longitude / divider,
            )
            require(firstLevelDelta.equals(levelSet.firstLevelDelta, TOLERANCE)) {
                "First level delta mismatch for '$contentKey'"
            }
            if (imageFormat.equals("image/webp", ignoreCase = true)) {
                requireNotNull(geoPackage.getExtension(contentKey, TileTable.COLUMN_TILE_DATA, WebPExtension.EXTENSION_NAME)) {
                    "WEBP extension missing on existing content '$contentKey'"
                }
            }
            if (!geoPackage.isReadOnly && current.numLevels < levelSet.numLevels) {
                geoPackage.setupTileMatrices(existing, levelSet)
            }
            // Reopen of a read-only GeoPackage must never write — skip the metadata sync.
            if (!geoPackage.isReadOnly) {
                geoPackage.updateTilesContent(contentKey, levelSet, displayName = existing.identifier, content = existing)
                // Pre-eviction tile tables may not have last_modified yet; migrate on reopen.
                geoPackage.ensureLastModifiedColumn(existing.tableName)
            }
            return existing
        }
        return geoPackage.setupTilesContent(
            tableName = contentKey,
            levelSet = levelSet,
            displayName = displayName,
            imageFormat = imageFormat,
        )
    }

    private suspend fun GpkgContent.toHandle(): CacheEntry {
        val service = runCatching { geoPackage.getWebService(this) }.getOrNull()
        val webInfo = service?.let {
            WebServiceInfo(
                type = it.type,
                address = it.address,
                layerName = it.layerName,
                outputFormat = it.outputFormat,
                metadata = it.metadata,
                isTransparent = it.isTransparent,
            )
        }
        val sector: Sector? = runCatching { geoPackage.getBoundingSector(this) }.getOrNull()
        val dataType = when (dataTypeName.lowercase()) {
            TILES -> CacheEntry.DataType.TILES
            VECTOR_TILES -> CacheEntry.DataType.VECTOR_TILES
            COVERAGE -> CacheEntry.DataType.COVERAGE
            FEATURES -> CacheEntry.DataType.FEATURES
            else -> error("Unknown data_type '$dataTypeName' for content '$tableName'")
        }
        val isFloat = dataType == CacheEntry.DataType.COVERAGE &&
            geoPackage.getGriddedCoverage(this)?.dataType == FLOAT
        return CacheEntry(
            contentKey = tableName,
            dataType = dataType,
            service = webInfo,
            boundingSector = sector,
            lastModified = lastChange?.let { Instant.fromEpochMilliseconds(it.time) },
            displayName = identifier ?: tableName,
            isFloat = isFloat,
        )
    }

    companion object {
        private const val TOLERANCE = 1e-6
    }
}
