package earth.worldwind.layer.ogc3d.auth

/**
 * Passthrough auth provider for unauthenticated tilesets (self-hosted, public mirrors).
 * Stateless singleton.
 */
object NoAuthProvider : TilesetAuthProvider {
    override val discriminator: String get() = DISCRIMINATOR
    override fun rewriteRequest(url: String, headers: MutableMap<String, String>): AuthedRequest =
        AuthedRequest(url, headers)

    const val DISCRIMINATOR = "NoAuthProvider"
}
