package earth.worldwind.layer.cache

import earth.worldwind.layer.source.TileBlob
import earth.worldwind.util.js.jso
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.khronos.webgl.Uint8Array
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.js
import kotlin.js.toJsNumber
import kotlin.js.toJsString
import kotlin.js.unsafeCast
import kotlin.time.Clock
import kotlin.time.Duration

/** Global serialisation lock for all IndexedDB operations on the JS target. Chrome's IDB
 *  engine can deadlock when many readonly transactions on the same store are opened
 *  concurrently — the spec allows it, but in practice 16 concurrent `get()`s on a freshly
 *  written store never complete. Serialising tile reads through one mutex restores
 *  liveness at the cost of throughput. */
internal val idbSerializationLock = Mutex()

/**
 * IndexedDB-backed [TileStore]. Tiles are stored as records keyed by `[contentKey, z, x, y]`
 * in the shared [storeName] object store; the composite key both namespaces by content key
 * and orders rows for range queries (e.g. [sizeBytes] walks the cursor for one content key).
 *
 * Bytes go in as `Uint8Array` (native IDB serialization, no JSON round-trip). ETag /
 * Last-Modified ride alongside in the same record so a follow-up network refresh can
 * conditional-GET.
 */
internal class IndexedDbTileStore(
    private val db: IDBDatabase,
    private val contentKey: String,
    private val storeName: String,
    override val cachePolicy: CachePolicy,
) : TileStore, CachedSourceInfoProvider {

    override val cacheInfo: CachedSourceInfo
        get() = CachedSourceInfo(contentKey = contentKey, contentPath = db.name)

    override suspend fun readTile(z: Int, x: Int, y: Int): TileBlob? = idbSerializationLock.withLock {
        val tx = db.transaction(storeName, "readonly")
        val store = tx.objectStore(storeName)
        val record = idbAwait(store.get(compositeKey(z, x, y)))?.unsafeCast<IdbImageTileRecord>() ?: return@withLock null
        // [IdbImageTileRecord.bytes] is typed non-null, but defend against a malformed row
        // (corrupted write, hand-rolled fixture, future schema) where the field is missing —
        // the [sizeBytes] cursor walk below applies the same fallback. Treat a missing or
        // empty payload as the EMPTY sentinel so the layer short-circuits on next lookup.
        val bytes = record.bytesOrNull() ?: return@withLock TileBlob.EMPTY
        if (bytes.length == 0) return@withLock TileBlob.EMPTY
        TileBlob(
            bytes = bytes.toByteArray(),
            etag = record.etag,
            lastModified = record.lastModified,
            // Surface write time only when freshness tracking is on (finite staleAfter), to drive
            // stale-while-revalidate in CachedTileSource / the elevation factory.
            cachedAt = if (cachePolicy.staleAfter != Duration.INFINITE) record.cachedAt?.toLong() else null,
        )
    }

    override suspend fun writeTile(z: Int, x: Int, y: Int, blob: TileBlob) = idbSerializationLock.withLock {
        val tx = db.transaction(storeName, "readwrite")
        val store = tx.objectStore(storeName)
        val record = newIdbImageTileRecord(
            blob.bytes.toUint8Array(), blob.etag, blob.lastModified,
            cachedAt = Clock.System.now().toEpochMilliseconds().toDouble(),
        )
        store.put(record, compositeKey(z, x, y))
        idbAwaitTransaction(tx)
    }

    override suspend fun deleteTile(z: Int, x: Int, y: Int) = idbSerializationLock.withLock {
        val tx = db.transaction(storeName, "readwrite")
        val store = tx.objectStore(storeName)
        store.delete(compositeKey(z, x, y))
        idbAwaitTransaction(tx)
    }

    // 304 path: refresh cachedAt only, keeping bytes / validators. Read and write in separate
    // transactions — an IDB tx auto-commits when it goes idle across a suspension, so a single
    // read-then-write tx would risk dropping the put(). The serialization lock makes the gap safe.
    override suspend fun bumpValidatedAt(z: Int, x: Int, y: Int): Unit = idbSerializationLock.withLock {
        val readTx = db.transaction(storeName, "readonly")
        val record = idbAwait(readTx.objectStore(storeName).get(compositeKey(z, x, y)))
            ?.unsafeCast<IdbImageTileRecord>() ?: return@withLock
        record.cachedAt = Clock.System.now().toEpochMilliseconds().toDouble()
        val writeTx = db.transaction(storeName, "readwrite")
        writeTx.objectStore(storeName).put(record, compositeKey(z, x, y))
        idbAwaitTransaction(writeTx)
    }

    /** Capacity eviction: drop tiles oldest-first (by `cachedAt`) until within
     *  [CachePolicy.maxEntries] (tile count). One record per tile; staleAfter never deletes. */
    override suspend fun evict(): Unit = idbSerializationLock.withLock {
        if (cachePolicy.isUnbounded) return@withLock
        data class TileRec(val key: JsAny?, val cachedAt: Double)
        val tiles = ArrayList<TileRec>()
        val readStore = db.transaction(storeName, "readonly").objectStore(storeName)
        idbWalkCursor(readStore.openCursor(boundFor(contentKey))) { cursor ->
            val record = cursor.value?.unsafeCast<IdbImageTileRecord>()
            val cachedAt = record?.cachedAt ?: 0.0
            tiles.add(TileRec(cursor.key, cachedAt))
        }
        var keptCount = 0L
        val victims = tiles.sortedByDescending { it.cachedAt }.filter { _ ->
            if (keptCount >= cachePolicy.maxEntries) true else { keptCount++; false }
        }
        if (victims.isEmpty()) return@withLock
        val writeTx = db.transaction(storeName, "readwrite")
        val writeStore = writeTx.objectStore(storeName)
        for (victim in victims) writeStore.delete(victim.key)
        idbAwaitTransaction(writeTx)
    }

    override suspend fun sizeBytes(): Long = idbSerializationLock.withLock {
        val tx = db.transaction(storeName, "readonly")
        val store = tx.objectStore(storeName)
        var total = 0L
        val req = store.openCursor(boundFor(contentKey))
        idbWalkCursor(req) { cursor ->
            val bytes = cursor.value?.unsafeCast<IdbImageTileRecord>()?.bytesOrNull()
            if (bytes != null) total += bytes.length.toLong()
        }
        // No idbAwaitTransaction — readonly auto-commits at end of microtask.
        total
    }

    private fun compositeKey(z: Int, x: Int, y: Int) =
        jsKeyArray(contentKey.toJsString(), z.toJsNumber(), x.toJsNumber(), y.toJsNumber())
}

/**
 * Schema of an image-tile row in the shared object store. The composite primary key
 * `[contentKey, z, x, y]` is supplied separately to `put()` rather than embedded in the record.
 */
internal external interface IdbImageTileRecord : JsAny {
    var bytes: Uint8Array
    var etag: String?
    var lastModified: String?
    var cachedAt: Double?
}

/** Defend against a malformed row with a missing/absent `bytes` field — see [IndexedDbTileStore]. */
internal fun IdbImageTileRecord.bytesOrNull(): Uint8Array? = idbRecordBytesOrNull(this)

// RedundantNullableReturnType: the IDE can't see into the js() string, but the ternary returns
// null at runtime when record.bytes is absent, so the nullable return is real, not redundant.
@Suppress("UNUSED_PARAMETER", "RedundantNullableReturnType")
private fun idbRecordBytesOrNull(record: JsAny): Uint8Array? = js("record.bytes ? record.bytes : null")

internal fun newIdbImageTileRecord(
    bytes: Uint8Array, etag: String?, lastModified: String?, cachedAt: Double? = null,
): IdbImageTileRecord {
    val r = newIdbImageTileRecordObj()
    r.bytes = bytes
    r.etag = etag
    r.lastModified = lastModified
    r.cachedAt = cachedAt
    return r
}

private fun newIdbImageTileRecordObj(): IdbImageTileRecord = jso()

internal fun boundFor(contentKey: String): IDBKeyRange =
    idbKeyRange.bound(
        jsKeyArray(contentKey.toJsString(), 0.toJsNumber(), 0.toJsNumber(), 0.toJsNumber()),
        jsKeyArray(
            contentKey.toJsString(), Int.MAX_VALUE.toJsNumber(), Int.MAX_VALUE.toJsNumber(), Int.MAX_VALUE.toJsNumber(),
        ),
    )

/** [idbAwait] convenience for string-valued rows (display names, data-type names). */
// REDUNDANT_CALL_OF_CONVERSION_METHOD: JsString.toString() is the real JsString->String conversion
// on wasmJs (required), but identity on JS (where the JS compiler flags it as redundant). Keep it.
@Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")
internal suspend fun idbAwaitString(request: IDBRequest): String? =
    (idbAwait(request) as? JsString)?.toString()

internal suspend fun idbAwait(request: IDBRequest): JsAny? = suspendCancellableCoroutine { cont ->
    request.onsuccess = {
        cont.resume(request.result)
    }
    request.onerror = {
        cont.resumeWithException(RuntimeException("IDB request failed: ${request.error?.message}"))
    }
}

internal suspend fun idbAwaitTransaction(tx: IDBTransaction): Unit = suspendCancellableCoroutine { cont ->
    tx.oncomplete = { cont.resume(Unit) }
    tx.onerror = {
        cont.resumeWithException(RuntimeException("IDB transaction failed: ${tx.error?.message}"))
    }
    tx.onabort = {
        cont.resumeWithException(RuntimeException("IDB transaction aborted"))
    }
    // Explicit commit while tx is still "active" — Chromium's IDB engine can otherwise sit on
    // the tx indefinitely waiting for the implicit "no-pending-requests + control-returned"
    // trigger, which never fires under concurrent IDB pressure (tile reads, MVT vector-tile
    // writes, etc.). runCatching covers the no-op browsers where the call throws InvalidStateError.
    runCatching { tx.commit() }
}

/** Walk a cursor through every row, calling [onRow] per match. Suspending. */
internal suspend fun idbWalkCursor(request: IDBRequest, onRow: (IDBCursor) -> Unit) {
    suspendCancellableCoroutine { cont ->
        request.onsuccess = {
            val cursor = request.result?.unsafeCast<IDBCursor>()
            if (cursor == null) cont.resume(Unit)
            else {
                onRow(cursor)
                cursor.`continue`()
            }
        }
        request.onerror = {
            cont.resumeWithException(RuntimeException("IDB cursor failed: ${request.error?.message}"))
        }
    }
}

// tileKey + ByteArray <-> Uint8Array conversion helpers live in IdbConversions.kt.
