package earth.worldwind.layer.cache

import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.events.Event
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/**
 * Suspend-aware wrapper around the browser `IndexedDB` API. One database, one object store
 * (`features`); rows discriminate by [IdbFeatureRecord.contentKey] and are indexed on the
 * composite `(contentKey, z, x, y)` for per-tile lookups plus `contentKey` for bulk reads.
 */
internal class IdbFeatureStore private constructor(private val db: IDBDatabase) {

    suspend fun readByTile(contentKey: String, z: Int, x: Int, y: Int): List<IdbFeatureRecord> {
        val tx = db.transaction(STORE_NAME, "readonly")
        val index = tx.objectStore(STORE_NAME).index(INDEX_BY_TILE)
        val request = index.getAll(arrayOf<Any>(contentKey, z, x, y))
        return awaitRequest(request).unsafeCast<Array<IdbFeatureRecord>>().toList()
    }

    suspend fun readByContent(contentKey: String): List<IdbFeatureRecord> {
        val tx = db.transaction(STORE_NAME, "readonly")
        val index = tx.objectStore(STORE_NAME).index(INDEX_BY_CONTENT)
        val request = index.getAll(contentKey)
        return awaitRequest(request).unsafeCast<Array<IdbFeatureRecord>>().toList()
    }

    suspend fun deleteByTile(contentKey: String, z: Int, x: Int, y: Int) {
        val tx = db.transaction(STORE_NAME, "readwrite")
        val index = tx.objectStore(STORE_NAME).index(INDEX_BY_TILE)
        val cursorReq = index.openCursor(arrayOf<Any>(contentKey, z, x, y))
        awaitCursorDelete(cursorReq)
        awaitTransaction(tx)
    }

    suspend fun deleteByContent(contentKey: String) {
        val tx = db.transaction(STORE_NAME, "readwrite")
        val index = tx.objectStore(STORE_NAME).index(INDEX_BY_CONTENT)
        val cursorReq = index.openCursor(contentKey)
        awaitCursorDelete(cursorReq)
        awaitTransaction(tx)
    }

    suspend fun putAll(records: List<IdbFeatureRecord>) {
        if (records.isEmpty()) return
        val tx = db.transaction(STORE_NAME, "readwrite")
        val store = tx.objectStore(STORE_NAME)
        for (record in records) store.add(record)
        awaitTransaction(tx)
    }

    /** Total bytes of all `properties` strings under [contentKey]. Cheap approximation of size. */
    suspend fun contentSize(contentKey: String): Long {
        val rows = readByContent(contentKey)
        return rows.sumOf { (it.properties?.length ?: 0).toLong() }
    }

    /** Highest `lastModified` epoch-ms across all rows under [contentKey], or null if none. */
    suspend fun lastModified(contentKey: String): Double? {
        val rows = readByContent(contentKey)
        if (rows.isEmpty()) return null
        var max = 0.0
        for (r in rows) if (r.lastModified > max) max = r.lastModified
        return max
    }

    /**
     * Apply a [CacheEvictionPolicy] to rows under [contentKey]. Walks the by-content index in
     * one cursor pass, computes which records to drop (TTL-expired + over-cap by count and bytes),
     * and deletes them in a single readwrite transaction.
     */
    suspend fun evictByPolicy(contentKey: String, policy: CacheEvictionPolicy) {
        if (policy.isUnbounded) return
        val rows = readByContent(contentKey)
        if (rows.isEmpty()) return

        val now = js("Date.now()").unsafeCast<Double>()
        val maxAgeMs = if (policy.maxAge == Duration.INFINITE) Double.POSITIVE_INFINITY
        else policy.maxAge.inWholeMilliseconds.toDouble()
        val cutoff = now - maxAgeMs

        // Sort newest-first so the keep-set is easy to slice off the front.
        val sorted = rows.sortedByDescending { it.lastModified }
        val idsToDelete = mutableListOf<Int>()
        var kept = 0L
        var keptBytes = 0L
        for (r in sorted) {
            val id = r.id ?: continue
            val tooOld = r.lastModified < cutoff
            val overCount = kept >= policy.maxEntries
            val sz = (r.geometry?.length ?: 0) + (r.properties?.length ?: 0)
            val overBytes = keptBytes + sz > policy.maxBytes
            if (tooOld || overCount || overBytes) {
                idsToDelete += id
            } else {
                kept++
                keptBytes += sz
            }
        }
        if (idsToDelete.isEmpty()) return
        deleteByIds(idsToDelete)
    }

    private suspend fun deleteByIds(ids: List<Int>) {
        val tx = db.transaction(STORE_NAME, "readwrite")
        val store = tx.objectStore(STORE_NAME)
        for (id in ids) store.delete(id)
        awaitTransaction(tx)
    }

    /** Read the raw vector-tile blob (MVT protobuf) plus optional revalidation metadata. */
    suspend fun readVectorTileBlob(
        contentKey: String, z: Int, x: Int, y: Int,
    ): IdbVectorTileRecord? {
        val tx = db.transaction(VT_STORE_NAME, "readonly")
        val store = tx.objectStore(VT_STORE_NAME)
        val request = store.get(arrayOf<Any>(contentKey, z, x, y))
        val res = awaitRequest(request) ?: return null
        return res.unsafeCast<IdbVectorTileRecord?>()
    }

    /** Upsert a vector-tile blob row (composite key [contentKey, z, x, y]). */
    suspend fun writeVectorTileBlob(record: IdbVectorTileRecord) {
        val tx = db.transaction(VT_STORE_NAME, "readwrite")
        tx.objectStore(VT_STORE_NAME).put(record)
        awaitTransaction(tx)
    }

    /** Drop every vector-tile blob row for [contentKey]. Used on cache clear. */
    suspend fun deleteVectorTilesByContent(contentKey: String) {
        val tx = db.transaction(VT_STORE_NAME, "readwrite")
        val index = tx.objectStore(VT_STORE_NAME).index(VT_INDEX_BY_CONTENT)
        awaitCursorDelete(index.openCursor(contentKey))
        awaitTransaction(tx)
    }

    /** Total byte budget of all blob payloads under [contentKey]. Excludes overhead. */
    suspend fun vectorTileContentSize(contentKey: String): Long {
        val rows = readAllVectorTiles(contentKey)
        return rows.sumOf { it.bytes.length.toLong() }
    }

    /** Max fetchedAt epoch-ms across all rows under [contentKey], or null if none. */
    suspend fun vectorTileLastModified(contentKey: String): Double? {
        val rows = readAllVectorTiles(contentKey)
        if (rows.isEmpty()) return null
        var max = 0.0
        for (r in rows) if (r.fetchedAt > max) max = r.fetchedAt
        return max
    }

    /** Apply [CacheEvictionPolicy] to the vector-tile store under [contentKey]. */
    suspend fun evictVectorTilesByPolicy(contentKey: String, policy: CacheEvictionPolicy) {
        if (policy.isUnbounded) return
        val rows = readAllVectorTiles(contentKey)
        if (rows.isEmpty()) return

        val now = js("Date.now()").unsafeCast<Double>()
        val maxAgeMs = if (policy.maxAge == Duration.INFINITE) Double.POSITIVE_INFINITY
        else policy.maxAge.inWholeMilliseconds.toDouble()
        val cutoff = now - maxAgeMs

        val sorted = rows.sortedByDescending { it.fetchedAt }
        val toDelete = mutableListOf<Array<Any>>()
        var kept = 0L
        var keptBytes = 0L
        for (r in sorted) {
            val tooOld = r.fetchedAt < cutoff
            val overCount = kept >= policy.maxEntries
            val sz = r.bytes.length.toLong()
            val overBytes = keptBytes + sz > policy.maxBytes
            if (tooOld || overCount || overBytes) {
                toDelete += arrayOf<Any>(r.contentKey, r.z, r.x, r.y)
            } else {
                kept++
                keptBytes += sz
            }
        }
        if (toDelete.isEmpty()) return
        val tx = db.transaction(VT_STORE_NAME, "readwrite")
        val store = tx.objectStore(VT_STORE_NAME)
        for (key in toDelete) store.delete(key)
        awaitTransaction(tx)
    }

    private suspend fun readAllVectorTiles(contentKey: String): List<IdbVectorTileRecord> {
        val tx = db.transaction(VT_STORE_NAME, "readonly")
        val index = tx.objectStore(VT_STORE_NAME).index(VT_INDEX_BY_CONTENT)
        val request = index.getAll(contentKey)
        return awaitRequest(request).unsafeCast<Array<IdbVectorTileRecord>>().toList()
    }

    companion object {
        private const val DB_NAME = "worldwind-cache"
        private const val DB_VERSION = 2
        internal const val STORE_NAME = "features"
        private const val INDEX_BY_TILE = "byTile"
        private const val INDEX_BY_CONTENT = "byContent"
        internal const val VT_STORE_NAME = "vector-tiles"
        private const val VT_INDEX_BY_CONTENT = "byContent"

        suspend fun open(): IdbFeatureStore = suspendCancellableCoroutine { cont ->
            val request = window.indexedDB.open(DB_NAME, DB_VERSION)
            request.onupgradeneeded = { _: Event ->
                val db = request.result.unsafeCast<IDBDatabase>()
                if (!db.objectStoreNames.contains(STORE_NAME)) {
                    val params = js("({keyPath: 'id', autoIncrement: true})")
                    val store = db.createObjectStore(STORE_NAME, params)
                    val tileKeyPath = arrayOf("contentKey", "z", "x", "y")
                    store.createIndex(INDEX_BY_TILE, tileKeyPath, js("({unique: false})"))
                    store.createIndex(INDEX_BY_CONTENT, "contentKey", js("({unique: false})"))
                }
                if (!db.objectStoreNames.contains(VT_STORE_NAME)) {
                    // Composite-key store: keyPath itself is [contentKey, z, x, y] so reads /
                    // upserts are O(1) without a synthetic id column.
                    val params = js("({keyPath: ['contentKey', 'z', 'x', 'y']})")
                    val store = db.createObjectStore(VT_STORE_NAME, params)
                    store.createIndex(VT_INDEX_BY_CONTENT, "contentKey", js("({unique: false})"))
                }
            }
            request.onsuccess = { _: Event ->
                cont.resume(IdbFeatureStore(request.result.unsafeCast<IDBDatabase>()))
            }
            request.onerror = { _: Event ->
                cont.resumeWithException(RuntimeException("IndexedDB open failed: ${request.error?.message}"))
            }
        }

        private suspend fun awaitRequest(request: IDBRequest): dynamic = suspendCancellableCoroutine { cont ->
            request.onsuccess = { _: Event -> cont.resume(request.result) }
            request.onerror = { _: Event ->
                cont.resumeWithException(RuntimeException("IDB request failed: ${request.error?.message}"))
            }
        }

        private suspend fun awaitTransaction(tx: IDBTransaction): Unit = suspendCancellableCoroutine { cont ->
            tx.oncomplete = { _: Event -> cont.resume(Unit) }
            tx.onerror = { _: Event ->
                cont.resumeWithException(RuntimeException("IDB transaction failed: ${tx.error?.message}"))
            }
            tx.onabort = { _: Event ->
                cont.resumeWithException(RuntimeException("IDB transaction aborted"))
            }
            // Explicit commit while tx is still "active" — Chromium's IDB engine can otherwise
            // sit on the tx indefinitely waiting for the implicit "no-pending-requests + control-
            // returned" trigger, which never fires under concurrent IDB pressure. tx.commit()
            // is IDB v3 (Chrome 76+); runCatching covers older browsers where it doesn't exist.
            runCatching { tx.commit() }
        }

        /** Iterate every cursor entry and delete it. Used by tile/content range deletes. */
        private suspend fun awaitCursorDelete(cursorReq: IDBRequest): Unit = suspendCancellableCoroutine { cont ->
            cursorReq.onsuccess = { _: Event ->
                val cursor = cursorReq.result.unsafeCast<IDBCursor?>()
                if (cursor == null) {
                    cont.resume(Unit)
                } else {
                    cursor.delete()
                    cursor.`continue`()
                }
            }
            cursorReq.onerror = { _: Event ->
                cont.resumeWithException(RuntimeException("IDB cursor failed: ${cursorReq.error?.message}"))
            }
        }
    }
}

/** One row. [geometry] / [properties] are JSON strings; the store doesn't parse them. */
internal external interface IdbFeatureRecord {
    var id: Int?
    var contentKey: String
    var z: Int?
    var x: Int?
    var y: Int?
    var geometry: String?
    var properties: String?
    var lastModified: Double
}

internal fun newIdbFeatureRecord(
    contentKey: String, z: Int?, x: Int?, y: Int?,
    geometry: String?, properties: String?, lastModified: Double,
): IdbFeatureRecord {
    val r = js("({})").unsafeCast<IdbFeatureRecord>()
    r.contentKey = contentKey
    r.z = z; r.x = x; r.y = y
    r.geometry = geometry
    r.properties = properties
    r.lastModified = lastModified
    return r
}

/**
 * One cached vector tile (MVT protobuf). [bytes] is a `Uint8Array` — IndexedDB stores it
 * natively without JSON round-tripping. Composite primary key `[contentKey, z, x, y]`.
 * [etag] / [lastModified] echo the server's HTTP revalidation headers when known.
 */
internal external interface IdbVectorTileRecord {
    var contentKey: String
    var z: Int
    var x: Int
    var y: Int
    var bytes: org.khronos.webgl.Uint8Array
    var etag: String?
    var lastModified: String?
    var fetchedAt: Double
}

internal fun newIdbVectorTileRecord(
    contentKey: String, z: Int, x: Int, y: Int,
    bytes: ByteArray, etag: String?, lastModified: String?, fetchedAt: Double,
): IdbVectorTileRecord {
    val r = js("({})").unsafeCast<IdbVectorTileRecord>()
    r.contentKey = contentKey
    r.z = z; r.x = x; r.y = y
    r.bytes = bytes.toUint8Array()
    r.etag = etag
    r.lastModified = lastModified
    r.fetchedAt = fetchedAt
    return r
}

internal fun ByteArray.toUint8Array(): org.khronos.webgl.Uint8Array {
    // Kotlin/JS backs `ByteArray` with a native `Int8Array`. `Uint8Array` and `Int8Array`
    // are bit-compatible so we hand IDB a view over the same buffer instead of copying
    // byte-by-byte through `asDynamic()`. A 50 KB tile-decode loop is O(N) but each
    // `asDynamic()` access is a JS dynamic dispatch — orders of magnitude slower than the
    // view-construction here.
    val int8 = this.unsafeCast<org.khronos.webgl.Int8Array>()
    return org.khronos.webgl.Uint8Array(int8.buffer, int8.byteOffset, int8.length)
}

internal fun org.khronos.webgl.Uint8Array.toByteArray(): ByteArray {
    // Same zero-copy trick in reverse — wrap the underlying buffer with an `Int8Array`
    // view and cast to `ByteArray` (which IS an `Int8Array` on Kotlin/JS). Element-wise
    // copying here previously dominated cache-hit latency: with a populated cache every
    // visible tile (~100 per frame) went through `asDynamic()` element access in a tight
    // loop, hanging the browser on page reload.
    return org.khronos.webgl.Int8Array(this.buffer, this.byteOffset, this.length).unsafeCast<ByteArray>()
}

