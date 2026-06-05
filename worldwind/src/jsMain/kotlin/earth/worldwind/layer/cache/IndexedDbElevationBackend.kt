package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileBlob

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import org.w3c.dom.events.Event
import kotlin.coroutines.resume
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
    private val tileStore: IndexedDbTileStore,
    private val ancillaryStoreName: String,
) : ElevationStoreBackend {

    override val isReadOnly: Boolean = false

    override val cacheInfo: CachedSourceInfo
        get() = CachedSourceInfo(contentKey = contentKey, contentPath = db.name)

    override suspend fun readTile(z: Int, x: Int, y: Int): CachedTile? {
        val blob = tileStore.readTile(z, x, y) ?: return null
        if (blob.isEmpty) return null
        val (scale, offset) = readAncillary(z, x, y) ?: (1f to 0f)
        return CachedTile(
            bytes = blob.bytes, tileScale = scale, tileOffset = offset,
            cachedAt = blob.cachedAt, etag = blob.etag, lastModified = blob.lastModified,
        )
    }

    override suspend fun writeTile(
        z: Int, x: Int, y: Int,
        bytes: ByteArray,
        tileScale: Float,
        tileOffset: Float,
        etag: String?,
        lastModified: String?,
    ) {
        tileStore.writeTile(z, x, y, TileBlob(bytes = bytes, etag = etag, lastModified = lastModified))
        if (tileScale != 1f || tileOffset != 0f) {
            writeAncillary(z, x, y, tileScale, tileOffset)
        }
    }

    override suspend fun bumpValidatedAt(z: Int, x: Int, y: Int) = tileStore.bumpValidatedAt(z, x, y)

    private suspend fun readAncillary(z: Int, x: Int, y: Int): Pair<Float, Float>? =
        idbSerializationLock.withLock {
            val tx = db.transaction(ancillaryStoreName, "readonly")
            val store = tx.objectStore(ancillaryStoreName)
            val req = store.get(arrayOf<Any>(contentKey, z, x, y))
            val raw = suspendCancellableCoroutine<dynamic> { cont ->
                req.onsuccess = { _: Event -> cont.resume(req.result) }
                req.onerror = { _: Event -> cont.resume(null) }
            }
            if (raw == undefined || raw == null) return@withLock null
            val record = raw.unsafeCast<AncillaryRowSurrogate>()
            val scale = record.scale ?: return@withLock null
            val offset = record.offset ?: return@withLock null
            scale.toFloat() to offset.toFloat()
        }

    private suspend fun writeAncillary(z: Int, x: Int, y: Int, scale: Float, offset: Float) =
        idbSerializationLock.withLock {
            val tx = db.transaction(ancillaryStoreName, "readwrite")
            val store = tx.objectStore(ancillaryStoreName)
            val record = js("({})").unsafeCast<AncillaryRowSurrogate>()
            record.scale = scale.toDouble()
            record.offset = offset.toDouble()
            store.put(record, arrayOf<Any>(contentKey, z, x, y))
            suspendCancellableCoroutine<Unit> { cont ->
                tx.oncomplete = { _: Event -> cont.resume(Unit) }
                tx.onerror = { _: Event -> cont.resume(Unit) }
                tx.onabort = { _: Event -> cont.resume(Unit) }
            }
        }
}

private external interface AncillaryRowSurrogate {
    var scale: Double?
    var offset: Double?
}
