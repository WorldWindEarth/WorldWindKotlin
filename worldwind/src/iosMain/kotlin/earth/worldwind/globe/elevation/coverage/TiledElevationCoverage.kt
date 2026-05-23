@file:OptIn(ExperimentalForeignApi::class)

package earth.worldwind.globe.elevation.coverage

import earth.worldwind.formats.dted.DTED
import earth.worldwind.formats.geotiff.GeoTiffReader
import earth.worldwind.geom.TileMatrix
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.util.Logger.DEBUG
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.isLoggable
import earth.worldwind.util.Logger.log
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * iOS port of TiledElevationCoverage. Mirrors the JS impl: fetches the URL with Ktor,
 * dispatches by `Content-Type`, and decodes the response in-process. Currently supports
 * `application/bil16` (little-endian int16) and `application/bil32` (little-endian
 * float32) — what `BasicElevationCoverage` (and most WMS-elevation servers) returns.
 *
 * TIFF/PNG elevation tiles still fail back to the WGS-84 ellipsoid; that's a small
 * follow-up if any consumer wires a coverage with those formats.
 */
actual open class TiledElevationCoverage actual constructor(
    tileMatrixSet: TileMatrixSet, elevationSourceFactory: ElevationSourceFactory
) : AbstractTiledElevationCoverage(tileMatrixSet, elevationSourceFactory) {

    private val httpClient = DefaultHttpClient()

    actual open fun clone() = TiledElevationCoverage(tileMatrixSet, elevationSourceFactory).also {
        it.displayName = displayName
        it.sector.copy(sector)
    }

    actual override suspend fun retrieveTileArray(key: Long, tileMatrix: TileMatrix, row: Int, column: Int) {
        val elevationSource = elevationSourceFactory.createElevationSource(tileMatrix, row, column)
        if (!elevationSource.isUrl) {
            retrievalFailed(key, "Unsupported elevation source type: $elevationSource")
            return
        }
        val url = elevationSource.asUrl()
        try {
            val response = httpClient.get(url)
            if (response.status != HttpStatusCode.OK) {
                retrievalFailed(key, "Elevation retrieval failed (${response.status}): $url")
                return
            }
            val bytes = response.readRawBytes()
            val contentType = response.contentType()?.toString().orEmpty()
            val pixels = withContext(Dispatchers.Default) {
                when {
                    contentType.equals("image/bil", true) ||
                    contentType.equals("application/bil", true) ||
                    contentType.equals("application/bil16", true) -> bilToShortArray(bytes)
                    contentType.equals("application/bil32", true) -> bil32ToShortArray(bytes)
                    contentType.equals("image/tiff", true) -> GeoTiffReader(bytes).createElevationShortArray()
                    contentType.equals("application/dted", true) ||
                    contentType.equals("application/dted0", true) ||
                    contentType.equals("application/dted1", true) ||
                    contentType.equals("application/dted2", true) -> DTED(bytes).elevations
                    else -> {
                        log(WARN, "Unsupported elevation content type '$contentType' for $url")
                        null
                    }
                }
            }
            if (pixels != null) {
                if (isLoggable(DEBUG)) log(DEBUG, "Elevation retrieval succeeded: $url")
                retrievalSucceeded(key, pixels)
            } else {
                retrievalFailed(key, "Elevation decode produced no values: $url")
            }
        } catch (e: Throwable) {
            retrievalFailed(key, "Elevation retrieval failed (${e.message}): $url")
        }
    }

    private fun bilToShortArray(bytes: ByteArray): ShortArray {
        // BIL16 is little-endian signed int16, tightly packed.
        val count = bytes.size / 2
        val out = ShortArray(count)
        var i = 0
        var b = 0
        while (i < count) {
            val lo = bytes[b].toInt() and 0xFF
            val hi = bytes[b + 1].toInt() and 0xFF
            out[i] = ((hi shl 8) or lo).toShort()
            i++; b += 2
        }
        return out
    }

    private fun bil32ToShortArray(bytes: ByteArray): ShortArray {
        // BIL32 is little-endian IEEE float32. Convert to int16 the same way JVM/JS do —
        // null-data sentinel `Float.MAX_VALUE` becomes `Short.MIN_VALUE`, everything else
        // rounds to the nearest int16.
        val count = bytes.size / 4
        val out = ShortArray(count)
        var i = 0
        var b = 0
        while (i < count) {
            val raw = (bytes[b].toInt() and 0xFF) or
                ((bytes[b + 1].toInt() and 0xFF) shl 8) or
                ((bytes[b + 2].toInt() and 0xFF) shl 16) or
                ((bytes[b + 3].toInt() and 0xFF) shl 24)
            val value = Float.fromBits(raw)
            out[i] = if (value == Float.MAX_VALUE) Short.MIN_VALUE else value.roundToInt().toShort()
            i++; b += 4
        }
        return out
    }

    protected open fun retrievalFailed(key: Long, message: String) {
        retrievalFailed(key)
        log(WARN, message)
    }
}
