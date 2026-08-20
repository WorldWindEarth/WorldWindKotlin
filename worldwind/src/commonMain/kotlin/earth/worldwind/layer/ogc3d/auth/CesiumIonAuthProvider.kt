package earth.worldwind.layer.ogc3d.auth

import kotlin.concurrent.Volatile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Cesium Ion auth. Point the layer at `https://api.cesium.com/v1/assets/<id>/endpoint`;
 * the first fetch returns `{type, url, accessToken}` — [redirectFor] follows `url` and
 * captures the per-asset token for subsequent Bearer headers. One provider instance per asset.
 */
class CesiumIonAuthProvider(
    val userAccessToken: String,
) : TilesetAuthProvider {

    override val discriminator: String get() = DISCRIMINATOR

    @Volatile private var assetAccessToken: String? = null

    /** Latched after the endpoint redirect fires. */
    @Volatile private var endpointFollowed: Boolean = false

    override fun rewriteRequest(url: String, headers: MutableMap<String, String>): AuthedRequest {
        val token = assetAccessToken ?: userAccessToken
        headers["Authorization"] = "Bearer $token"
        return AuthedRequest(url, headers)
    }

    override fun observeTilesetResponse(requestedUrl: String, responseUrl: String, body: String) {
        if (assetAccessToken != null) return
        val envelope = tryParseEndpoint(body) ?: return
        envelope.accessToken?.let { assetAccessToken = it }
    }

    override fun redirectFor(responseUrl: String, body: String): String? {
        if (endpointFollowed) return null
        val envelope = tryParseEndpoint(body) ?: return null
        val redirectUrl = envelope.url ?: return null
        envelope.accessToken?.let { assetAccessToken = it }
        endpointFollowed = true
        return redirectUrl
    }

    /** Endpoint URL carries the per-asset token in its body envelope and must always
     *  re-fetch to refresh; the redirected tileset.json is also session-bound to the
     *  bearer header so neither should be cached. */
    override fun isTilesetBodyCacheable(url: String): Boolean = false

    /** Drop the captured asset access token + endpoint-followed latch so a re-fetch of
     *  the `/endpoint` URL re-issues a fresh Bearer token. */
    override fun invalidateSessionState() {
        assetAccessToken = null
        endpointFollowed = false
    }

    /** Per-asset tokens are re-issued by the `/endpoint` fetch, so recovery can cure a rejection. */
    override val hasRefreshableSession: Boolean get() = true

    private fun tryParseEndpoint(body: String): IonEndpoint? = try {
        val envelope = json.decodeFromString<IonEndpoint>(body)
        // `type` + `url` discriminator distinguishes the endpoint envelope from a tileset.json.
        if (!envelope.type.isNullOrEmpty() && !envelope.url.isNullOrEmpty()) envelope else null
    } catch (_: Throwable) {
        null
    }

    companion object {
        const val DISCRIMINATOR = "CesiumIonAuthProvider"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

@Serializable
private data class IonEndpoint(
    val type: String? = null,
    val url: String? = null,
    @SerialName("accessToken") val accessToken: String? = null,
)
