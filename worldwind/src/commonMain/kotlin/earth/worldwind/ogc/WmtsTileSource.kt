package earth.worldwind.ogc

import earth.worldwind.layer.cache.HttpTileSource
import earth.worldwind.layer.source.TileBlob
import io.ktor.client.HttpClientConfig
import kotlin.time.Duration.Companion.seconds

/**
 * [HttpTileSource] for OGC Web Map Tile Service (WMTS) endpoints. Maps slippy-map `(z, x, y)`
 * directly onto a WMTS URL template using the `{TileMatrix}` / `{TileCol}` / `{TileRow}`
 * tokens (the spec-mandated KVP / RESTful placeholders). `[tileMatrixIdentifiers][z]`
 * supplies the per-level TileMatrix identifier — WMTS doesn't require numeric levels.
 *
 * `y` is slippy-map (top-down). No row flip needed — WMTS itself uses top-down rows.
 *
 * Cache wiring is external — wrap in [earth.worldwind.layer.cache.CachedTileSource] to
 * persist bytes through a [earth.worldwind.layer.cache.TileStore].
 */
class WmtsTileSource(
    val template: String,
    val tileMatrixIdentifiers: List<String>,
    val imageFormat: String,
    headers: Map<String, String> = emptyMap(),
    connectTimeoutMs: Long = 5.seconds.inWholeMilliseconds,
    requestTimeoutMs: Long = 30.seconds.inWholeMilliseconds,
    clientConfig: HttpClientConfig<*>.() -> Unit = {},
) : HttpTileSource(headers, connectTimeoutMs, requestTimeoutMs, clientConfig) {

    override suspend fun fetchTile(
        z: Int, x: Int, y: Int,
        previousEtag: String?, previousLastModified: String?,
    ): TileBlob? {
        if (z !in tileMatrixIdentifiers.indices) return null
        val url = template
            .replace(TILE_MATRIX_TEMPLATE, tileMatrixIdentifiers[z])
            .replace(TILE_ROW_TEMPLATE, y.toString())
            .replace(TILE_COL_TEMPLATE, x.toString())
        return fetchTileBlob(url, previousEtag, previousLastModified, "WMTS")
    }

    companion object {
        const val TILE_MATRIX_TEMPLATE = "{TileMatrix}"
        const val TILE_ROW_TEMPLATE = "{TileRow}"
        const val TILE_COL_TEMPLATE = "{TileCol}"
    }
}
