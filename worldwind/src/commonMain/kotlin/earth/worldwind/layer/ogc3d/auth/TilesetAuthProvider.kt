package earth.worldwind.layer.ogc3d.auth

/**
 * Plugged into every HTTP fetch a 3D Tiles layer makes: the root `tileset.json`, every
 * external tileset reference, and every content payload. Providers can rewrite the URL
 * (e.g. append a query parameter), append request headers, or both. They are also given
 * the chance to mutate child URIs at parse time via [rewriteChildUri] so persistent state
 * like Google's `session` token rides down the tree.
 *
 * Providers are stateful by design (Google session token, Cesium Ion-issued asset
 * endpoint), so a single instance must be used for the lifetime of a layer.
 */
interface TilesetAuthProvider {
    /** Hard-coded identifier used as the discriminator when serializing the provider via
     *  [Ogc3dAuthCredentials]; must survive R8 / ProGuard / Kotlin-Native obfuscation. */
    val discriminator: String

    /**
     * Rewrite an outgoing request. Called once per HTTP fetch immediately before the GET.
     * Implementations may mutate the URL (e.g. append a token) and/or supply extra headers.
     * Returning the input unchanged is fine — see [NoAuthProvider].
     */
    fun rewriteRequest(url: String, headers: MutableMap<String, String> = mutableMapOf()): AuthedRequest

    /**
     * Rewrite a child URI discovered while parsing a tileset. Lets providers propagate
     * persistent query parameters (Google's `session` token) onto every child URI as soon
     * as the tree is built, instead of waiting until each child fetch fires. Default returns
     * the URI unchanged.
     */
    fun rewriteChildUri(parentUri: String, childUri: String): String = childUri

    /**
     * Inspect a successful tileset.json fetch result so the provider can update its internal
     * state from the response (e.g. Google's `session` query parameter on the response URL,
     * Cesium Ion's asset endpoint URL + access token in the response body). Default no-op.
     */
    fun observeTilesetResponse(requestedUrl: String, responseUrl: String, body: String) {}

    /**
     * Ask the provider whether a successful tileset fetch should redirect to a different
     * URL. Returns the new URL when the response is itself a redirect envelope (e.g.
     * Cesium Ion's `api.cesium.com/v1/assets/<id>/endpoint` returns JSON with `url` +
     * `accessToken` fields; the actual tileset.json lives at the returned URL), or null
     * when [body] is the final tileset.json content.
     *
     * The fetch queue uses this to follow redirects in a bounded loop (see
     * `TileFetchQueue.MAX_REDIRECT_HOPS`). Providers that don't need redirect logic — most —
     * accept the default null return. Providers that do should also update their internal
     * state (typically the bearer token they'll attach in subsequent [rewriteRequest]
     * calls) here, since this hook runs before the redirected request goes out.
     */
    fun redirectFor(responseUrl: String, body: String): String? = null

    /** Whether a successful tileset.json body for [url] should be persisted to the blob
     *  cache. Providers whose first response is a redirect envelope rather than a real
     *  tileset (Cesium Ion's `/endpoint` returns `{type, url, accessToken}`) return false
     *  so it never gets cached as if it were tileset content. Default true — root and
     *  subtree tileset.json bodies cache normally and replay across sessions, with the
     *  embedded session/auth tokens reused as long as the server still accepts them. */
    fun isTilesetBodyCacheable(url: String): Boolean = true

    /** Whether [statusCode] on a fetch means the credential is stale and the session must
     *  be rebootstrapped. Default 401/403; providers override for their own codes (Google
     *  returns 400 on an expired/mismatched `session=`). */
    fun isAuthRejection(statusCode: Int): Boolean = statusCode == 401 || statusCode == 403

    /** Drop in-memory auth state derived from a prior response (Google's captured
     *  `session=` token, Cesium Ion's per-asset access token) so the next fetch is
     *  forced through the full auth bootstrap. Called when the caller detects auth
     *  rejection (e.g. 401/403 on a content fetch) and is about to refetch root.json
     *  to obtain a new session/token. Default no-op for stateless providers. */
    fun invalidateSessionState() {}

    /** Whether re-bootstrapping can ever change an auth rejection's outcome — i.e. the
     *  provider derives fresh session state from server responses (Google's `session=`,
     *  Cesium Ion's per-asset token). Gates `TileFetchQueue`'s session recovery: for
     *  static-credential providers (custom headers, bearer token) a 401/403 is final, and
     *  recovery would loop refetch-root → reject → recover forever. Default false; only
     *  providers with a meaningful [invalidateSessionState] should return true. */
    val hasRefreshableSession: Boolean get() = false
}

/**
 * Auth-decorated HTTP request: a (possibly rewritten) URL plus headers to attach.
 */
data class AuthedRequest(val url: String, val headers: Map<String, String>)
