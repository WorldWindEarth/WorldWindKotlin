@file:OptIn(ExperimentalForeignApi::class)

package earth.worldwind.globe.elevation.coverage

import earth.worldwind.formats.dted.DTED
import earth.worldwind.formats.geotiff.GeoTiffReader
import earth.worldwind.geom.TileMatrix
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.globe.elevation.TileSourceElevationRef
import earth.worldwind.layer.cache.CachedElevationRef
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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * iOS port of TiledElevationCoverage. Two retrieval paths:
 *   * [TileSourceElevationRef] — cache-first reads through the
 *     [earth.worldwind.layer.cache.CachedTileSource] decorator. Output format comes from
 *     the ref, no Content-Type negotiation needed.
 *   * URL — direct Ktor GET, dispatched by `Content-Type` header.
 *
 * Both paths share [decodeBytes] which understands `application/bil16`, `application/bil32`,
 * `image/tiff` (GeoTIFF), and `application/dted{,0,1,2}`. PNG-encoded elevation tiles fall
 * through with a WARN — add a decoder if any consumer wires that format.
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
        val raw = elevationSource.asUnrecognized()
        // Cross-platform cached path: codec-driven, IDB/filesystem-backed, transcoded.
        if (raw is CachedElevationRef) {
            try {
                val shorts = raw.factory.fetchTile(raw.z, raw.x, raw.y)
                val expected = tileMatrix.tileWidth * tileMatrix.tileHeight
                when {
                    shorts == null -> retrievalFailed(key, "CachedElevation produced no data for ($key)")
                    shorts.size != expected -> retrievalFailed(
                        key, "CachedElevation size mismatch — got ${shorts.size} shorts, expected $expected"
                    )
                    else -> retrievalSucceeded(key, shorts)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Throwable) {
                retrievalFailed(key, "CachedElevation retrieval failed (${e.message})")
            }
            return
        }
        // Tile-source path: cache-first reads via the TileSource decorator; outputFormat
        // comes from the ref so we don't depend on Content-Type negotiation.
        if (raw is TileSourceElevationRef) {
            try {
                val blob = raw.source.fetchTile(raw.z, raw.x, raw.y)
                if (blob == null || blob.isEmpty) {
                    retrievalFailed(key, "Elevation tile-source returned no data for ($key)")
                    return
                }
                // Prefer the server's actual Content-Type when present — catches an XML
                // service-exception returned at HTTP 200. Cache hits typically have null
                // contentType (we don't persist it); fall back to the requested format,
                // which is safe because the source-side validation rejects bad responses
                // before they reach the cache.
                // Specific server Content-Type wins; cache hits (null) AND generic
                // application/octet-stream responses fall back to the requested format.
                // See ElevationDecoder.decodeTileSource (jvmCommonMain) for the rationale.
                val serverType = blob.contentType
                val format =
                    if (serverType == null || serverType.equals("application/octet-stream", ignoreCase = true))
                        raw.outputFormat
                    else serverType
                val pixels = withContext(Dispatchers.Default) { decodeBytes(blob.bytes, format) }
                val expected = tileMatrix.tileWidth * tileMatrix.tileHeight
                when {
                    pixels == null -> retrievalFailed(key, "Elevation tile decode produced no values for ($key)")
                    pixels.size != expected -> retrievalFailed(
                        key, "Elevation tile-source size mismatch — got ${pixels.size} shorts, expected $expected"
                    )
                    else -> retrievalSucceeded(key, pixels)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Throwable) {
                retrievalFailed(key, "Elevation tile-source retrieval failed (${e.message})")
            }
            return
        }
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
            val pixels = withContext(Dispatchers.Default) { decodeBytes(bytes, contentType) }
            if (pixels != null) {
                if (isLoggable(DEBUG)) log(DEBUG, "Elevation retrieval succeeded: $url")
                retrievalSucceeded(key, pixels)
            } else {
                retrievalFailed(key, "Elevation decode produced no values: $url")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Throwable) {
            retrievalFailed(key, "Elevation retrieval failed (${e.message}): $url")
        }
    }

    /** Dispatch a byte payload to the per-format decoder. Returns null for unsupported
     *  Content-Types — callers report this as a retrieval failure. */
    private fun decodeBytes(bytes: ByteArray, contentType: String): ShortArray? = when {
        contentType.equals("image/bil", true) ||
            contentType.equals("application/bil", true) ||
            contentType.equals("application/bil16", true) -> bilToShortArray(bytes)
        contentType.equals("application/bil32", true) -> bil32ToShortArray(bytes)
        // Accept the OGC MIME plus the bare WCS/WMS FORMAT spellings so cache replays don't
        // need the precise response Content-Type to round-trip.
        contentType.equals("image/tiff", true) ||
            contentType.equals("image/geotiff", true) ||
            contentType.equals("application/geotiff", true) ||
            contentType.equals("geotiff", true) ||
            contentType.equals("tiff", true) -> GeoTiffReader(bytes).createElevationShortArray()
        contentType.equals("application/dted", true) ||
            contentType.equals("application/dted0", true) ||
            contentType.equals("application/dted1", true) ||
            contentType.equals("application/dted2", true) -> DTED(bytes).elevations
        else -> {
            log(WARN, "Unsupported elevation content type '$contentType'")
            null
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
