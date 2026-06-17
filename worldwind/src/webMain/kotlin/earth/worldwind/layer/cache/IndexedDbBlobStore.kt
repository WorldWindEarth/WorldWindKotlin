package earth.worldwind.layer.cache

import earth.worldwind.util.js.jso
import kotlinx.coroutines.sync.withLock
import org.khronos.webgl.Uint8Array
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.toJsNumber
import kotlin.js.toJsString
import kotlin.js.unsafeCast
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * IndexedDB-backed [BlobStore]. Records keyed by `[contentKey, uri]` in the shared
 * [WebContentManager.BLOB_STORE_OGC3D] object store; one store covers every 3D Tiles
 * dataset (matching the existing pattern of one IDB object store per data type).
 *
 * Eviction is best-effort and walks the cursor for this store's `contentKey` range — see
 * [evict]. The first-touch sweep on construction brings a re-opened store into bounds
 * before the layer issues a single read.
 *
 * Records carry the same fields as the GeoPackage `GpkgBlobRow` (content_type, etag,
 * last_modified, cached_at, size_bytes) so the [BlobStore] surface is uniform across
 * platforms; only the storage substrate differs.
 */
internal class IndexedDbBlobStore(
    private val db: IDBDatabase,
    private val contentKey: String,
    private val storeName: String,
    private val evictionPolicy: CachePolicy,
) : BlobStore {

    override suspend fun get(uri: String): BlobEntry? = idbSerializationLock.withLock {
        val tx = db.transaction(storeName, "readonly")
        val store = tx.objectStore(storeName)
        val record = idbAwait(store.get(compositeKey(uri)))?.unsafeCast<IdbBlobRecord>()
            ?: return@withLock null
        // Defend against a malformed row (aborted put, legacy schema, hand-rolled fixture)
        // where the `bytes` field is absent: treat as a cache miss + let the caller refetch.
        val bytes = record.bytesOrNull() ?: return@withLock null
        BlobEntry(
            bytes = bytes.toByteArray(),
            contentType = record.contentType,
            etag = record.etag,
            lastModified = record.lastModifiedMs?.let { Instant.fromEpochMilliseconds(it.toLong()) },
            responseUrl = record.responseUrl,
        )
    }

    override suspend fun put(
        uri: String,
        bytes: ByteArray,
        contentType: String?,
        etag: String?,
        lastModified: Instant?,
        responseUrl: String?,
    ) = idbSerializationLock.withLock {
        val tx = db.transaction(storeName, "readwrite")
        val store = tx.objectStore(storeName)
        val record = newIdbBlobRecord(
            bytes = bytes.toUint8Array(),
            contentType = contentType,
            etag = etag,
            lastModifiedMs = lastModified?.toEpochMilliseconds()?.toDouble(),
            cachedAtMs = Clock.System.now().toEpochMilliseconds().toDouble(),
            sizeBytes = bytes.size.toDouble(),
            responseUrl = responseUrl,
        )
        store.put(record, compositeKey(uri))
        idbAwaitTransaction(tx)
    }

    override suspend fun remove(uri: String) = idbSerializationLock.withLock {
        val tx = db.transaction(storeName, "readwrite")
        val store = tx.objectStore(storeName)
        store.delete(compositeKey(uri))
        idbAwaitTransaction(tx)
    }

    override suspend fun clear() = idbSerializationLock.withLock {
        // Per-contentKey scoped clear, not store.clear(): one IDB store hosts every 3D Tiles
        // dataset, so blowing away the whole store would nuke unrelated layers' caches.
        val tx = db.transaction(storeName, "readwrite")
        val store = tx.objectStore(storeName)
        val req = store.openCursor(boundForBlob(contentKey))
        idbWalkCursor(req) { cursor -> cursor.delete() }
        idbAwaitTransaction(tx)
    }

    override suspend fun sizeBytes(): Long = idbSerializationLock.withLock {
        val tx = db.transaction(storeName, "readonly")
        val store = tx.objectStore(storeName)
        var total = 0L
        val req = store.openCursor(boundForBlob(contentKey))
        idbWalkCursor(req) { cursor ->
            val rec = cursor.value?.unsafeCast<IdbBlobRecord>() ?: return@idbWalkCursor
            total += rec.sizeBytes?.toLong() ?: rec.bytesOrNull()?.length?.toLong() ?: 0L
        }
        total
    }

    /** Apply [evictionPolicy] sweep — oldest cachedAt first until the budget is satisfied. */
    suspend fun evict(): Unit = idbSerializationLock.withLock {
        if (evictionPolicy.isUnbounded) return@withLock
        val tx = db.transaction(storeName, "readwrite")
        val store = tx.objectStore(storeName)

        // Two-pass: first walk collects (key, sizeBytes, cachedAtMs); second pass deletes the
        // oldest until each bound is satisfied. Avoids per-cursor delete recomputing totals on
        // every iteration, which got expensive on stores with thousands of entries.
        data class Row(val key: JsAny?, val sizeBytes: Long, val cachedAtMs: Long)
        val rows = mutableListOf<Row>()
        val req = store.openCursor(boundForBlob(contentKey))
        idbWalkCursor(req) { cursor ->
            val rec = cursor.value?.unsafeCast<IdbBlobRecord>() ?: return@idbWalkCursor
            val sz = rec.sizeBytes?.toLong() ?: rec.bytesOrNull()?.length?.toLong() ?: 0L
            val cachedAt = rec.cachedAtMs?.toLong() ?: 0L
            rows.add(Row(cursor.key, sz, cachedAt))
        }
        rows.sortBy { it.cachedAtMs }

        // staleAfter NEVER deletes — stale blobs are refreshed in place by
        // stale-while-revalidate, not removed. Only [CachePolicy.maxEntries] evicts
        // (each tile is one row, whole-tile).
        if (rows.size > evictionPolicy.maxEntries) {
            val toDrop = rows.size - evictionPolicy.maxEntries.toInt()
            for (i in 0 until toDrop) store.delete(rows[i].key)
        }
        idbAwaitTransaction(tx)
    }

    private fun compositeKey(uri: String): JsArray<JsAny?> =
        jsKeyArray(contentKey.toJsString(), uri.toJsString())
}

/** Bound that covers `[contentKey, *]` — every URI under one content key. */
internal fun boundForBlob(contentKey: String): IDBKeyRange =
    idbKeyRange.bound(
        jsKeyArray(contentKey.toJsString(), "".toJsString()),
        jsKeyArray(contentKey.toJsString(), "￿".toJsString()),
    )

/**
 * Schema of one blob row. Parallel to `GpkgBlobRow` columns; same metadata travels through
 * the [BlobEntry] envelope on read. Properties are `Double?` rather than `Number?` because
 * Kotlin/Wasm JS interop only allows external + primitive types.
 *
 * `bytes` is typed non-null but real rows can be missing it (aborted writes, legacy schemas).
 * Reads go through [bytesOrNull] to surface that as null instead of an NPE.
 */
internal external interface IdbBlobRecord : JsAny {
    var bytes: Uint8Array
    var contentType: String?
    var etag: String?
    /** Epoch milliseconds (Double because IDB doesn't understand kotlin.Long). */
    var lastModifiedMs: Double?
    /** Epoch milliseconds the row was written. Drives age eviction. */
    var cachedAtMs: Double?
    var sizeBytes: Double?
    /** Post-redirect URL the body was actually served from. Null for legacy rows. */
    var responseUrl: String?
}

internal fun IdbBlobRecord.bytesOrNull(): Uint8Array? = idbBlobRecordBytesOrNull(this)

@Suppress("UNUSED_PARAMETER", "RedundantNullableReturnType")
private fun idbBlobRecordBytesOrNull(record: JsAny): Uint8Array? = js("record.bytes ? record.bytes : null")

internal fun newIdbBlobRecord(
    bytes: Uint8Array,
    contentType: String?,
    etag: String?,
    lastModifiedMs: Double?,
    cachedAtMs: Double,
    sizeBytes: Double,
    responseUrl: String?,
): IdbBlobRecord {
    val r: IdbBlobRecord = jso()
    r.bytes = bytes
    r.contentType = contentType
    r.etag = etag
    r.lastModifiedMs = lastModifiedMs
    r.cachedAtMs = cachedAtMs
    r.sizeBytes = sizeBytes
    r.responseUrl = responseUrl
    return r
}
