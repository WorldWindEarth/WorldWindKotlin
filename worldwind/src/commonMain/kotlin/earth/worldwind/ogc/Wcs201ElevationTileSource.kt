package earth.worldwind.ogc

import com.eygraber.uri.Uri
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.layer.cache.HttpTileSource
import earth.worldwind.layer.source.TileBlob
import io.ktor.client.HttpClientConfig
import kotlin.time.Duration.Companion.seconds

/**
 * [HttpTileSource] for OGC Web Coverage Service (WCS) 2.0.1 elevation endpoints. Mirrors
 * the SUBSET/SCALESIZE URL convention used by [Wcs201ElevationSourceFactory] but emits
 * raw elevation tile bytes that the cache decorator can persist through a
 * [earth.worldwind.layer.cache.TileStore].
 *
 * `(z, x, y)`: `z` indexes [TileMatrixSet.entries]; `y` is top-down.
 */
class Wcs201ElevationTileSource(
    val serviceAddress: String,
    val coverageName: String,
    val outputFormat: String,
    val tileMatrixSet: TileMatrixSet,
    val axisLabels: List<String> = listOf("Lat", "Long"),
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
            .appendQueryParameter("VERSION", "2.0.1")
            .appendQueryParameter("SERVICE", "WCS")
            .appendQueryParameter("REQUEST", "GetCoverage")
            .appendQueryParameter("COVERAGEID", coverageName)
            .appendQueryParameter("OUTPUTCRS", coordinateSystem)
            .appendQueryParameter("FORMAT", outputFormat)
            .appendQueryParameter(
                "SUBSET",
                "${axisLabels[0]}(${sector.minLatitude.inDegrees},${sector.maxLatitude.inDegrees})",
            )
            .appendQueryParameter(
                "SUBSET",
                "${axisLabels[1]}(${sector.minLongitude.inDegrees},${sector.maxLongitude.inDegrees})",
            )
            .appendQueryParameter(
                "SCALESIZE",
                "${axisLabels[1]}(${matrix.tileWidth}),${axisLabels[0]}(${matrix.tileHeight})",
            )
            .appendQueryParameter("OVERVIEWPOLICY", "NEAREST")
            .build().toString()
        return fetchTileBlob(url, previousEtag, previousLastModified, "WCS")
            ?.let(WmsElevationTileSource.Companion::guardElevation)
    }
}
