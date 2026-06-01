package earth.worldwind.layer.ogc3d.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Round-trip serialization for [TilesetAuthProvider] credentials so a 3D-Tiles cache can
 * carry enough info to be reopened from `ContentManager.findEntry(...)` without the caller
 * having to supply credentials separately.
 *
 * Stored in [earth.worldwind.layer.cache.WebServiceInfo.metadata] as a small JSON envelope
 * paired with the provider class name in [earth.worldwind.layer.cache.WebServiceInfo.layerName].
 * Same local-storage tradeoff as keeping the credentials in any other on-device DB
 * (Room / SharedPreferences / Keychain).
 */
object Ogc3dAuthCredentials {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Encode the provider's secret(s) to a JSON string, or null when there's nothing to
     *  store (NoAuthProvider, CompositeAuthProvider). */
    fun encode(provider: TilesetAuthProvider): String? = when (provider) {
        is BearerTokenAuthProvider -> json.encodeToString(TokenEnv.serializer(), TokenEnv(provider.token))
        is CesiumIonAuthProvider -> json.encodeToString(TokenEnv.serializer(), TokenEnv(provider.userAccessToken))
        is GoogleTilesAuthProvider -> json.encodeToString(ApiKeyEnv.serializer(), ApiKeyEnv(provider.apiKey))
        is CustomHeadersAuthProvider -> json.encodeToString(HeadersEnv.serializer(), HeadersEnv(provider.staticHeaders))
        else -> null
    }

    /** Reconstruct a provider from its class-name discriminator + [metadata] JSON. Returns
     *  [NoAuthProvider] when the discriminator is unknown / metadata is missing / decode
     *  fails — never throws. Callers wanting to fail loudly should null-check upstream. */
    fun decode(discriminator: String?, metadata: String?): TilesetAuthProvider {
        if (metadata == null) return NoAuthProvider
        return runCatching {
            when (discriminator) {
                BearerTokenAuthProvider.DISCRIMINATOR -> BearerTokenAuthProvider(json.decodeFromString(TokenEnv.serializer(), metadata).token)
                CesiumIonAuthProvider.DISCRIMINATOR -> CesiumIonAuthProvider(json.decodeFromString(TokenEnv.serializer(), metadata).token)
                GoogleTilesAuthProvider.DISCRIMINATOR -> GoogleTilesAuthProvider(json.decodeFromString(ApiKeyEnv.serializer(), metadata).apiKey)
                CustomHeadersAuthProvider.DISCRIMINATOR -> CustomHeadersAuthProvider(json.decodeFromString(HeadersEnv.serializer(), metadata).headers)
                else -> NoAuthProvider
            }
        }.getOrDefault(NoAuthProvider)
    }

    @Serializable private data class TokenEnv(val token: String)
    @Serializable private data class ApiKeyEnv(val apiKey: String)
    @Serializable private data class HeadersEnv(val headers: Map<String, String>)
}
