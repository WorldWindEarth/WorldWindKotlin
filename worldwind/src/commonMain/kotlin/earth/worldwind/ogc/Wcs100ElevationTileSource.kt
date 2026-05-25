package earth.worldwind.ogc

import com.eygraber.uri.Uri
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.layer.cache.HttpTileSource
import earth.worldwind.layer.source.TileBlob
import io.ktor.client.HttpClientConfig
import kotlin.time.Duration.Companion.seconds

/**
 * [HttpTileSource] for OGC Web Coverage Service (WCS) 1.0.0 elevation endpoints. Mirrors the
 * URL convention used by [Wcs100ElevationSourceFactory] — same BBOX-keyed GetCoverage
 * request — but emits raw elevation tile bytes that the cache decorator can persist
 * through a [earth.worldwind.layer.cache.TileStore].
 *
 * `(z, x, y)`: `z` indexes [TileMatrixSet.entries]; `y` is top-down ([earth.worldwind.geom.TileMatrix.tileSector]
 * already uses top-down row indexing, matching the slippy-map y convention this contract
 * requires).
 */
class Wcs100ElevationTileSource(
    val serviceAddress: String,
    val coverageName: String,
    val outputFormat: String,
    val tileMatrixSet: TileMatrixSet,
    val coordinateSystem: String = "EPSG:4326",
    headers: Map<String, String> = emptyMap(),
    connectTimeoutMs: Long = 5.seconds.inWholeMilliseconds,
    requestTimeoutMs: Long = 30.seconds.inWholeMilliseconds,
    clientConfig: HttpClientConfig<*>.() -> Unit = {},
) : HttpTileSource(headers, connectTimeoutMs, requestTimeoutMs, clientConfig) {

    override suspend fun fetchTile(
        z: Int, x: Int, y: Int,
        previousEtag: String?, previousLastModified: String?,
    ): TileBlob? {
        val matrix = tileMatrixSet.entries.getOrNull(z) ?: return null
        if (y !in 0 until matrix.matrixHeight) return null
        if (x !in 0 until matrix.matrixWidth) return null
        val sector = matrix.tileSector(row = y, column = x)
        val url = Uri.parse(serviceAddress).buildUpon()
            .appendQueryParameter("VERSION", "1.0.0")
            .appendQueryParameter("SERVICE", "WCS")
            .appendQueryParameter("REQUEST", "GetCoverage")
            .appendQueryParameter("COVERAGE", coverageName)
            .appendQueryParameter("CRS", coordinateSystem)
            .appendQueryParameter("BBOX", sector.run {
                "${minLongitude.inDegrees},${minLatitude.inDegrees}," +
                    "${maxLongitude.inDegrees},${maxLatitude.inDegrees}"
            })
            .appendQueryParameter("WIDTH", matrix.tileWidth.toString())
            .appendQueryParameter("HEIGHT", matrix.tileHeight.toString())
            .appendQueryParameter("FORMAT", outputFormat)
            .build().toString()
        return fetchTileBlob(url, previousEtag, previousLastModified, "WCS")
            ?.let(WmsElevationTileSource.Companion::guardElevation)
    }
}
