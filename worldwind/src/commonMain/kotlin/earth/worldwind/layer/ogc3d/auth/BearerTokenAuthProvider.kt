package earth.worldwind.layer.ogc3d.auth

/**
 * Adds `Authorization: Bearer <token>` to every request. Used directly for Cesium Ion-style
 * static-token endpoints. The Ion redirect dance (`asset/<id>/endpoint` -> tileset.json +
 * signed-URL access token) is layered on top of this and wired in Phase 5.
 */
class BearerTokenAuthProvider(val token: String) : TilesetAuthProvider {
    override val discriminator: String get() = DISCRIMINATOR
    override fun rewriteRequest(url: String, headers: MutableMap<String, String>): AuthedRequest {
        headers["Authorization"] = "Bearer $token"
        return AuthedRequest(url, headers)
    }

    companion object { const val DISCRIMINATOR = "BearerTokenAuthProvider" }
}
