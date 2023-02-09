package earth.worldwind.globe.elevation.coverage

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrix
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationDecoder
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.globe.elevation.ElevationSource.Companion.fromUnrecognized
import earth.worldwind.globe.elevation.ElevationTileFactory
import earth.worldwind.ogc.GpkgElevationFactory
import earth.worldwind.ogc.GpkgElevationTileFactory
import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.ogc.gpkg.GpkgContent
import earth.worldwind.util.Logger.DEBUG
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.isLoggable
import earth.worldwind.util.Logger.log
import io.ktor.client.network.sockets.*
import kotlinx.coroutines.*
import java.io.FileNotFoundException
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.seconds

actual open class TiledElevationCoverage actual constructor(
    tileMatrixSet: TileMatrixSet, tileFactory: ElevationTileFactory,
): AbstractTiledElevationCoverage(tileMatrixSet, tileFactory) {
    /**
     * This is a dummy workaround for asynchronously defined TileFactory
     */
    actual constructor(): this(TileMatrixSet(), object : ElevationTileFactory {
        override fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int) = fromUnrecognized(Any())
    })

    protected actual val mainScope = MainScope()
    protected val elevationDecoder = ElevationDecoder()
    protected var cacheTileFactory: ElevationTileFactory? = null
    protected var cacheContent: GpkgContent? = null
    /**
     * Checks if cache is successfully configured
     */
    val isCacheConfigured get() = cacheTileFactory != null
    /**
     * Configures tiled elevation coverage to work with cache source only
     */
    var useCacheOnly = false
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

    override fun invalidateTiles() {
        mainScope.coroutineContext.cancelChildren() // Cancel all async jobs but keep scope reusable
        super.invalidateTiles()
    }

    /**
     * Configures current tiled elevation coverage to use GeoPackage database file as a cache provider.
     *
     * @param pathName Full path to GeoPackage database file. If not exists, it will be created.
     * @param tableName Name of content table inside GeoPackage database file.
     * @param readOnly Do not create GeoPackage database file and do not save newly downloaded tiles to it. Read existing tiles only.
     * @param isFloat If true, then cache will be stored in Float32 format, else Int16.
     *
     * @return Cache configured successfully
     * @throws IllegalArgumentException In case of incompatible matrix set configured in cache content.
     * @throws IllegalStateException In case of new content creation required on read-only database.
     */
    @JvmOverloads
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    suspend fun configureCache(pathName: String, tableName: String, readOnly: Boolean = false, isFloat: Boolean = false) {
        val geoPackage = GeoPackage(pathName, readOnly)
        val content = geoPackage.content.firstOrNull { it.tableName == tableName }?.also {
            // Check if current layer fits cache content
            val matrixSet = geoPackage.buildTileMatrixSet(it)
            require(matrixSet.sector == tileMatrixSet.sector) { "Invalid sector" }
            requireNotNull(geoPackage.griddedCoverages.firstOrNull { gc ->
                gc.tileMatrixSetName == tableName && gc.datatype == if (isFloat) "float" else "integer"
            }) { "Invalid data type" }
            // Verify if all required tile matrices created
            if (matrixSet.entries.size < tileMatrixSet.entries.size) geoPackage.setupTileMatrices(tableName, tileMatrixSet)
        } ?: geoPackage.setupGriddedCoverageContent(tableName, displayName ?: tableName, tileMatrixSet, isFloat)

        cacheContent = content
        cacheTileFactory = GpkgElevationTileFactory(content, isFloat)
    }

    /**
     * Removes cache provider from current tiled elevation coverage.
     */
    fun disableCache() {
        cacheContent = null
        cacheTileFactory = null
    }

    /**
     * Deletes all tiles from current cache storage, but keeps cache metadata.
     *
     * @throws IllegalStateException In case of read-only database.
     */
    @Throws(IllegalStateException::class)
    suspend fun clearCache() = cacheContent?.run { container.clearContent(tableName) }

    /**
     * Deletes cache storage with all metadata and disables cache
     *
     * @throws IllegalStateException In case of read-only database.
     */
    @Throws(IllegalStateException::class)
    suspend fun deleteCache() = cacheContent?.run { container.deleteContent(tableName) }.also { disableCache() }

    /**
     * Start a new coroutine Job that downloads all elevations for a given sector and resolution,
     * without downloading imagery already in the file store.
     *
     * Note that the target resolution must be provided in radians of latitude per texel, which is the resolution in
     * meters divided by the globe radius.
     *
     * @param sector     the sector to download data for.
     * @param resolution the target resolution in angular value of latitude per texel.
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
        sector: Sector, resolution: Angle, scope: CoroutineScope = GlobalScope, onProgress: ((Int, Int, Int) -> Unit)? = null
    ): Job {
        val cacheTileFactory = cacheTileFactory ?: error("Cache not configured")
        require(sector.intersect(tileMatrixSet.sector)) { "Sector does not intersect elevation coverage sector" }
        return scope.launch(Dispatchers.IO) {
            // Prepare tile list for download, based on specified sector and resolution
            val processingList = assembleTilesList(sector, resolution)
            var downloaded = 0
            var skipped = 0
            // Try to download each tile in a list
            for (tile in processingList) {
                var attempt = 0
                // Retry download attempts till success or 404 not fond or job cancelled
                while(true) {
                    // Check if job cancelled
                    ensureActive()
                    // Attempt to download tile
                    try {
                        ++attempt
                        val cacheSource = cacheTileFactory.createElevationSource(tile.tileMatrix, tile.row, tile.col)
                        // Check if tile exists in cache. If cache retrieval fail, then normal tile source will be requested.
                        // TODO If retrieved cache source is outdated, then retrieve original tile source to refresh cache
                        val success = elevationDecoder.run {
                            decodeElevation(cacheSource) ?: decodeElevation(
                                tileFactory.createElevationSource(tile.tileMatrix, tile.row, tile.col).also {
                                    // Assign buffer postprocessor to save retrieved online tile to cache
                                    val source = cacheSource.asUnrecognized()
                                    if (source is GpkgElevationFactory) it.postprocessor = source
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
                        // Received data can not be decoded as image
                        else onProgress?.invoke(downloaded, ++skipped, processingList.size)
                        break // Continue downloading next tile
                    } catch (throwable: Throwable) {
                        when (throwable) {
                            is FileNotFoundException -> {
                                onProgress?.invoke(downloaded, ++skipped, processingList.size)
                                break // Skip missed tile and continue download next one
                            }
                            // Wait some time and retry
                            else -> delay(if(attempt % makeLocalRetries == 0) makeLocalTimeoutLong else makeLocalTimeoutShort)
                        }
                    }
                }
            }
        }
    }

    // TODO If retrieved cache source is outdated, than try to retrieve online source anyway to refresh cache
    override fun retrieveTileArray(key: Long, tileMatrix: TileMatrix, row: Int, column: Int) {
        mainScope.launch(Dispatchers.IO) {
            // Determine cache source, if cache tile factory is specified
            val cacheSource = cacheTileFactory?.createElevationSource(tileMatrix, row, column)
            try {
                // Try to retrieve cache source first
                cacheSource?.let {
                    elevationDecoder.decodeElevation(cacheSource)?.also { retrievalSucceeded(key, cacheSource, it) }
                }
            } catch (logged: Throwable) {
                null // Ignore cache decoding exceptions
            } ?: run {
                // Check if online source is enabled. Cache source must exist to be able to disable online source.
                if (!useCacheOnly || cacheSource == null) {
                    // Determine online source
                    val onlineSource = tileFactory.createElevationSource(tileMatrix, row, column)
                    // Assign buffer postprocessor to save retrieved online tile to cache
                    val source = cacheSource?.asUnrecognized()
                    if (source is GpkgElevationFactory) onlineSource.postprocessor = source
                    try {
                        // Try to retrieve online source
                        elevationDecoder.decodeElevation(onlineSource)?.also { retrievalSucceeded(key, onlineSource, it) }
                            ?: retrievalFailed(key, onlineSource)
                    } catch (logged: Throwable) {
                        retrievalFailed(key, onlineSource, logged)
                    }
                } else retrievalFailed(key, cacheSource)
            }
        }
    }

    protected open suspend fun retrievalSucceeded(key: Long, source: ElevationSource, value: ShortArray) = withContext(Dispatchers.Main) {
        retrievalSucceeded(key, value)
        if (isLoggable(DEBUG)) log(DEBUG, "Coverage retrieval succeeded '$source'")
    }

    protected open suspend fun retrievalFailed(key: Long, source: ElevationSource, ex: Throwable? = null) = withContext(Dispatchers.Main) {
        retrievalFailed(key)
        when {
            // log socket timeout exceptions while suppressing the stack trace
            ex is ConnectTimeoutException -> log(WARN, "Connect timeout retrieving coverage '$source'")
            ex is SocketTimeoutException -> log(WARN, "Socket timeout retrieving coverage '$source'")
            // log file not found exceptions while suppressing the stack trace
            ex is FileNotFoundException -> log(WARN, "Coverage not found '$source'")
            // log checked exceptions with the entire stack trace
            ex != null -> log(WARN, "Coverage retrieval failed with exception '$source'", ex)
            else -> log(WARN, "Coverage retrieval failed '$source'")
        }
    }
}