package earth.worldwind.globe.elevation.coverage

import earth.worldwind.geom.TileMatrix
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationSource.Companion.fromUnrecognized
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.util.Logger.DEBUG
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.isLoggable
import earth.worldwind.util.Logger.log
import io.ktor.client.fetch.*
import kotlinx.coroutines.await
import org.khronos.webgl.*
import org.khronos.webgl.ArrayBufferView
import org.khronos.webgl.Uint8Array
import kotlin.math.roundToInt

actual open class TiledElevationCoverage actual constructor(
    tileMatrixSet: TileMatrixSet, elevationSourceFactory: ElevationSourceFactory
) : AbstractTiledElevationCoverage(tileMatrixSet, elevationSourceFactory) {
    /**
     * This is a dummy workaround for asynchronously defined ElevationSourceFactory
     */
    actual constructor(): this(TileMatrixSet(), object : ElevationSourceFactory {
        override fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int) = fromUnrecognized(Any())
    })

    override suspend fun retrieveTileArray(key: Long, tileMatrix: TileMatrix, row: Int, column: Int) {
        val elevationSource = elevationSourceFactory.createElevationSource(tileMatrix, row, column)
        if (elevationSource.isUrl) {
            val url = elevationSource.asUrl()
            try {
                // Ktor JS Client cannot be used here, because it is not able to return ArrayBuffer directly.
                val response = fetch(url).await()
                if (response.ok) {
                    val arrayBuffer = response.arrayBuffer().await()
                    val contentType = response.headers.get("Content-Type")
                    var message: String? = null
                    val buffer = when {
                        contentType.equals("image/bil", true) ||
                        contentType.equals("application/bil", true) ||
                        contentType.equals("application/bil16", true) -> Int16Array(arrayBuffer)
                        contentType.equals("application/bil32", true) -> Float32Array(arrayBuffer)
                        contentType.equals("image/tiff", true) -> TODO("Implement Tiff parsing for JS")
                        contentType.equals("text/xml", true) -> {
                            message = "Elevations retrieval failed (${response.statusText}): $url.\n"
                            +String.asDynamic().fromCharCode.apply(null, Uint8Array(arrayBuffer))
                            null
                        }
                        else -> {
                            message = "Elevations retrieval failed (Unexpected content type $contentType): $url"
                            null
                        }
                    }?.let { elevationSource.postprocessor?.process(it) ?: it } // Apply buffer transformations
                    decodeBuffer(buffer)?.let {
                        retrievalSucceeded(key, it, "Elevation retrieval succeeded: $url")
                    } ?: retrievalFailed(key, message ?: "Elevations retrieval failed: $url")
                } else {
                    retrievalFailed(key, "Elevations retrieval failed (${response.statusText}): $url")
                }
            } catch (e: Throwable) {
                retrievalFailed(key, "Elevations retrieval failed (${e.message}): $url")
            }
        } else retrievalFailed(key, "Unsupported elevation source type")
    }

    protected open fun decodeBuffer(buffer: ArrayBufferView?) = when (buffer) {
        is Int16Array -> ShortArray(buffer.length) { buffer[it] }
        is Float32Array -> ShortArray(buffer.length) { buffer[it].roundToInt().toShort() }
        else -> null
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