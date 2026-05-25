package earth.worldwind.layer.cache

/**
 * Storage backend for one elevation coverage's encoded tiles + their per-tile ancillary
 * (`scale`, `offset`) metadata. Per-platform implementations bind one [contentKey] to
 * the underlying medium:
 *   - JS: IDB `coverage_tiles` blob store + `coverage_ancillary` keyed object store.
 *   - iOS: filesystem `${baseDirectory}/${contentKey}/${z}/${x}/${y}.bin` blob +
 *     `${z}/${x}/${y}.ancillary` sidecar.
 *
 * The codec ([ElevationStorageCodec]) lives one level up — this interface is *just*
 * "where do the bytes live" + "what's the per-tile scale/offset" so the cross-platform
 * cached elevation factory (`CachedElevationDataFactory`) doesn't have to know about
 * the backend medium.
 */
interface ElevationStoreBackend {
    /** When `true`, [writeTile] throws. */
    val isReadOnly: Boolean

    /** Cache identity (contentKey + containing ContentManager path). Surfaced through
     *  [CachedElevationSourceFactory.cacheInfo] so layer-side extensions can return it. */
    val cacheInfo: CachedSourceInfo

    /** Read the encoded tile bytes + ancillary metadata for `(z, x, y)`. Returns `null`
     *  when the tile isn't present in the cache (cache miss). */
    suspend fun readTile(z: Int, x: Int, y: Int): CachedTile?

    /** Persist the encoded tile bytes + ancillary metadata for `(z, x, y)`. Implementations
     *  should be idempotent — writing the same key twice replaces the prior row. */
    suspend fun writeTile(
        z: Int, x: Int, y: Int,
        bytes: ByteArray,
        tileScale: Float,
        tileOffset: Float,
    )
}

/** One stored tile pulled out of an [ElevationStoreBackend]: the encoded blob plus the
 *  per-tile `(scale, offset)` the codec needs to decode it. */
data class CachedTile(
    val bytes: ByteArray,
    val tileScale: Float,
    val tileOffset: Float,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CachedTile) return false
        return tileScale == other.tileScale && tileOffset == other.tileOffset && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + tileScale.hashCode()
        result = 31 * result + tileOffset.hashCode()
        return result
    }
}
