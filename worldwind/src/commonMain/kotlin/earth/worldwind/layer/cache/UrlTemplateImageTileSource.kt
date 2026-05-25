package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileBlob

import earth.worldwind.util.UrlTemplate
import io.ktor.client.HttpClientConfig
import kotlin.time.Duration.Companion.seconds

/**
 * [TileSource] for slippy-map raster servers — image tiles addressed by `(z, x, y)` via
 * a URL template, the same pattern every major XYZ-tile provider uses (Google, OSM,
 * MapServer cgi-bin, Stamen, …). Supports the common template tokens `{z}`, `{x}`, `{y}`,
 * `{lang}`, `{rand=a,b,c}`, `{i}` (the WorldWind dialect — see [UrlTemplate]).
 */
class UrlTemplateImageTileSource(
    val urlTemplate: String,
    headers: Map<String, String> = emptyMap(),
    connectTimeoutMs: Long = 5.seconds.inWholeMilliseconds,
    requestTimeoutMs: Long = 30.seconds.inWholeMilliseconds,
    clientConfig: HttpClientConfig<*>.() -> Unit = {},
) : HttpTileSource(headers, connectTimeoutMs, requestTimeoutMs, clientConfig) {

    private val template = UrlTemplate(urlTemplate)

    override suspend fun fetchTile(
        z: Int, x: Int, y: Int,
        previousEtag: String?, previousLastModified: String?,
    ): TileBlob? = fetchTileBlob(
        url = template.buildUrl(z, x, y),
        previousEtag = previousEtag,
        previousLastModified = previousLastModified,
        errorContext = "Tile",
    )
}
