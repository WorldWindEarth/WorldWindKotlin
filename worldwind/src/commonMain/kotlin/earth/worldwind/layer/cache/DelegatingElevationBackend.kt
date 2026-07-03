package earth.worldwind.layer.cache

import earth.worldwind.layer.source.TileBlob

/**
 * Shared [ElevationStoreBackend] logic for the platform elevation caches (iOS filesystem, Web
 * IndexedDB). Both delegate tile-blob I/O to an underlying [TileStore] and keep per-tile
 * `(scale, offset)` in a parallel "ancillary" medium — a sidecar file on iOS, an object store on
 * Web. Only that ancillary medium (and [cacheInfo]'s content path) genuinely differs between
 * platforms, so the read/write/bump orchestration lives here once and each platform implements just
 * the three ancillary hooks.
 *
 * [writeTile] keeps the ancillary entry in lockstep with the packing: it writes `(scale, offset)`
 * when non-default and deletes any prior entry when default, so a tile re-encoded with default
 * packing can't read a stale `(scale, offset)` and mis-decode its elevations.
 */
internal abstract class DelegatingElevationBackend(
    private val tileStore: TileStore,
) : ElevationStoreBackend {

    final override val isReadOnly: Boolean = false

    final override suspend fun readTile(z: Int, x: Int, y: Int): CachedTile? {
        val blob = tileStore.readTile(z, x, y) ?: return null
        if (blob.isEmpty) return null
        val (scale, offset) = readAncillary(z, x, y) ?: (1f to 0f)
        return CachedTile(
            bytes = blob.bytes, tileScale = scale, tileOffset = offset,
            cachedAt = blob.cachedAt, etag = blob.etag, lastModified = blob.lastModified,
        )
    }

    final override suspend fun writeTile(
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
        } else {
            deleteAncillary(z, x, y)
        }
    }

    final override suspend fun bumpValidatedAt(z: Int, x: Int, y: Int) = tileStore.bumpValidatedAt(z, x, y)

    /** Read a tile's stored `(scale, offset)`; `null` when absent (caller defaults to `(1, 0)`). */
    protected abstract suspend fun readAncillary(z: Int, x: Int, y: Int): Pair<Float, Float>?

    /** Persist a tile's non-default `(scale, offset)`. */
    protected abstract suspend fun writeAncillary(z: Int, x: Int, y: Int, scale: Float, offset: Float)

    /** Remove a tile's stored `(scale, offset)`, if any (called when packing reverts to default). */
    protected abstract suspend fun deleteAncillary(z: Int, x: Int, y: Int)
}
