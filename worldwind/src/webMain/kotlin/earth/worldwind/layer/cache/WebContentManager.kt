@file:OptIn(LowLevelCacheApi::class)

package earth.worldwind.layer.cache

import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.layer.source.TileSource
import earth.worldwind.util.ContentManager
import earth.worldwind.util.LevelSet
import earth.worldwind.util.js.jso
import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import org.w3c.dom.events.Event
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsAny
import kotlin.js.JsBoolean
import kotlin.js.JsString
import kotlin.js.js
import kotlin.js.toBoolean
import kotlin.js.toJsBoolean
import kotlin.js.toJsString
import kotlin.js.unsafeCast
import kotlin.time.Clock.System
import kotlin.time.Instant

/**
 * IndexedDB-backed [ContentManager] for the JS target. Implements every method of the
 * [ContentManager] contract — image / vector / coverage tile stores, feature stores
 * (bulk + tile-addressed), and web-service metadata.
 *
 * Schema (one IDB database per [WebContentManager], named [pathName]):
 *   * `image_tiles`, `vector_tiles`, `coverage_tiles` — tile blob stores keyed by
 *     `[contentKey, z, x, y]`. One store per data type covers every layer; no
 *     per-content-key store proliferation.
 *   * `features` — feature rows with auto-increment id; indexes `byTile`
 *     `[contentKey, z, x, y]` (tile-addressed reads) and `byContent` `contentKey`
 *     (bulk reads + per-content cleanup).
 *   * `metadata` — `WebServiceInfo` records keyed by content key. Walked by
 *     [listEntries].
 *
 * The cached-layer dispatcher (see `OpenCachedLayer.kt` in commonMain — reified `openLayer` / `openElevationCoverage` plus `CachedLayerRegistry`). The WFS arm currently depends on
 * `mil.nga.sf`). JS callers can still inspect [listEntries] / [findEntry]
 * and construct their own layers per service type.
 */
class WebContentManager(
    databaseName: String = DEFAULT_DATABASE_NAME,
) : ContentManager {

    override val pathName: String = databaseName
    override val isReadOnly: Boolean = false

    private var dbCache: IDBDatabase? = null
    private var closed: Boolean = false
    override val isClosed get() = closed
    private suspend fun db(): IDBDatabase {
        check(!closed) { "WebContentManager '$pathName' is closed" }
        return dbCache ?: openDatabase().also { dbCache = it }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        dbCache?.close()
        dbCache = null
    }

    private suspend fun openDatabase(): IDBDatabase = suspendCancellableCoroutine { cont ->
        val request = window.indexedDB.open(pathName, DB_VERSION)
        request.onupgradeneeded = { _: Event ->
            val db = request.result!!.unsafeCast<IDBDatabase>()
            for (store in TILE_STORES) {
                if (!db.objectStoreNames.contains(store)) db.createObjectStore(store)
            }
            if (!db.objectStoreNames.contains(IndexedDbFeatureStore.FEATURES_STORE)) {
                val store = db.createObjectStore(
                    IndexedDbFeatureStore.FEATURES_STORE, idbStoreOptionsKeyPath("id", autoIncrement = true),
                )
                store.createIndex(
                    IndexedDbFeatureStore.INDEX_BY_TILE,
                    jsStringArray("contentKey", "z", "x", "y"),
                    idbIndexOptions(unique = false),
                )
                store.createIndex(
                    IndexedDbFeatureStore.INDEX_BY_CONTENT,
                    "contentKey".toJsString(),
                    idbIndexOptions(unique = false),
                )
            }
            if (!db.objectStoreNames.contains(METADATA_STORE)) db.createObjectStore(METADATA_STORE)
            // Per-content-key data-type registry — keeps openXxxStore reads honest after a
            // wrong-type re-open attempt. Mirrors the GPKG `data_type` mismatch check.
            if (!db.objectStoreNames.contains(DATA_TYPE_STORE)) db.createObjectStore(DATA_TYPE_STORE)
            // Per-content-key user-visible name (parallel to GPKG's gpkg_contents.identifier).
            if (!db.objectStoreNames.contains(DISPLAY_NAME_STORE)) db.createObjectStore(DISPLAY_NAME_STORE)
            // Per-content-key storage layout for elevation coverages (parallel to GPKG's
            // gpkg_2d_gridded_coverage_ancillary.dataType — float vs int storage).
            if (!db.objectStoreNames.contains(IS_FLOAT_STORE)) db.createObjectStore(IS_FLOAT_STORE)
            // Per-tile (scale, offset) packing metadata for elevation coverages — read
            // by the codec's decode path to invert the Float→Uint16 packing. Keyed by
            // a `${contentKey}|${z}|${x}|${y}` string so we don't need a compound key.
            if (!db.objectStoreNames.contains(COVERAGE_ANCILLARY_STORE)) db.createObjectStore(COVERAGE_ANCILLARY_STORE)
        }
        request.onsuccess = { _: Event ->
            cont.resume(request.result!!.unsafeCast<IDBDatabase>())
        }
        request.onerror = { _: Event ->
            cont.resumeWithException(RuntimeException("IDB open '$pathName' failed: ${request.error?.message}"))
        }
        request.onblocked = { _: Event ->
            console.warn("IDB open '$pathName' blocked: older connection still open at lower version")
        }
    }

    /** Per-content size by data type: walks the right IDB object store via the same impls
     *  the public stores expose, so the result matches what the per-store [TileStore.sizeBytes]
     *  / [FeatureStore.sizeBytes] would report. */
    override suspend fun sizeBytesOf(contentKey: String): Long {
        val dataType = readDataType(contentKey) ?: return 0L
        return when (dataType) {
            CacheEntry.DataType.TILES ->
                IndexedDbTileStore(db(), contentKey, IMAGE_TILES_STORE, CachePolicy.UNBOUNDED).sizeBytes()
            CacheEntry.DataType.VECTOR_TILES ->
                IndexedDbTileStore(db(), contentKey, VECTOR_TILES_STORE, CachePolicy.UNBOUNDED).sizeBytes()
            CacheEntry.DataType.COVERAGE ->
                IndexedDbTileStore(db(), contentKey, COVERAGE_TILES_STORE, CachePolicy.UNBOUNDED).sizeBytes()
            CacheEntry.DataType.FEATURES ->
                IndexedDbFeatureStore(db(), contentKey, CachePolicy.UNBOUNDED).sizeBytes()
        }
    }

    override suspend fun contentSize(): Long = idbSerializationLock.withLock {
        val db = db()
        var total = 0L
        for (storeName in TILE_STORES) {
            val tx = db.transaction(storeName, "readonly")
            val store = tx.objectStore(storeName)
            val req = store.openCursor()
            idbWalkCursor(req) { cursor ->
                val bytes = cursor.value?.unsafeCast<IdbImageTileRecord>()?.bytesOrNull()
                if (bytes != null) total += bytes.length.toLong()
            }
            // Readonly transactions auto-commit at the end of the microtask once their
            // pending requests are drained — waiting for tx.oncomplete here races with that
            // auto-commit (the handler may be set up after the event already fired) and
            // can suspend the coroutine forever. We've already collected the data; don't
            // wait. Same pattern in the other read paths below.
        }
        total
    }

    /**
     * Most-recent `registerWebService` timestamp across all content keys. Per-content
     * timestamps live on the [WebServiceInfo] records and are surfaced via
     * [listEntries] / [findEntry].
     */
    override suspend fun lastModifiedDate(): Instant? {
        val db = db()
        val tx = db.transaction(METADATA_STORE, "readonly")
        val store = tx.objectStore(METADATA_STORE)
        var maxMs = 0L
        idbWalkCursor(store.openCursor()) { cursor ->
            val record = cursor.value?.unsafeCast<IdbWebServiceRecord>() ?: return@idbWalkCursor
            val ms = record.lastModifiedMs?.toLong() ?: 0L
            if (ms > maxMs) maxMs = ms
        }
        return if (maxMs > 0L) Instant.fromEpochMilliseconds(maxMs) else null
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
        return IndexedDbTileStore(db(), contentKey, IMAGE_TILES_STORE, cachePolicy)
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
        return IndexedDbTileStore(db(), contentKey, VECTOR_TILES_STORE, cachePolicy)
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
        // The tile blob store uses the existing coverage_tiles object store; transcoding
        // (TIFF-Float32 or TIFF-Uint16 + per-tile scale/offset) runs through the codec
        // and ancillary lives in the parallel coverage_ancillary object store.
        rejectIfPreviouslyTypedAs(contentKey, CacheEntry.DataType.COVERAGE)
        rememberDataType(contentKey, CacheEntry.DataType.COVERAGE)
        if (displayName != null) rememberDisplayName(contentKey, displayName)
        rememberIsFloat(contentKey, isFloat)
        val tileStore = IndexedDbTileStore(db(), contentKey, COVERAGE_TILES_STORE, cachePolicy)
            .also { if (!cachePolicy.isUnbounded) runCatching { it.evict() } }
        val backend = IndexedDbElevationBackend(
            db = db(),
            contentKey = contentKey,
            tileStore = tileStore,
            ancillaryStoreName = COVERAGE_ANCILLARY_STORE,
        )
        // Honor the stored isFloat on reopen — the user's value is a creation-time hint.
        val effectiveIsFloat = readIsFloat(contentKey) ?: isFloat
        return CachedElevationSourceFactory(
            backend = backend,
            networkSource = networkSource,
            networkDecoder = WebElevationNetworkDecoder,
            outputFormat = outputFormat,
            isFloat = effectiveIsFloat,
            tileMatrixSet = tileMatrixSet,
            staleAfter = cachePolicy.staleAfter,
        )
    }

    private suspend fun readIsFloat(contentKey: String): Boolean? = idbSerializationLock.withLock {
        runCatching {
            val tx = db().transaction(IS_FLOAT_STORE, "readonly")
            val store = tx.objectStore(IS_FLOAT_STORE)
            val req = store.get(contentKey.toJsString())
            suspendCancellableCoroutine<Boolean?> { cont ->
                req.onsuccess = { _: Event ->
                    val raw = req.result
                    cont.resume(if (raw == null) null else (raw as JsBoolean).toBoolean())
                }
                req.onerror = { _: Event ->
                    cont.resume(null)
                }
            }
        }.getOrNull()
    }

    /** First-write-wins, matching display-name semantics. The IDB row stays the canonical
     *  storage-layout choice for the coverage, same role as the gpkg's
     *  `gpkg_2d_gridded_coverage_ancillary.dataType` on JVM. */
    private suspend fun rememberIsFloat(contentKey: String, isFloat: Boolean): Unit = idbSerializationLock.withLock {
        runCatching {
            val db = db()
            val tx = db.transaction(IS_FLOAT_STORE, "readwrite")
            val store = tx.objectStore(IS_FLOAT_STORE)
            val getReq = store.get(contentKey.toJsString())
            suspendCancellableCoroutine<Unit> { cont ->
                getReq.onsuccess = { _: Event ->
                    if (getReq.result == null) {
                        val putReq = store.put(isFloat.toJsBoolean(), contentKey.toJsString())
                        putReq.onsuccess = { _: Event -> cont.resume(Unit) }
                        putReq.onerror = { _: Event -> cont.resume(Unit) }
                    } else {
                        cont.resume(Unit)
                    }
                }
                getReq.onerror = { _: Event -> cont.resume(Unit) }
            }
        }.onFailure { e ->
            console.warn("WebContentManager.rememberIsFloat('$contentKey') skipped: ${e.message}")
        }
    }

    override suspend fun openFeatureStore(
        contentKey: String,
        cachePolicy: CachePolicy,
        displayName: String?,
    ): FeatureStore {
        rejectIfPreviouslyTypedAs(contentKey, CacheEntry.DataType.FEATURES)
        rememberDataType(contentKey, CacheEntry.DataType.FEATURES)
        if (displayName != null) rememberDisplayName(contentKey, displayName)
        return IndexedDbFeatureStore(db(), contentKey, cachePolicy)
            .also { if (!cachePolicy.isUnbounded) runCatching { it.evict() } }
    }

    /** Throw if [contentKey] was previously opened under a different data type. Without
     *  this, JS could silently store the same key in two object stores and the next read
     *  would pick the wrong one. Matches GPKG `data_type` mismatch check.
     *
     *  Storage-side failures (missing object store from a stale upgrade, transient IDB
     *  error) are logged WARN and treated as "no recorded type" — the validation is a
     *  defense-in-depth check, not a hard requirement. The actual mismatch case still
     *  throws so the caller sees a clear error. */
    private suspend fun rejectIfPreviouslyTypedAs(
        contentKey: String, requested: CacheEntry.DataType,
    ) {
        val existing = readDataType(contentKey) ?: return
        require(existing == requested) {
            "Content key '$contentKey' is already typed as $existing; cannot reopen as $requested"
        }
    }

    private suspend fun rememberDataType(contentKey: String, dataType: CacheEntry.DataType) = idbSerializationLock.withLock {
        try {
            val db = db()
            val tx = db.transaction(DATA_TYPE_STORE, "readwrite")
            tx.objectStore(DATA_TYPE_STORE).put(dataType.name.toJsString(), contentKey.toJsString())
            idbAwaitTransaction(tx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            console.warn("WebContentManager.rememberDataType('$contentKey') skipped: ${e.message}")
        }
    }

    /** Persist the user-visible name. First-write-wins per content key — re-opens don't
     *  overwrite a name already on file (matches gpkg_contents.identifier's stability). */
    private suspend fun rememberDisplayName(contentKey: String, displayName: String) = idbSerializationLock.withLock {
        try {
            val db = db()
            val tx = db.transaction(DISPLAY_NAME_STORE, "readwrite")
            val store = tx.objectStore(DISPLAY_NAME_STORE)
            val existing = idbAwaitString(store.get(contentKey.toJsString()))
            if (existing.isNullOrBlank()) {
                store.put(displayName.toJsString(), contentKey.toJsString())
            }
            idbAwaitTransaction(tx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            console.warn("WebContentManager.rememberDisplayName('$contentKey') skipped: ${e.message}")
        }
    }

    private suspend fun readDisplayName(contentKey: String): String? = try {
        val db = db()
        val tx = db.transaction(DISPLAY_NAME_STORE, "readonly")
        val store = tx.objectStore(DISPLAY_NAME_STORE)
        idbAwaitString(store.get(contentKey.toJsString()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    private suspend fun readDataType(contentKey: String): CacheEntry.DataType? = try {
        val db = db()
        val tx = db.transaction(DATA_TYPE_STORE, "readonly")
        val store = tx.objectStore(DATA_TYPE_STORE)
        val name = idbAwaitString(store.get(contentKey.toJsString()))
        // No idbAwaitTransaction — readonly auto-commits.
        name?.let { runCatching { CacheEntry.DataType.valueOf(it) }.getOrNull() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        console.warn("WebContentManager.readDataType('$contentKey') skipped: ${e.message}")
        null
    }

    override suspend fun registerWebService(contentKey: String, info: WebServiceInfo) = idbSerializationLock.withLock {
        try {
            val db = db()
            val tx = db.transaction(METADATA_STORE, "readwrite")
            val store = tx.objectStore(METADATA_STORE)
            // Full metadata is stored regardless of size. The previous 256 KB cap existed
            // because IDB structured-cloning a multi-MB blob on the same connection could
            // stall concurrent tile reads indefinitely; that concurrency is now eliminated
            // by [idbSerializationLock], so structured-clone time is a bounded one-shot
            // cost paid during layer load.
            val record = newIdbWebServiceRecord(
                type = info.type,
                address = info.address,
                layerName = info.layerName,
                outputFormat = info.outputFormat,
                metadata = info.metadata,
                isTransparent = info.isTransparent,
                // Epoch millis as Double — IDB structured clone doesn't understand kotlin.Long.
                lastModifiedMs = System.now().toEpochMilliseconds().toDouble(),
            )
            store.put(record, contentKey.toJsString())
            idbAwaitTransaction(tx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            console.warn("WebContentManager.registerWebService('$contentKey') failed: ${e.message}")
        }
    }

    /** Read the persisted [WebServiceInfo] from IndexedDB. Private — public callers go
     *  through [findEntry] / [listEntries]. */
    private suspend fun loadWebServiceInfo(contentKey: String): WebServiceInfo? {
        val db = db()
        val tx = db.transaction(METADATA_STORE, "readonly")
        val store = tx.objectStore(METADATA_STORE)
        val record = idbAwait(store.get(contentKey.toJsString()))?.unsafeCast<IdbWebServiceRecord>() ?: return null
        // Readonly tx auto-commits; no idbAwaitTransaction — see contentSize() for why.
        return WebServiceInfo(
            type = record.type,
            address = record.address,
            layerName = record.layerName,
            outputFormat = record.outputFormat,
            metadata = record.metadata,
            isTransparent = record.isTransparent == true,
        )
    }

    override suspend fun listEntries(): List<CacheEntry> {
        // NOT wrapped in idbSerializationLock: the enrichment pass below calls readIsFloat(), which
        // takes the (non-reentrant) lock itself — wrapping here would deadlock. The metadata cursor
        // walk is read-only; the lock-serialized sub-reads provide the needed ordering. Same applies
        // to findEntry().
        // Cursor walk first (no suspending calls allowed inside idbWalkCursor's lambda);
        // then a follow-up pass to enrich each handle with the user-visible name from the
        // separate DISPLAY_NAME_STORE (suspending read).
        data class Raw(
            val key: String, val dataType: CacheEntry.DataType, val info: WebServiceInfo,
            val lastModified: Instant?,
        )
        val raws = mutableListOf<Raw>()
        run {
            val db = db()
            val tx = db.transaction(METADATA_STORE, "readonly")
            val store = tx.objectStore(METADATA_STORE)
            val req = store.openCursor()
            idbWalkCursor(req) { cursor ->
                // JsString.toString() is a real conversion on wasmJs; the JS compiler sees it as
                // redundant (JsString ≈ String there). Keep it for wasmJs; suppress the JS-only warning.
                @Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")
                val key = (cursor.key as? JsString)?.toString() ?: return@idbWalkCursor
                val record = cursor.value?.unsafeCast<IdbWebServiceRecord>() ?: return@idbWalkCursor
                val recordedDataType = record.dataType?.let {
                    runCatching { CacheEntry.DataType.valueOf(it) }.getOrNull()
                }
                val ms = record.lastModifiedMs?.toLong() ?: 0L
                raws += Raw(
                    key = key,
                    dataType = recordedDataType ?: serviceTypeToDataType(record.type),
                    info = WebServiceInfo(
                        type = record.type,
                        address = record.address,
                        layerName = record.layerName,
                        outputFormat = record.outputFormat,
                        metadata = record.metadata,
                        isTransparent = record.isTransparent == true,
                    ),
                    lastModified = if (ms > 0L) Instant.fromEpochMilliseconds(ms) else null,
                )
            }
        }
        return raws.map { raw ->
            CacheEntry(
                contentKey = raw.key,
                dataType = raw.dataType,
                service = raw.info,
                // boundingSector is per-content geo metadata not currently persisted on JS —
                // we don't have the geom-encoding primitives the GPKG path uses. Wave 2
                // gets us as far as the JVM/Android parity bar without that field.
                boundingSector = null,
                lastModified = raw.lastModified,
                displayName = readDisplayName(raw.key) ?: raw.key,
                isFloat = raw.dataType == CacheEntry.DataType.COVERAGE && readIsFloat(raw.key) == true,
            )
        }
    }

    override suspend fun findEntry(contentKey: String): CacheEntry? {
        val info = loadWebServiceInfo(contentKey) ?: return null
        val dataType = readDataType(contentKey) ?: serviceTypeToDataType(info.type)
        return CacheEntry(
            contentKey = contentKey,
            dataType = dataType,
            service = info,
            boundingSector = null,
            lastModified = null,
            displayName = readDisplayName(contentKey) ?: contentKey,
            isFloat = dataType == CacheEntry.DataType.COVERAGE && readIsFloat(contentKey) == true,
        )
    }

    private fun serviceTypeToDataType(type: String?): CacheEntry.DataType = when (type) {
        "WCS 1.0.0" -> CacheEntry.DataType.COVERAGE
        "MVT" -> CacheEntry.DataType.VECTOR_TILES
        "WFS", "Shapefile", "OsmBuildings" -> CacheEntry.DataType.FEATURES
        else -> CacheEntry.DataType.TILES
    }

    override suspend fun setDisplayName(contentKey: String, displayName: String) = idbSerializationLock.withLock {
        try {
            val db = db()
            val tx = db.transaction(DISPLAY_NAME_STORE, "readwrite")
            tx.objectStore(DISPLAY_NAME_STORE).put(displayName.toJsString(), contentKey.toJsString())
            idbAwaitTransaction(tx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            console.warn("WebContentManager.setDisplayName('$contentKey') failed: ${e.message}")
        }
    }

    override suspend fun clearEntry(contentKey: String) = idbSerializationLock.withLock {
        try {
            val db = db()
            // Drop tile blobs for this content key across every tile store. The metadata,
            // display-name, data-type, isFloat, and ancillary sidecars are preserved so
            // re-download repopulates without re-configuring the layer.
            for (storeName in TILE_STORES) {
                val tx = db.transaction(storeName, "readwrite")
                val store = tx.objectStore(storeName)
                val req = store.openCursor(boundFor(contentKey))
                idbWalkCursor(req) { cursor -> cursor.delete() }
                idbAwaitTransaction(tx)
            }
            // Drop coverage_ancillary rows (per-tile scale/offset) — they're stale once
            // the matching tile blobs are gone. Coverage-level isFloat in IS_FLOAT_STORE
            // is preserved.
            val ancTx = db.transaction(COVERAGE_ANCILLARY_STORE, "readwrite")
            val ancStore = ancTx.objectStore(COVERAGE_ANCILLARY_STORE)
            val ancReq = ancStore.openCursor(boundFor(contentKey))
            idbWalkCursor(ancReq) { cursor -> cursor.delete() }
            idbAwaitTransaction(ancTx)
            // Drop feature rows (bulk + per-tile) — they belong with the tile blobs.
            val featuresTx = db.transaction(IndexedDbFeatureStore.FEATURES_STORE, "readwrite")
            val featuresStore = featuresTx.objectStore(IndexedDbFeatureStore.FEATURES_STORE)
            val featuresReq = featuresStore.index(IndexedDbFeatureStore.INDEX_BY_CONTENT).openCursor(contentKey.toJsString())
            idbWalkCursor(featuresReq) { cursor -> cursor.delete() }
            idbAwaitTransaction(featuresTx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            console.warn("WebContentManager.clearEntry('$contentKey') failed: ${e.message}")
        }
    }

    override suspend fun deleteEntry(contentKey: String): Unit = idbSerializationLock.withLock {
        val db = db()
        for (storeName in TILE_STORES) {
            val tx = db.transaction(storeName, "readwrite")
            val store = tx.objectStore(storeName)
            val req = store.openCursor(boundFor(contentKey))
            idbWalkCursor(req) { cursor -> cursor.delete() }
            idbAwaitTransaction(tx)
        }
        // Drop bulk + tile-addressed feature rows for this content key.
        val featuresTx = db.transaction(IndexedDbFeatureStore.FEATURES_STORE, "readwrite")
        val featuresStore = featuresTx.objectStore(IndexedDbFeatureStore.FEATURES_STORE)
        val featuresReq = featuresStore.index(IndexedDbFeatureStore.INDEX_BY_CONTENT).openCursor(contentKey.toJsString())
        idbWalkCursor(featuresReq) { cursor -> cursor.delete() }
        idbAwaitTransaction(featuresTx)
        // Drop the registered web-service metadata.
        val metaTx = db.transaction(METADATA_STORE, "readwrite")
        metaTx.objectStore(METADATA_STORE).delete(contentKey.toJsString())
        idbAwaitTransaction(metaTx)
        // Drop the data-type registry entry so the key can be reused for a different type.
        val dtTx = db.transaction(DATA_TYPE_STORE, "readwrite")
        dtTx.objectStore(DATA_TYPE_STORE).delete(contentKey.toJsString())
        idbAwaitTransaction(dtTx)
        // Drop the user-visible name.
        runCatching {
            val nameTx = db.transaction(DISPLAY_NAME_STORE, "readwrite")
            nameTx.objectStore(DISPLAY_NAME_STORE).delete(contentKey.toJsString())
            idbAwaitTransaction(nameTx)
        }
    }

    companion object {
        const val DEFAULT_DATABASE_NAME = "worldwind-cache"

        // Bump only when adding object stores AFTER a release — onupgradeneeded fires
        // only on version bump. Pre-release we keep this at 1 and add stores freely;
        // the cache schema only freezes when the codebase ships.
        private const val DB_VERSION = 1
        private const val IMAGE_TILES_STORE = "image_tiles"
        private const val VECTOR_TILES_STORE = "vector_tiles"
        internal const val COVERAGE_TILES_STORE = "coverage_tiles"
        internal const val METADATA_STORE = "metadata"
        internal const val DATA_TYPE_STORE = "data_types"
        internal const val DISPLAY_NAME_STORE = "display_names"
        internal const val IS_FLOAT_STORE = "coverage_is_float"
        internal const val COVERAGE_ANCILLARY_STORE = "coverage_ancillary"
        private val TILE_STORES =
            arrayOf(IMAGE_TILES_STORE, VECTOR_TILES_STORE, COVERAGE_TILES_STORE)
    }
}

/**
 * Schema of the per-content web-service metadata row stored in [METADATA_STORE].
 * Written via [newIdbWebServiceRecord] from `registerWebService`; read by `loadWebServiceInfo`,
 * `listEntries`, and `lastModifiedDate`.
 *
 * [dataType] is read-only and may be set by code paths outside `registerWebService` (it is
 * legacy / advisory). All fields tolerate `null` because cross-version migrations may have
 * left older rows without the newer attributes.
 */
// Kotlin/Wasm has no `kotlin.js.console`; bind the global console as a typed external.
private external interface JsConsole : JsAny {
    fun warn(message: String)
}
private val console: JsConsole = js("console")

private external interface IdbWebServiceRecord : JsAny {
    var type: String
    var address: String
    var layerName: String?
    var outputFormat: String?
    var metadata: String?
    var isTransparent: Boolean?
    /** Epoch milliseconds — Double because IDB structured clone doesn't understand kotlin.Long. */
    var lastModifiedMs: Double?
    val dataType: String?
}

private fun newIdbWebServiceRecordObj(): IdbWebServiceRecord = jso()

private fun newIdbWebServiceRecord(
    type: String, address: String, layerName: String?, outputFormat: String?,
    metadata: String?, isTransparent: Boolean, lastModifiedMs: Double,
): IdbWebServiceRecord {
    val r = newIdbWebServiceRecordObj()
    r.type = type
    r.address = address
    r.layerName = layerName
    r.outputFormat = outputFormat
    r.metadata = metadata
    r.isTransparent = isTransparent
    r.lastModifiedMs = lastModifiedMs
    return r
}
