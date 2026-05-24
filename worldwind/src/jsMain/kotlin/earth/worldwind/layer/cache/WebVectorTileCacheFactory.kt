package earth.worldwind.layer.cache

import earth.worldwind.layer.VectorTileBlob
import earth.worldwind.layer.VectorTileCacheSourceFactory
import kotlin.time.Instant

/**
 * IndexedDB-backed [VectorTileCacheSourceFactory]. Each MVT layer's tiles live in the
 * shared `vector-tiles` object store under a unique [contentKey]; the BLOB payload is a
 * `Uint8Array` (no JSON round-trip), and HTTP revalidation headers ride alongside.
 */
class WebVectorTileCacheFactory internal constructor(
    private val store: IdbFeatureStore,
    override val contentKey: String,
) : VectorTileCacheSourceFactory {
    override val contentType = "IDB"
    override val contentPath = "indexeddb:worldwind-cache/vector-tiles"

    override suspend fun lastModifiedDate(): Instant? =
        store.vectorTileLastModified(contentKey)?.let { Instant.fromEpochMilliseconds(it.toLong()) }

    override suspend fun contentSize(): Long = store.vectorTileContentSize(contentKey)

    override var evictionPolicy: CacheEvictionPolicy = CacheEvictionPolicy.UNBOUNDED

    override suspend fun evict() {
        if (evictionPolicy.isUnbounded) return
        store.evictVectorTilesByPolicy(contentKey, evictionPolicy)
    }

    override suspend fun clearContent(deleteMetadata: Boolean) {
        store.deleteVectorTilesByContent(contentKey)
    }

    override suspend fun readTileBlob(z: Int, x: Int, y: Int): VectorTileBlob? {
        val rec = store.readVectorTileBlob(contentKey, z, x, y) ?: return null
        return VectorTileBlob(
            bytes = rec.bytes.toByteArray(),
            etag = rec.etag,
            lastModified = rec.lastModified,
        )
    }

    override suspend fun writeTileBlob(
        z: Int, x: Int, y: Int,
        bytes: ByteArray, etag: String?, lastModified: String?,
    ) {
        val now = js("Date.now()").unsafeCast<Double>()
        store.writeVectorTileBlob(
            newIdbVectorTileRecord(contentKey, z, x, y, bytes, etag, lastModified, now)
        )
    }
}
