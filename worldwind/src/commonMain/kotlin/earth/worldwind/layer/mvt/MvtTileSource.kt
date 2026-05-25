package earth.worldwind.layer.mvt

import earth.worldwind.layer.cache.HttpTileSource
import earth.worldwind.layer.source.TileBlob
import io.ktor.client.HttpClientConfig
import kotlin.time.Duration.Companion.seconds

/**
 * URL-template-based [HttpTileSource] for Mapbox Vector Tile servers. The template uses
 * `{z}`, `{x}`, `{y}` placeholders, matching the slippy-map convention used by every major
 * vector-tile provider:
 *
 * ```
 * UrlTemplateMvtTileSource("https://tiles.example.com/data/v3/{z}/{x}/{y}.pbf")
 * UrlTemplateMvtTileSource("https://api.maptiler.com/tiles/v3/{z}/{x}/{y}.pbf?key=YOUR_KEY")
 * ```
 *
 * gzip transparency: every engine we ship (OkHttp on JVM/Android, Darwin URLSession on iOS,
 * the browser fetch API on JS) auto-decodes `Content-Encoding: gzip`, and vector tiles are
 * almost always gzipped at rest. No Ktor ContentEncoding plugin is installed.
 */
class UrlTemplateMvtTileSource(
    val urlTemplate: String,
    /** Extra request headers — typical use case is `User-Agent` for tile servers that require one. */
    headers: Map<String, String> = emptyMap(),
    connectTimeoutMs: Long = 5.seconds.inWholeMilliseconds,
    requestTimeoutMs: Long = 30.seconds.inWholeMilliseconds,
    clientConfig: HttpClientConfig<*>.() -> Unit = {},
) : HttpTileSource(headers, connectTimeoutMs, requestTimeoutMs, clientConfig) {

    override suspend fun fetchTile(
        z: Int, x: Int, y: Int,
        previousEtag: String?, previousLastModified: String?,
    ): TileBlob? {
        val url = urlTemplate
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
        return fetchTileBlob(url, previousEtag, previousLastModified, "MVT")
    }
}
