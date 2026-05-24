package earth.worldwind.layer.mvt

import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode

/**
 * Fetches a Mapbox sprite atlas (`<stem>.json` manifest + `<stem>.png` packed image) from a
 * URL stem and returns a populated [MvtSpriteAtlas]. The two fetches run sequentially —
 * sprite endpoints typically serve both responses from the same edge node so concurrency
 * doesn't save much round-trip time.
 *
 * For HiDPI displays, the standard Mapbox convention is `<stem>@2x.json` / `<stem>@2x.png`;
 * pass `stem` with the `@2x` suffix already appended if you want the higher-resolution
 * variant.
 */
object MvtSpriteAtlasLoader {

    /**
     * @param stem  Full URL minus the `.json` / `.png` suffix, e.g.
     *              `"https://tiles.example.com/styles/v1/sprite"`.
     * @param headers Extra request headers (User-Agent, Authorization, etc.).
     * @param client Optional pre-built [HttpClient]; one is created and closed per-call when null.
     * @return populated atlas, or null if either request fails / decodes empty.
     */
    suspend fun load(
        stem: String,
        headers: Map<String, String> = emptyMap(),
        client: HttpClient? = null,
    ): MvtSpriteAtlas? {
        val owned = client == null
        val http = client ?: DefaultHttpClient()
        try {
            val jsonResp = http.get("$stem.json") {
                headers { headers.forEach { (k, v) -> append(k, v) } }
            }
            if (jsonResp.status != HttpStatusCode.OK) {
                logMessage(ERROR, "MvtSpriteAtlasLoader", "load",
                    "manifest fetch failed: ${jsonResp.status}")
                return null
            }
            val manifest = MvtSpriteAtlas.parseManifest(jsonResp.bodyAsText())
            if (manifest.isEmpty()) {
                logMessage(ERROR, "MvtSpriteAtlasLoader", "load", "manifest is empty")
                return null
            }
            val pngResp = http.get("$stem.png") {
                headers { headers.forEach { (k, v) -> append(k, v) } }
            }
            if (pngResp.status != HttpStatusCode.OK) {
                logMessage(ERROR, "MvtSpriteAtlasLoader", "load",
                    "atlas image fetch failed: ${pngResp.status}")
                return null
            }
            val bytes = pngResp.bodyAsBytes()
            if (bytes.isEmpty()) {
                logMessage(ERROR, "MvtSpriteAtlasLoader", "load", "atlas image is empty")
                return null
            }
            return MvtSpriteAtlas(manifest, bytes)
        } finally {
            if (owned) http.close()
        }
    }
}
