package earth.worldwind.layer.ogc3d.auth

/**
 * Attaches a fixed set of headers to every request. Useful for in-house tilesets that
 * authenticate via a non-Bearer scheme (e.g. an internal `X-API-Key` header), and as a
 * compositional building block under [CompositeAuthProvider].
 */
class CustomHeadersAuthProvider(val staticHeaders: Map<String, String>) : TilesetAuthProvider {
    override val discriminator: String get() = DISCRIMINATOR
    override fun rewriteRequest(url: String, headers: MutableMap<String, String>): AuthedRequest {
        headers.putAll(staticHeaders)
        return AuthedRequest(url, headers)
    }

    companion object { const val DISCRIMINATOR = "CustomHeadersAuthProvider" }
}
