package earth.worldwind.layer.ogc3d.auth

/**
 * Chains multiple providers so callers can stack auth (e.g. Ion bearer token + internal
 * tracing headers). Providers are applied in order; each one sees the URL + headers
 * produced by the previous one.
 */
class CompositeAuthProvider(private val providers: List<TilesetAuthProvider>) : TilesetAuthProvider {
    constructor(vararg providers: TilesetAuthProvider) : this(providers.toList())

    override val discriminator: String get() = DISCRIMINATOR

    override fun rewriteRequest(url: String, headers: MutableMap<String, String>): AuthedRequest {
        var current = AuthedRequest(url, headers)
        for (p in providers) {
            val mutableHeaders = current.headers.toMutableMap()
            current = p.rewriteRequest(current.url, mutableHeaders)
        }
        return current
    }

    override fun rewriteChildUri(parentUri: String, childUri: String): String {
        var current = childUri
        for (p in providers) current = p.rewriteChildUri(parentUri, current)
        return current
    }

    override fun observeTilesetResponse(requestedUrl: String, responseUrl: String, body: String) {
        for (p in providers) p.observeTilesetResponse(requestedUrl, responseUrl, body)
    }

    /**
     * Returns the first non-null redirect URL in chain order. Multiple providers requesting
     * redirects at the same time would be a configuration error — the spec is "one redirect
     * per response," and chained Cesium-Ion-plus-Custom-Headers cases want Ion's redirect.
     */
    override fun redirectFor(responseUrl: String, body: String): String? {
        for (p in providers) {
            p.redirectFor(responseUrl, body)?.let { return it }
        }
        return null
    }

    /** Cacheable iff every child says so — any veto from one provider wins. */
    override fun isTilesetBodyCacheable(url: String): Boolean = providers.all { it.isTilesetBodyCacheable(url) }

    /** Auth-rejection if any child treats the code as one (e.g. Google's 400). */
    override fun isAuthRejection(statusCode: Int): Boolean = providers.any { it.isAuthRejection(statusCode) }

    /** Invalidate every child's session state — used on auth-rejection recovery. */
    override fun invalidateSessionState() {
        for (p in providers) p.invalidateSessionState()
    }

    /** Recovery is worth attempting if any child can refresh its session. */
    override val hasRefreshableSession: Boolean get() = providers.any { it.hasRefreshableSession }

    companion object { const val DISCRIMINATOR = "CompositeAuthProvider" }
}
