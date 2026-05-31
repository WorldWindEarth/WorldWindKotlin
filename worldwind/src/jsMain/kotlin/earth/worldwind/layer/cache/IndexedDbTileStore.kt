package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileBlob

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.khronos.webgl.Uint8Array
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
    override val evictionPolicy: CacheEvictionPolicy,
) : TileStore, CachedSourceInfoProvider {

    override val cacheInfo: CachedSourceInfo
        get() = CachedSourceInfo(contentKey = contentKey, contentPath = db.name)

    override suspend fun readTile(z: Int, x: Int, y: Int): TileBlob? = idbSerializationLock.withLock {
        val tx = db.transaction(storeName, "readonly")
        val store = tx.objectStore(storeName)
        val record = idbAwait<IdbImageTileRecord?>(store.get(compositeKey(z, x, y))) ?: return@withLock null
        // [IdbImageTileRecord.bytes] is typed non-null, but defend against a malformed row
        // (corrupted write, hand-rolled fixture, future schema) where the field is missing —
        // the [sizeBytes] cursor walk below applies the same fallback. Treat a missing or
        // empty payload as the EMPTY sentinel so the layer short-circuits on next lookup.
        val bytes = record.bytes.unsafeCast<Uint8Array?>() ?: return@withLock TileBlob.EMPTY
        if (bytes.length == 0) return@withLock TileBlob.EMPTY
        TileBlob(
            bytes = bytes.toByteArray(),
            etag = record.etag,
            lastModified = record.lastModified,
            // Surface write time only when freshness tracking is on (finite maxAge), to drive
            // stale-while-revalidate in CachedTileSource / the elevation factory.
            cachedAt = if (evictionPolicy.maxAge != Duration.INFINITE) record.cachedAt?.toLong() else null,
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

    override suspend fun sizeBytes(): Long = idbSerializationLock.withLock {
        val tx = db.transaction(storeName, "readonly")
        val store = tx.objectStore(storeName)
        var total = 0L
        val req = store.openCursor(boundFor(contentKey))
        idbWalkCursor(req) { cursor ->
            val bytes = cursor.value.bytes.unsafeCast<Uint8Array?>()
            if (bytes != null) total += bytes.length.toLong()
        }
        // No idbAwaitTransaction — readonly auto-commits at end of microtask.
        total
    }

    private fun compositeKey(z: Int, x: Int, y: Int): Array<Any> = arrayOf(contentKey, z, x, y)
}

/**
 * Schema of an image-tile row in the shared object store. The composite primary key
 * `[contentKey, z, x, y]` is supplied separately to `put()` rather than embedded in the
 * record (unlike [IdbVectorTileRecord], whose store uses an in-line `keyPath`).
 */
internal external interface IdbImageTileRecord {
    var bytes: Uint8Array
    var etag: String?
    var lastModified: String?
    var cachedAt: Double?
}

internal fun newIdbImageTileRecord(
    bytes: Uint8Array, etag: String?, lastModified: String?, cachedAt: Double? = null,
): IdbImageTileRecord {
    val r = js("({})").unsafeCast<IdbImageTileRecord>()
    r.bytes = bytes
    r.etag = etag
    r.lastModified = lastModified
    r.cachedAt = cachedAt
    return r
}

internal fun boundFor(contentKey: String): IDBKeyRange =
    idbKeyRange.bound(
        arrayOf<Any>(contentKey, 0, 0, 0),
        arrayOf<Any>(contentKey, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE),
    ).unsafeCast<IDBKeyRange>()

internal suspend fun <T> idbAwait(request: IDBRequest): T = suspendCancellableCoroutine { cont ->
    request.onsuccess = {
        @Suppress("UNCHECKED_CAST")
        cont.resume(request.result as T)
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
            val cursor = request.result.unsafeCast<IDBCursor?>()
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

// ByteArray <-> Uint8Array helpers live alongside the feature-store conversion utilities
// in IdbFeatureStore.kt.
