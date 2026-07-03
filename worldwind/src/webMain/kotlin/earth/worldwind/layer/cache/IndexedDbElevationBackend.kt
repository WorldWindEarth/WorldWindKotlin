package earth.worldwind.layer.cache

import earth.worldwind.util.js.jso
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import org.w3c.dom.events.Event
import kotlin.coroutines.resume
import kotlin.js.JsAny
import kotlin.js.unsafeCast

/**
 * IndexedDB-backed [ElevationStoreBackend]. Tile blobs flow through the existing
 * `coverage_tiles` object store via [IndexedDbTileStore] — the same wire format that
 * non-transcoded coverages used, so the schema is shared. Per-tile `(scale, offset)`
 * lives in a parallel `coverage_ancillary` object store keyed by
 * `[contentKey, z, x, y]`.
 *
 * Created by `WebContentManager.createElevationSourceFactory`; combined with a
 * [CachedElevationSourceFactory] so the cross-platform codec owns the encode / decode.
 */
internal class IndexedDbElevationBackend(
    private val db: IDBDatabase,
    private val contentKey: String,
    tileStore: IndexedDbTileStore,
    private val ancillaryStoreName: String,
) : DelegatingElevationBackend(tileStore) {

    override val cacheInfo: CachedSourceInfo
        get() = CachedSourceInfo(contentKey = contentKey, contentPath = db.name)

    override suspend fun readAncillary(z: Int, x: Int, y: Int): Pair<Float, Float>? =
        idbSerializationLock.withLock {
            val tx = db.transaction(ancillaryStoreName, "readonly")
            val store = tx.objectStore(ancillaryStoreName)
            val req = store.get(tileKey(contentKey, z, x, y))
            val raw = suspendCancellableCoroutine<JsAny?> { cont ->
                req.onsuccess = { _: Event -> cont.resume(req.result) }
                req.onerror = { _: Event -> cont.resume(null) }
            }
            val record = raw?.unsafeCast<AncillaryRowSurrogate>() ?: return@withLock null
            val scale = record.scale ?: return@withLock null
            val offset = record.offset ?: return@withLock null
            scale.toFloat() to offset.toFloat()
        }

    override suspend fun writeAncillary(z: Int, x: Int, y: Int, scale: Float, offset: Float) =
        idbSerializationLock.withLock {
            val tx = db.transaction(ancillaryStoreName, "readwrite")
            val store = tx.objectStore(ancillaryStoreName)
            val record = newAncillaryRowSurrogate()
            record.scale = scale.toDouble()
            record.offset = offset.toDouble()
            store.put(record, tileKey(contentKey, z, x, y))
            // Route through the shared helper, which commits the tx (Chromium can otherwise sit on
            // an uncommitted readwrite tx under concurrent IDB pressure).
            idbAwaitTransaction(tx)
        }

    override suspend fun deleteAncillary(z: Int, x: Int, y: Int) =
        idbSerializationLock.withLock {
            val tx = db.transaction(ancillaryStoreName, "readwrite")
            tx.objectStore(ancillaryStoreName).delete(tileKey(contentKey, z, x, y))
            idbAwaitTransaction(tx)
        }
}

private external interface AncillaryRowSurrogate : JsAny {
    var scale: Double?
    var offset: Double?
}

private fun newAncillaryRowSurrogate(): AncillaryRowSurrogate = jso()
