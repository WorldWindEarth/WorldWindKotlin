package earth.worldwind.globe.elevation.coverage

import earth.worldwind.geom.TileMatrix
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationDecoder
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.util.Logger.DEBUG
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.isLoggable
import earth.worldwind.util.Logger.log
import io.ktor.client.network.sockets.*
import java.io.FileNotFoundException
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException

actual open class TiledElevationCoverage actual constructor(
    tileMatrixSet: TileMatrixSet, elevationSourceFactory: ElevationSourceFactory,
): AbstractTiledElevationCoverage(tileMatrixSet, elevationSourceFactory), CacheableElevationCoverage {
    protected val elevationDecoder = ElevationDecoder()

    /** Makes a copy of this elevation coverage. */
    actual open fun clone() = TiledElevationCoverage(tileMatrixSet, elevationSourceFactory).also {
        it.displayName = displayName
        it.sector.copy(sector)
    }

    actual override suspend fun retrieveTileArray(key: Long, tileMatrix: TileMatrix, row: Int, column: Int) {
        val source = elevationSourceFactory.createElevationSource(tileMatrix, row, column)
        try {
            val decoded = elevationDecoder.decodeElevation(source)
            val expected = tileMatrix.tileWidth * tileMatrix.tileHeight
            when {
                decoded == null -> retrievalFailed(key, source)
                // Reject short / oversized payloads (typically a service-error XML returned
                // at HTTP 200, or a truncated transfer). Without this guard the bytes get
                // cached and crash `scanHeightLimits` later with AIOOBE.
                decoded.size != expected -> {
                    log(WARN, "Coverage retrieval size mismatch '$source' — got ${decoded.size} shorts, expected $expected")
                    retrievalFailed(key, source)
                }
                else -> retrievalSucceeded(key, source, buildImage(decoded))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (logged: Throwable) {
            retrievalFailed(key, source, logged)
        }
    }

    protected open fun retrievalSucceeded(key: Long, source: ElevationSource, image: ElevationImage) {
        retrievalSucceeded(key, image)
        if (isLoggable(DEBUG)) log(DEBUG, "Coverage retrieval succeeded '$source'")
    }

    protected open fun retrievalFailed(key: Long, source: ElevationSource, ex: Throwable? = null) {
        retrievalFailed(key)
        when {
            // log "socket timeout" exceptions while suppressing the stack trace
            ex is SocketTimeoutException || ex is ConnectTimeoutException -> log(WARN, "Coverage retrieval timeout '$source'")
            // log "file not found" exceptions while suppressing the stack trace
            ex is FileNotFoundException -> log(WARN, "Coverage retrieval failed (404 not found) '$source'")
            // log checked exceptions with the entire stack trace
            ex != null -> log(WARN, "Coverage retrieval failed with exception '$source': ${ex::class.simpleName}: ${ex.message}")
            else -> log(WARN, "Coverage retrieval failed '$source'")
        }
    }
}
