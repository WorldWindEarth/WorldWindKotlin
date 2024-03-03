package earth.worldwind.globe.elevation.coverage

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrix
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.CacheSourceFactory
import earth.worldwind.globe.elevation.ElevationDecoder
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.globe.elevation.ElevationSource.Companion.fromUnrecognized
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.util.Logger.DEBUG
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.isLoggable
import earth.worldwind.util.Logger.log
import earth.worldwind.util.ResourcePostprocessor
import io.ktor.client.network.sockets.*
import kotlinx.coroutines.*
import java.io.FileNotFoundException
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.seconds

actual open class TiledElevationCoverage actual constructor(
    tileMatrixSet: TileMatrixSet, elevationSourceFactory: ElevationSourceFactory,
): AbstractTiledElevationCoverage(tileMatrixSet, elevationSourceFactory), CacheableElevationCoverage {
    override var cacheSourceFactory: CacheSourceFactory? = null
    override var isCacheOnly = false
    protected val elevationDecoder = ElevationDecoder()

    /**
     * This is a dummy workaround for asynchronously defined ElevationSourceFactory
     */
    actual constructor(): this(TileMatrixSet(), object : ElevationSourceFactory {
        override val contentType = "Dummy"

        override fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int) = fromUnrecognized(Any())
    })

    /**
     * Makes a copy of this elevation coverage
     */
    actual open fun clone() = TiledElevationCoverage(tileMatrixSet, elevationSourceFactory).also {
        it.displayName = displayName
    }

    /**
     * Start a new coroutine Job that downloads all elevations for a given sector and resolution,
     * without downloading imagery already in the file store.
     *
     * Note that the target resolution must be provided in radians of latitude per texel, which is the resolution in
     * meters divided by the globe radius.
     *
     * @param sector     the sector to download data for.
     * @param resolution the desired resolution range in angular value of latitude per texel.
     * @param scope      custom coroutine scope for better job management. Global scope by default.
     * @param onProgress an optional retrieval listener, indication successful, failed and total tiles amount.
     *
     * @return the coroutine Job executing the retrieval or `null` if the specified sector does
     * not intersect the elevation model bounding sector.
     *
     * @throws IllegalStateException if cache is not configured.
     * @throws IllegalArgumentException if sector does not intersect elevation coverage sector
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    fun makeLocal(
        sector: Sector, resolution: ClosedRange<Angle>, scope: CoroutineScope = GlobalScope, onProgress: ((Int, Int, Int) -> Unit)? = null
    ): Job {
        val cacheTileFactory = cacheSourceFactory ?: error("Cache not configured")
        require(sector.intersect(tileMatrixSet.sector)) { "Sector does not intersect elevation coverage sector" }
        return scope.launch(Dispatchers.Default) {
            // Prepare a tile list for download, based on specified sector and resolution
            val processingList = assembleTilesList(sector, resolution)
            var downloaded = 0
            var skipped = 0
            // Try to download each tile in a list
            for (tile in processingList) {
                var attempt = 0
                // Retry download attempts till success or 404 not fond or job canceled
                while(true) {
                    // Check if a job is canceled
                    ensureActive()
                    // Attempt to download tile
                    try {
                        ++attempt
                        val cacheSource = cacheTileFactory.createElevationSource(tile.tileMatrix, tile.row, tile.col)
                        // Check if tile exists in cache. If cache retrieval fail, then normal tile source will be requested.
                        // TODO If retrieved cache source is outdated, then retrieve original tile source to refresh cache
                        val success = elevationDecoder.run {
                            decodeElevation(cacheSource) ?: decodeElevation(
                                elevationSourceFactory.createElevationSource(tile.tileMatrix, tile.row, tile.col).also {
                                    // Assign buffer postprocessor to save retrieved online tile to cache
                                    val source = cacheSource.asUnrecognized()
                                    if (source is ResourcePostprocessor) it.postprocessor = source
                                }
                            )
                        }?.let {
                            // Un-mark tile key from absent list
                            launch(Dispatchers.Main) {
                                absentResourceList.unmarkResourceAbsent(tile.tileMatrix.tileKey(tile.row, tile.col))
                            }
                            true
                        } ?: false
                        // Tile successfully downloaded
                        if (success) onProgress?.invoke(++downloaded, skipped, processingList.size)
                        // Received data cannot be decoded as image
                        else onProgress?.invoke(downloaded, ++skipped, processingList.size)
                        break // Continue downloading the next tile
                    } catch (throwable: Throwable) {
                        delay(if(attempt % makeLocalRetries == 0) makeLocalTimeoutLong else makeLocalTimeoutShort)
                    }
                }
            }
        }
    }

    // TODO If retrieved cache source is outdated, than try to retrieve online source anyway to refresh cache
    override suspend fun retrieveTileArray(key: Long, tileMatrix: TileMatrix, row: Int, column: Int) {
        // Determine a cache source if cache tile factory is specified
        val cacheSource = cacheSourceFactory?.createElevationSource(tileMatrix, row, column)
        try {
            // Try to retrieve a cache source first
            cacheSource?.let {
                elevationDecoder.decodeElevation(cacheSource)?.also { retrievalSucceeded(key, cacheSource, it) }
            }
        } catch (logged: Throwable) {
            null // Ignore cache decoding exceptions
        } ?: run {
            // Read cache only in case of elevation source factory and cache factory are the same
            val isCacheOnly = isCacheOnly || elevationSourceFactory == cacheSourceFactory
            // Check if an online source is enabled. Cache source must exist to be able to disable an online source.
            if (!isCacheOnly || cacheSource == null) {
                // Determine online source
                val onlineSource = elevationSourceFactory.createElevationSource(tileMatrix, row, column)
                // Assign buffer postprocessor to save retrieved online tile to cache
                val source = cacheSource?.asUnrecognized()
                if (source is ResourcePostprocessor) onlineSource.postprocessor = source
                try {
                    // Try to retrieve an online source
                    elevationDecoder.decodeElevation(onlineSource)?.also { retrievalSucceeded(key, onlineSource, it) }
                        ?: retrievalFailed(key, onlineSource)
                } catch (logged: Throwable) {
                    retrievalFailed(key, onlineSource, logged)
                }
            } else retrievalFailed(key, cacheSource)
        }
    }

    protected open fun retrievalSucceeded(key: Long, source: ElevationSource, value: ShortArray) {
        retrievalSucceeded(key, value)
        if (isLoggable(DEBUG)) log(DEBUG, "Coverage retrieval succeeded '$source'")
    }

    protected open fun retrievalFailed(key: Long, source: ElevationSource, ex: Throwable? = null) {
        retrievalFailed(key)
        when {
            // log "socket timeout" exceptions while suppressing the stack trace
            ex is ConnectTimeoutException -> log(WARN, "Connect timeout retrieving coverage '$source'")
            ex is SocketTimeoutException -> log(WARN, "Socket timeout retrieving coverage '$source'")
            // log "file not found" exceptions while suppressing the stack trace
            ex is FileNotFoundException -> log(WARN, "Coverage not found '$source'")
            // log checked exceptions with the entire stack trace
            ex != null -> log(WARN, "Coverage retrieval failed with exception '$source': ${ex.message}")
            else -> log(WARN, "Coverage retrieval failed '$source'")
        }
    }

    companion object {
        /**
         * Number of reties of bulk tile retrieval before long timeout
         */
        var makeLocalRetries = 3
        /**
         * Short timeout on bulk tile retrieval failed
         */
        var makeLocalTimeoutShort = 5.seconds
        /**
         * Long timeout on bulk tile retrieval failed
         */
        var makeLocalTimeoutLong = 15.seconds
    }
}