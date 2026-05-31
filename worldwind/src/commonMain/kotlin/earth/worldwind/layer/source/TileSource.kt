package earth.worldwind.layer.source

/**
 * One tile of raw bytes plus optional HTTP revalidation headers. The byte payload is what
 * the server returned: PNG/JPEG/WebP for image layers, GeoTIFF/Int16/Float32 for elevation,
 * Mapbox Vector Tile protobuf for MVT. The cache stores blobs as-is — decoding happens in
 * the layer that consumes the source. [etag] / [lastModified] ride alongside so a
 * subsequent network fetch can issue a conditional GET.
 */
data class TileBlob(
    val bytes: ByteArray,
    val etag: String? = null,
    val lastModified: String? = null,
    /**
     * MIME-type the server reported for [bytes] (e.g. `application/bil16`, `image/png`,
     * `application/vnd.ogc.se_xml`). Set when the bytes came from a fresh HTTP response;
     * `null` for cache-only reads (cache stores typically don't persist the header).
     *
     * Consumers that care about format dispatch (elevation decoder, primarily) should
     * prefer this over any "requested" format string when non-null — it reflects what
     * the server actually returned, not what we asked for, and catches the case where a
     * service-exception XML is returned at HTTP 200.
     */
    val contentType: String? = null,
    /**
     * Epoch-millis the cached tile was written, for a stale-while-revalidate read. Set by a
     * cache store when freshness tracking is enabled (finite eviction `maxAge`); `null` on
     * network responses and on cache reads where freshness isn't tracked. [CachedTileSource]
     * uses it to decide whether to kick a background refresh after serving the cached blob.
     */
    val cachedAt: Long? = null,
) {
    val isEmpty: Boolean get() = bytes.isEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TileBlob) return false
        return etag == other.etag && lastModified == other.lastModified
            && contentType == other.contentType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + (etag?.hashCode() ?: 0)
        result = 31 * result + (lastModified?.hashCode() ?: 0)
        result = 31 * result + (contentType?.hashCode() ?: 0)
        return result
    }

    companion object {
        /** Sentinel for "the server confirmed there is no tile at this address" (HTTP 404 etc).
         *  Cached so the layer doesn't repeatedly re-query ocean / out-of-bounds tiles. */
        val EMPTY = TileBlob(ByteArray(0))
    }
}

/**
 * A source of byte-encoded tiles indexed by slippy-map `(z, x, y)`. Implementations:
 *   * HTTP — wraps a URL template, performs conditional GET when [previousEtag] /
 *     [previousLastModified] are supplied (so a follow-up replay through a cache
 *     decorator can revalidate cheaply).
 *   * Cache-decorated — wraps a network source plus a [TileStore]. Returns cached blobs
 *     on hit; on miss falls back to the inner network source.
 *   * Cache-only — returns `null` for any tile the cache doesn't have. Useful for
 *     replaying a GPKG offline.
 *
 * Returns `null` when the tile is unavailable (network error, out-of-bounds, cache miss).
 * Use [TileBlob.EMPTY] to encode "definitely no tile" so callers can cache the negative
 * answer without confusing it with a transient miss.
 */
interface TileSource {
    suspend fun fetchTile(
        z: Int, x: Int, y: Int,
        previousEtag: String? = null,
        previousLastModified: String? = null,
    ): TileBlob?

    /**
     * Cache-only fast-path read for `(z, x, y)`. Default returns `null` — a plain network
     * source has no cache to consult. Cache-backed sources override to read their store
     * without going through the network-fetch concurrency budget.
     *
     * Callers (layers) use this to serve cache hits cheaply, then fall back to [fetchTile]
     * only on a miss. The polymorphic dispatch keeps layers free of `is CachedTileSource`
     * checks.
     */
    suspend fun tryReadCachedTile(z: Int, x: Int, y: Int): TileBlob? = null

    /** Release any resources held by the source (HTTP client, etc.). Idempotent default no-op. */
    fun close() {}
}
