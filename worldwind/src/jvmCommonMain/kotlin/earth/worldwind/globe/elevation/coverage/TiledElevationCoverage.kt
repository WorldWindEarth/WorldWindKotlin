package earth.worldwind.globe.elevation.coverage

import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrix
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationDecoder
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.globe.elevation.ElevationSource.Companion.fromElevationFactory
import earth.worldwind.globe.elevation.ElevationSource.Companion.fromUnrecognized
import earth.worldwind.globe.elevation.ElevationTileFactory
import earth.worldwind.ogc.GpkgElevationFactory
import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.ogc.gpkg.GpkgContent
import earth.worldwind.util.Logger.DEBUG
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.isLoggable
import earth.worldwind.util.Logger.log
import earth.worldwind.util.Logger.logMessage
import io.ktor.client.network.sockets.*
import kotlinx.coroutines.*
import java.io.FileNotFoundException
import java.net.SocketTimeoutException

actual open class TiledElevationCoverage actual constructor(
    tileMatrixSet: TileMatrixSet, tileFactory: ElevationTileFactory,
): AbstractTiledElevationCoverage(tileMatrixSet, tileFactory) {
    /**
     * This is a dummy workaround for asynchronously defined TileFactory
     */
    actual constructor(): this(TileMatrixSet(), object : ElevationTileFactory {
        override fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int) = fromUnrecognized(Any())
    })

    // WorldWindow main scope cannot be injected due to constructor is common on all platforms.
    protected actual val mainScope = MainScope() // Use own scope with the same livecycle as WorldWindow main scope.
    protected val elevationDecoder = ElevationDecoder()
    protected var cacheTileFactory: ElevationTileFactory? = null
    protected var cacheContent: GpkgContent? = null
    var useCacheOnly = false

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
     */
    suspend fun configureCache(pathName: String, tableName: String, readOnly: Boolean = false, isFloat: Boolean = false): Boolean {
        return try {
            val geoPackage = GeoPackage(pathName, readOnly)
            val content = geoPackage.content.firstOrNull { it.tableName == tableName }?.also {
                // Check if current layer fits cache content
                val matrixSet = geoPackage.buildTileMatrixSet(it)
                require(matrixSet.sector == tileMatrixSet.sector) { "Invalid sector" }
                require(matrixSet.entries.size == tileMatrixSet.entries.size) { "Invalid number of matrices" }
                requireNotNull(geoPackage.griddedCoverages.firstOrNull { gc ->
                    gc.tileMatrixSetName == tableName && gc.datatype == if (isFloat) "float" else "integer"
                }) { "Invalid data type" }
            } ?: geoPackage.setupGriddedCoverageContent(tableName, displayName ?: tableName, tileMatrixSet, isFloat)

            cacheContent = content
            cacheTileFactory = object : ElevationTileFactory {
                override fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int): ElevationSource {
                    // Convert the WorldWind tile row to the equivalent GeoPackage tile row.
                    val gpkgRow = tileMatrix.matrixHeight - row - 1
                    return fromElevationFactory(GpkgElevationFactory(content, tileMatrix.ordinal, column, gpkgRow, isFloat))
                }
            }
            true
        } catch (e: IllegalArgumentException) {
            logMessage(WARN, "TiledImageLayer", "configureCache", e.message!!)
            false
        } catch (e: IllegalStateException) {
            logMessage(WARN, "TiledImageLayer", "configureCache", e.message!!)
            false
        }
    }

    /**
     * Removes cache provider from current tiled elevation coverage.
     */
    fun disableCache() {
        cacheContent = null
        cacheTileFactory = null
    }

    /**
     * Delete all tiles from current cache storage
     */
    suspend fun clearCache() = cacheContent?.run { container.deleteContent(tableName) }.also { disableCache() }

    /**
     * Start a new coroutine Job that downloads all elevations for a given sector and resolution,
     * without downloading imagery already in the file store.
     *
     * Note that the target resolution must be provided in radians of latitude per texel, which is the resolution in
     * meters divided by the globe radius.
     *
     * @param sector     the sector to download data for.
     * @param resolution the target resolution, provided in radians of latitude per texel.
     * @param scope      custom coroutine scope for better job management. Global scope by default.
     * @param onProgress an optional retrieval listener.
     *
     * @return the coroutine Job executing the retrieval or `null` if the specified sector does
     * not intersect the elevation model bounding sector.
     *
     * @throws IllegalStateException if cache not configured.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun makeLocal(
        sector: Sector, resolution: Double, scope: CoroutineScope = GlobalScope, onProgress: ((Int, Int) -> Unit)? = null
    ): Job? {
        val cacheTileFactory = cacheTileFactory ?: error("Cache not configured")
        return if (sector.intersect(tileMatrixSet.sector)) {
            scope.launch(Dispatchers.IO) {
                val processingList = assembleTilesList(sector, resolution)
                for ((current, tile) in processingList.withIndex()) {
                    ensureActive()
                    try {
                        val cacheSource = cacheTileFactory.createElevationSource(tile.tileMatrix, tile.row, tile.col)
                        // Check if tile exists in cache. If cache retrieval fail, then normal tile source will be requested.
                        // TODO If retrieved cache source is outdated, then retrieve original tile source to refresh cache
                        elevationDecoder.run {
                            decodeElevation(cacheSource) ?: decodeElevation(
                                tileFactory.createElevationSource(tile.tileMatrix, tile.row, tile.col).also {
                                    // Assign download postprocessor
                                    val source = cacheSource.asUnrecognized()
                                    if (source is GpkgElevationFactory) it.postprocessor = source
                                }
                            )
                        }?.also {
                            // Un-mark tile key from absent list
                            launch(Dispatchers.Main) {
                                absentResourceList.unmarkResourceAbsent(tile.tileMatrix.tileKey(tile.row, tile.col))
                            }
                        }
                    } catch (ignore: Throwable) {
                        // Ignore particular tile retrieval failure
                    }
                    onProgress?.invoke(current + 1, processingList.size)
                }
            }
        } else null
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
                    // Assign download postprocessor to save retrieved online tile to cache
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

    protected open fun retrievalSucceeded(key: Long, source: ElevationSource, value: ShortArray) = mainScope.launch {
        retrievalSucceeded(key, value)
        if (isLoggable(DEBUG)) log(DEBUG, "Coverage retrieval succeeded '$source'")
    }

    protected open fun retrievalFailed(key: Long, source: ElevationSource, ex: Throwable? = null) = mainScope.launch {
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