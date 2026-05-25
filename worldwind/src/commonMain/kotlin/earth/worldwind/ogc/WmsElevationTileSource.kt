package earth.worldwind.ogc

import com.eygraber.uri.Uri
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.layer.cache.HttpTileSource
import earth.worldwind.layer.source.TileBlob
import io.ktor.client.HttpClientConfig
import kotlin.time.Duration.Companion.seconds

/**
 * [HttpTileSource] for OGC Web Map Service (WMS) elevation endpoints (WMS 1.3.0 GetMap with
 * an elevation-format payload such as `application/bil16`). Parallels [Wcs100ElevationTileSource]:
 * indexes [TileMatrixSet.entries] by `z`, uses top-down `y`, and emits raw bytes for the
 * cache decorator to persist via [earth.worldwind.layer.cache.TileStore].
 *
 * The WMS image-tile equivalent is [WmsTileSource], which is row-indexed by [earth.worldwind.util.LevelSet];
 * this class is the elevation counterpart, row-indexed by [TileMatrixSet] so it composes with
 * [earth.worldwind.globe.elevation.TileSourceElevationSourceFactory] directly.
 */
class WmsElevationTileSource(
    val serviceAddress: String,
    val coverageName: String,
    val outputFormat: String,
    val tileMatrixSet: TileMatrixSet,
    val coordinateSystem: String = "EPSG:4326",
    val wmsVersion: String = "1.3.0",
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
        val url = Uri.parse(serviceAddress).buildUpon().apply {
            appendQueryParameter("VERSION", wmsVersion)
            appendQueryParameter("SERVICE", "WMS")
            appendQueryParameter("REQUEST", "GetMap")
            appendQueryParameter("LAYERS", coverageName)
            appendQueryParameter("STYLES", "")
            appendQueryParameter("WIDTH", matrix.tileWidth.toString())
            appendQueryParameter("HEIGHT", matrix.tileHeight.toString())
            appendQueryParameter("FORMAT", outputFormat)
            appendQueryParameter("TRANSPARENT", "FALSE")
            if (wmsVersion == "1.3.0") {
                appendQueryParameter("CRS", coordinateSystem)
                appendQueryParameter("BBOX", sector.run {
                    if (coordinateSystem == "CRS:84")
                        "${minLongitude.inDegrees},${minLatitude.inDegrees},${maxLongitude.inDegrees},${maxLatitude.inDegrees}"
                    else
                        "${minLatitude.inDegrees},${minLongitude.inDegrees},${maxLatitude.inDegrees},${maxLongitude.inDegrees}"
                })
            } else {
                appendQueryParameter("SRS", coordinateSystem)
                appendQueryParameter("BBOX", sector.run {
                    "${minLongitude.inDegrees},${minLatitude.inDegrees},${maxLongitude.inDegrees},${maxLatitude.inDegrees}"
                })
            }
        }.build().toString()
        return fetchTileBlob(url, previousEtag, previousLastModified, "WMS elevation")?.let(::guardElevation)
    }

    companion object {
        /**
         * Reject responses whose `Content-Type` isn't an elevation MIME — typically a
         * service-exception XML or HTML error page returned at HTTP 200. Without this,
         * those bytes get decoded as garbage shorts and persisted into the cache. We
         * return [TileBlob.EMPTY] (the negative-cache sentinel) rather than `null` so the
         * layer caches "no tile" and stops re-fetching every frame.
         *
         * Blobs that lack a `contentType` (cache reads, source-only sources, or servers
         * that don't advertise one) pass through — the downstream decoder handles them
         * via the requested output format.
         */
        internal fun guardElevation(blob: TileBlob): TileBlob = when {
            blob.isEmpty -> blob
            blob.contentType == null || isElevationMime(blob.contentType) -> blob
            else -> TileBlob.EMPTY
        }

        private fun isElevationMime(contentType: String): Boolean = when {
            contentType.equals("application/bil", true) -> true
            contentType.equals("application/bil16", true) -> true
            contentType.equals("application/bil32", true) -> true
            contentType.equals("image/bil", true) -> true
            contentType.equals("image/tiff", true) -> true
            contentType.equals("image/tif", true) -> true
            contentType.equals("application/dted", true) -> true
            contentType.equals("application/dted0", true) -> true
            contentType.equals("application/dted1", true) -> true
            contentType.equals("application/dted2", true) -> true
            // application/octet-stream is the common fallback for servers that don't
            // advertise a specific MIME — accept it and rely on the decoder.
            contentType.equals("application/octet-stream", true) -> true
            else -> false
        }
    }
}
