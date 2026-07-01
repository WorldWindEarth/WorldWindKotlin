package earth.worldwind.layer.ogc3d.stream

/**
 * Raw byte transport for [TileFetchQueue], abstracted from the inline HTTP client so the same queue
 * (priority scheduling, [earth.worldwind.layer.cache.BlobStore] cache, auth, session recovery) can
 * serve a local SLPK archive as well as remote HTTP. Auth rewrite, redirects and caching stay in the
 * queue; this only fetches an already-rewritten URL. Archive sources: no rewrite, no redirects.
 */
interface TileByteSource {
    /** True when this source handles [uri]'s scheme (`http(s):`, `slpk:`, `3dtiles:`). */
    fun handles(uri: String): Boolean

    /** Fetch raw bytes for an already-rewritten [uri]. A miss is a non-success
     *  [TileByteResponse.statusCode], not a throw; only real transport failures throw. */
    suspend fun get(uri: String, headers: Map<String, String> = emptyMap()): TileByteResponse

    /** Release transport resources (close the HTTP client / archive handle). Idempotent. */
    fun close()
}

/**
 * One fetch result — the subset of an HTTP response the queue uses. Archive sources set
 * [statusCode] 200/404, [finalUrl] = the request uri, and null metadata.
 */
class TileByteResponse(
    val statusCode: Int,
    val bytes: ByteArray,
    /** URL the body was actually served from (post-redirect for HTTP; == request uri for archives). */
    val finalUrl: String,
    val contentType: String? = null,
    val etag: String? = null,
    /** Reason phrase for a non-success status, surfaced in [HttpStatusException]. Null for archives. */
    val statusMessage: String? = null,
) {
    val isSuccess: Boolean get() = statusCode in 200..299

    companion object {
        const val STATUS_OK = 200
        const val STATUS_NOT_FOUND = 404
    }
}
