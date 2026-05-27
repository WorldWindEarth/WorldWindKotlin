package earth.worldwind.globe.elevation.coverage

import earth.worldwind.formats.dted.DTED
import earth.worldwind.formats.geotiff.GeoTiffReader
import earth.worldwind.geom.TileMatrix
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.layer.cache.WebTileCache
import earth.worldwind.util.Logger.DEBUG
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.isLoggable
import earth.worldwind.util.Logger.log
import io.ktor.client.fetch.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.withContext
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int16Array
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import kotlin.math.roundToInt

actual open class TiledElevationCoverage actual constructor(
    tileMatrixSet: TileMatrixSet, elevationSourceFactory: ElevationSourceFactory
) : AbstractTiledElevationCoverage(tileMatrixSet, elevationSourceFactory) {
    /**
     * Makes a copy of this elevation coverage
     */
    actual open fun clone() = TiledElevationCoverage(tileMatrixSet, elevationSourceFactory).also {
        it.displayName = displayName
        it.sector.copy(sector)
    }

    actual override suspend fun retrieveTileArray(key: Long, tileMatrix: TileMatrix, row: Int, column: Int) {
        val elevationSource = elevationSourceFactory.createElevationSource(tileMatrix, row, column)
        if (elevationSource.isUrl) {
            val url = elevationSource.asUrl()
            try {
                // Ktor JS Client cannot be used here, because it is not able to return ArrayBuffer directly.
                // Route through WebTileCache when this URL prefix has a registered store.
                val storeName = WebTileCache.storeNameFor(url)
                val response = if (storeName != null) WebTileCache.fetchWithCache(url, storeName)
                else fetch(url).await()
                if (response.ok) {
                    val arrayBuffer = response.arrayBuffer().await()
                    val contentType = response.headers.get("Content-Type")
                    var message: String? = null
                    val pixels = when {
                        contentType.equals("image/bil", true) ||
                        contentType.equals("application/bil", true) ||
                        contentType.equals("application/bil16", true) -> Int16ArrayToShortArray(Int16Array(arrayBuffer))
                        contentType.equals("application/bil32", true) -> Float32ArrayToShortArray(Float32Array(arrayBuffer))
                        contentType.equals("image/tiff", true) -> decodeTiff(arrayBuffer)
                        contentType.equals("application/dted", true) ||
                        contentType.equals("application/dted0", true) ||
                        contentType.equals("application/dted1", true) ||
                        contentType.equals("application/dted2", true) -> decodeDted(arrayBuffer)
                        contentType.equals("text/xml", true) -> {
                            // The XML body usually carries a server error message; decode and append
                            // it so the failure log is actionable. The leading `+` continuation here
                            // previously parsed as a unary-plus expression-statement, so the decoded
                            // text never reached [message] — fixed by chaining the `+` onto the prior
                            // string literal.
                            message = "Elevations retrieval failed (${response.statusText}): $url.\n" +
                                js("new TextDecoder().decode(arrayBuffer)").unsafeCast<String>()
                            null
                        }
                        else -> {
                            message = "Elevations retrieval failed (Unexpected content type $contentType): $url"
                            null
                        }
                    }
                    if (pixels != null) {
                        retrievalSucceeded(key, pixels, "Elevation retrieval succeeded: $url")
                    } else {
                        retrievalFailed(key, message ?: "Elevations retrieval failed: $url")
                    }
                } else {
                    retrievalFailed(key, "Elevations retrieval failed (${response.statusText}): $url")
                }
            } catch (e: Throwable) {
                retrievalFailed(key, "Elevations retrieval failed (${e.message}): $url")
            }
        } else retrievalFailed(key, "Unsupported elevation source type")
    }

    /** Decode a TIFF/GeoTIFF buffer into a `ShortArray` via the cross-platform reader.
     *  Same code path JVM and iOS use; replaces the prior typed-array detour. */
    private suspend fun decodeTiff(arrayBuffer: ArrayBuffer): ShortArray = withContext(Dispatchers.Default) {
        GeoTiffReader(arrayBufferToBytes(arrayBuffer)).createElevationShortArray()
    }

    /** Decode a DTED (MIL-PRF-89020B) buffer into a `ShortArray`. */
    private suspend fun decodeDted(arrayBuffer: ArrayBuffer): ShortArray = withContext(Dispatchers.Default) {
        DTED(arrayBufferToBytes(arrayBuffer)).elevations
    }

    private fun arrayBufferToBytes(arrayBuffer: ArrayBuffer): ByteArray {
        val bytes = ByteArray(arrayBuffer.byteLength)
        val view = Int8Array(arrayBuffer)
        for (i in 0 until view.length) bytes[i] = view[i]
        return bytes
    }

    private fun Int16ArrayToShortArray(buffer: Int16Array): ShortArray =
        ShortArray(buffer.length) { buffer[it] }

    private fun Float32ArrayToShortArray(buffer: Float32Array): ShortArray = ShortArray(buffer.length) {
        val value = buffer[it]
        if (value == Float.MAX_VALUE) Short.MIN_VALUE else value.roundToInt().toShort()
    }

    protected open fun retrievalSucceeded(key: Long, value: ShortArray, message: String) {
        retrievalSucceeded(key, value)
        if (isLoggable(DEBUG)) log(DEBUG, message)
    }

    protected open fun retrievalFailed(key: Long, message: String) {
        retrievalFailed(key)
        log(WARN, message)
    }
}
