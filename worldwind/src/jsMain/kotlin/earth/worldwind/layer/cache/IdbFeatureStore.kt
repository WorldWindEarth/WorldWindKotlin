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

    companion object {
        private const val DB_NAME = "worldwind-cache"
        private const val DB_VERSION = 1
        internal const val STORE_NAME = "features"
        private const val INDEX_BY_TILE = "byTile"
        private const val INDEX_BY_CONTENT = "byContent"

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

// ---- Minimal external declarations for the IDB API surface this file uses. ----

internal external interface IDBFactory {
    fun open(name: String, version: Int): IDBOpenDBRequest
}

internal external interface IDBOpenDBRequest : IDBRequest {
    var onupgradeneeded: ((Event) -> Unit)?
}

internal external interface IDBRequest {
    val result: Any?
    val error: dynamic
    var onsuccess: ((Event) -> Unit)?
    var onerror: ((Event) -> Unit)?
}

internal external interface IDBDatabase {
    val objectStoreNames: DOMStringList
    fun createObjectStore(name: String, options: dynamic = definedExternally): IDBObjectStore
    fun transaction(storeNames: String, mode: String = definedExternally): IDBTransaction
}

internal external interface DOMStringList {
    val length: Int
    fun contains(name: String): Boolean
}

internal external interface IDBTransaction {
    val error: dynamic
    var oncomplete: ((Event) -> Unit)?
    var onerror: ((Event) -> Unit)?
    var onabort: ((Event) -> Unit)?
    fun objectStore(name: String): IDBObjectStore
}

internal external interface IDBObjectStore {
    fun add(value: Any?): IDBRequest
    fun put(value: Any?): IDBRequest
    fun delete(key: Any?): IDBRequest
    fun clear(): IDBRequest
    fun createIndex(name: String, keyPath: Any, options: dynamic = definedExternally): IDBIndex
    fun index(name: String): IDBIndex
}

internal external interface IDBIndex {
    fun getAll(query: Any? = definedExternally): IDBRequest
    fun openCursor(query: Any? = definedExternally): IDBRequest
}

internal external interface IDBCursor {
    val key: Any?
    fun delete(): IDBRequest
    fun `continue`()
}

private external interface WindowWithIdb {
    val indexedDB: IDBFactory
}

internal val org.w3c.dom.Window.indexedDB: IDBFactory
    get() = this.unsafeCast<WindowWithIdb>().indexedDB
