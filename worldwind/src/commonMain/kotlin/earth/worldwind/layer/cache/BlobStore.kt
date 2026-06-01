package earth.worldwind.layer.cache

import kotlin.time.Instant

/**
 * URI-keyed binary blob store. Persists arbitrary HTTP-fetched payloads (3D Tiles content,
 * future generic asset caches) under a single content key.
 *
 * Distinct from [TileStore] / [FeatureStore]: those are pyramid- or feature-shaped. This
 * one is a flat string-keyed key/value store, the right shape for any cache whose entries
 * are independent URLs rather than a coordinate grid. The first user is 3D Tiles
 * ([earth.worldwind.layer.ogc3d.Ogc3dTilesLayer]) — each tile's content URI keys into one
 * row.
 *
 * Persistence backend is platform-dependent (GeoPackage extension table on JVM/Android,
 * IndexedDB on JS, file blobs on iOS), all reached through
 * [earth.worldwind.util.ContentManager.openBlobStore].
 *
 * The store stores both the raw payload bytes and a small metadata envelope
 * ([BlobEntry.contentType], [BlobEntry.etag], [BlobEntry.lastModified]) so the layer can
 * skip magic-byte detection when the server returned a known content type and so future
 * revalidation (If-None-Match / If-Modified-Since) can be wired without a schema migration.
 */
interface BlobStore {
    /** Fetch a previously-stored blob. Null when [uri] is not in the cache. */
    suspend fun get(uri: String): BlobEntry?

    /**
     * Persist or replace the blob for [uri]. [contentType] is whatever the server returned
     * (e.g. `application/octet-stream`, `model/gltf-binary`); null when unknown. [etag] and
     * [lastModified] are conditional-fetch metadata the layer can consult on a future
     * revalidation request. [responseUrl] is the post-redirect URL the body was actually
     * served from — replayed via [BlobEntry.responseUrl] on cache hits so callers can resolve
     * relative URIs against the body's true origin. Null when same as [uri] / unknown.
     */
    suspend fun put(
        uri: String,
        bytes: ByteArray,
        contentType: String? = null,
        etag: String? = null,
        lastModified: Instant? = null,
        responseUrl: String? = null,
    )

    /** Drop one entry. No-op when [uri] is absent. */
    suspend fun remove(uri: String)

    /** Drop every entry. Schema is preserved so subsequent puts succeed. */
    suspend fun clear()

    /**
     * Total stored payload size in bytes. Excludes metadata + schema overhead; close
     * approximation suitable for budget reporting, not exact accounting.
     */
    suspend fun sizeBytes(): Long
}

/**
 * One read result from [BlobStore.get]. Carries the bytes alongside the conditional-fetch
 * metadata that was stored at write time.
 */
data class BlobEntry(
    val bytes: ByteArray,
    val contentType: String?,
    val etag: String?,
    val lastModified: Instant?,
    /** URL the body was actually served from (after any auth-driven redirects). Null when
     *  unrecorded — older cache rows written before this column existed return null. */
    val responseUrl: String? = null,
) {
    // Manual equals/hashCode: ByteArray identity-equals by default. Comparing by content keeps
    // tests + assertEquals predictable. Hot-path callers should not be comparing entries.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BlobEntry) return false
        return bytes.contentEquals(other.bytes)
            && contentType == other.contentType
            && etag == other.etag
            && lastModified == other.lastModified
            && responseUrl == other.responseUrl
    }
    override fun hashCode(): Int {
        var h = bytes.contentHashCode()
        h = 31 * h + (contentType?.hashCode() ?: 0)
        h = 31 * h + (etag?.hashCode() ?: 0)
        h = 31 * h + (lastModified?.hashCode() ?: 0)
        h = 31 * h + (responseUrl?.hashCode() ?: 0)
        return h
    }
}

/**
 * Always-empty store. Used as the [earth.worldwind.util.ContentManager.openBlobStore] default
 * on platforms that haven't implemented persistence yet, so a layer that opts into caching
 * silently falls back to network-only without crashing.
 */
object NoOpBlobStore : BlobStore {
    override suspend fun get(uri: String): BlobEntry? = null
    override suspend fun put(
        uri: String,
        bytes: ByteArray,
        contentType: String?,
        etag: String?,
        lastModified: Instant?,
        responseUrl: String?,
    ) {}
    override suspend fun remove(uri: String) {}
    override suspend fun clear() {}
    override suspend fun sizeBytes(): Long = 0L
}
