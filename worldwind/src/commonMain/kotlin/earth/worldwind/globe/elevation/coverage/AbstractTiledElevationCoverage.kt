package earth.worldwind.globe.elevation.coverage

import earth.worldwind.WorldWind
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrix
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.util.*
import earth.worldwind.util.Logger.INFO
import earth.worldwind.util.Logger.log
import earth.worldwind.util.format.format
import earth.worldwind.util.math.fract
import earth.worldwind.util.math.mod
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.Duration.Companion.seconds

abstract class AbstractTiledElevationCoverage(
    tileMatrixSet: TileMatrixSet, elevationSourceFactory: ElevationSourceFactory
): AbstractElevationCoverage() {
    companion object {
        protected const val GET_HEIGHT_LIMIT_SAMPLES = 32
    }

    var tileMatrixSet = tileMatrixSet
        set(value) {
            field = value
            sector.copy(value.sector) // Use TMS sector as bounding sector by default
            clear()
        }
    var elevationSourceFactory = elevationSourceFactory
        set(value) {
            field = value
            clear()
        }
    /**
     * Unique identifier of the coverage type, defined by elevation source factory content type
     */
    val type get() = elevationSourceFactory.contentType
    /**
     * Controls how many concurrent tile requests are allowed for this coverage.
     */
    var retrievalQueueSize = 4
    /**
     * The list of elevation retrievals in progress.
     */
    protected val currentRetrievals = mutableSetOf<Long>()
    protected var coverageCache = LruMemoryCache<Long, ShortArray>(1024 * 1024 * 64)
    protected var isRetrievalEnabled = false
    protected val absentResourceList = AbsentResourceList<Long>(3, 5.seconds)
    protected val mainScope = MainScope()

    init {
        sector.copy(tileMatrixSet.sector) // Use TMS sector as bounding sector by default
        log(INFO, "Coverage cache initialized %.0f KB".format(coverageCache.capacity / 1024.0))
    }

    /**
     * Setup custom coverage cache size according to device capabilities and user needs.
     */
    fun setupCoverageCache(capacity: Long, lowWater: Long = (capacity * 0.75).toLong()) {
        coverageCache = LruMemoryCache(capacity, lowWater)
    }

    override fun clear() {
        mainScope.coroutineContext.cancelChildren() // Cancel all async jobs but keep scope reusable
        currentRetrievals.clear()
        coverageCache.clear()
        absentResourceList.clear()
        updateTimestamp()
    }

    override fun doGetHeight(latitude: Angle, longitude: Angle, retrieve: Boolean): Float? {
        if (!tileMatrixSet.sector.contains(latitude, longitude)) return null // no coverage in the specified location
        val targetIdx = tileMatrixSet.entries.size - 1 // retrieve height from last available matrix
        for (idx in targetIdx downTo 0) {
            // enable retrieval of the last and the first matrix
            isRetrievalEnabled = retrieve && (idx == targetIdx || idx == 0)
            val tileMatrix = tileMatrixSet.entries[idx]
            val deltaLat = tileMatrix.sector.deltaLatitude.inDegrees / tileMatrix.matrixHeight
            val deltaLon = tileMatrix.sector.deltaLongitude.inDegrees / tileMatrix.matrixWidth
            val row = floor((tileMatrix.sector.maxLatitude.inDegrees - latitude.inDegrees) / deltaLat).toInt()
            val col = floor((longitude.inDegrees - tileMatrix.sector.minLongitude.inDegrees) / deltaLon).toInt()
            fetchTileArray(tileMatrix, row, col)?.let {
                val maxLat = tileMatrix.sector.maxLatitude.inDegrees - deltaLat * row
                val minLon = tileMatrix.sector.minLongitude.inDegrees + deltaLon * col
                val maxX = tileMatrix.tileWidth - 1
                val maxY = tileMatrix.tileHeight - 1
                val x = (maxX * (longitude.inDegrees - minLon) / deltaLon).toFloat()
                val y = (maxY * (maxLat - latitude.inDegrees) / deltaLat).toFloat()
                val x0 = floor(x).toInt().coerceIn(0, maxX)
                val x1 = (x0 + 1).coerceIn(0, maxX)
                val y0 = floor(y).toInt().coerceIn(0, maxY)
                val y1 = (y0 + 1).coerceIn(0, maxY)
                val x0y0 = it[x0 + y0 * tileMatrix.tileWidth]
                val x1y0 = it[x1 + y0 * tileMatrix.tileWidth]
                val x0y1 = it[x0 + y1 * tileMatrix.tileWidth]
                val x1y1 = it[x1 + y1 * tileMatrix.tileWidth]
                val xf = x - x0
                val yf = y - y0
                return (1 - xf) * (1 - yf) * x0y0 + xf * (1 - yf) * x1y0 + (1 - xf) * yf * x0y1 + xf * yf * x1y1
            }
        }
        return null // did not find a tile
    }

    override fun doGetHeightGrid(gridSector: Sector, gridWidth: Int, gridHeight: Int, result: FloatArray) {
        if (!tileMatrixSet.sector.intersects(gridSector)) return  // no coverage in the specified sector
        val targetPixelSpan = gridSector.deltaLatitude.div(gridHeight)
        val targetIdx = tileMatrixSet.indexOfMatrixNearest(targetPixelSpan)
        val tileBlock = TileBlock()
        for (idx in targetIdx downTo 0) {
            // enable retrieval of the target matrix and the first matrix
            isRetrievalEnabled = idx == targetIdx || idx == 0
            val tileMatrix = tileMatrixSet.entries[idx]
            if (fetchTileBlock(gridSector, gridWidth, gridHeight, tileMatrix, tileBlock)) {
                readHeightGrid(gridSector, gridWidth, gridHeight, tileBlock, result)
                return
            }
        }
    }

    override fun doGetHeightLimits(sector: Sector, result: FloatArray) {
        if (!tileMatrixSet.sector.intersects(sector)) return  // no coverage in the specified sector
        val targetPixelSpan = sector.deltaLatitude.div(GET_HEIGHT_LIMIT_SAMPLES)
        val targetIdx = tileMatrixSet.indexOfMatrixNearest(targetPixelSpan)
        val tileBlock = TileBlock()
        for (idx in targetIdx downTo 0) {
            // enable retrieval of the target matrix and the first matrix
            isRetrievalEnabled = idx == targetIdx || idx == 0
            val tileMatrix = tileMatrixSet.entries[idx]
            if (fetchTileBlock(sector, tileMatrix, tileBlock)) {
                scanHeightLimits(sector, tileBlock, result)
                return
            }
        }
    }

    protected open fun fetchTileBlock(
        gridSector: Sector, gridWidth: Int, gridHeight: Int, tileMatrix: TileMatrix, result: TileBlock
    ): Boolean {
        val tileWidth = tileMatrix.tileWidth
        val tileHeight = tileMatrix.tileHeight
        val rasterWidth = tileMatrix.matrixWidth * tileWidth
        val rasterHeight = tileMatrix.matrixHeight * tileHeight
        val matrixMinLat = tileMatrix.sector.minLatitude.inDegrees
        val matrixMaxLat = tileMatrix.sector.maxLatitude.inDegrees
        val matrixMinLon = tileMatrix.sector.minLongitude.inDegrees
        val matrixMaxLon = tileMatrix.sector.maxLongitude.inDegrees
        val matrixDeltaLat = tileMatrix.sector.deltaLatitude.inDegrees
        val matrixDeltaLon = tileMatrix.sector.deltaLongitude.inDegrees
        val sMin = 1.0 / (2.0 * rasterWidth)
        val sMax = 1.0 - sMin
        val tMin = 1.0 / (2.0 * rasterHeight)
        val tMax = 1.0 - tMin
        result.tileMatrix = tileMatrix
        result.clear()
        var lon = gridSector.minLongitude.inDegrees
        val deltaLon = gridSector.deltaLongitude.inDegrees / (gridWidth - 1)
        var uIdx = 0
        while (uIdx < gridWidth) {
            // explicitly set the last lon to the max longitude to ensure alignment
            if (uIdx == gridWidth - 1) lon = gridSector.maxLongitude.inDegrees
            if (lon in matrixMinLon..matrixMaxLon) {
                val s = (lon - matrixMinLon) / matrixDeltaLon
                var u: Double
                var i0: Int
                var i1: Int
                if (tileMatrix.sector.isFullSphere) {
                    u = rasterWidth * fract(s) // wrap the horizontal coordinate
                    i0 = mod(floor(u - 0.5).toInt(), rasterWidth)
                    i1 = mod(i0 + 1, rasterWidth)
                } else {
                    u = rasterWidth * s.coerceIn(sMin, sMax) // clamp the horizontal coordinate
                    i0 = floor(u - 0.5).toInt().coerceIn(0, rasterWidth - 1)
                    i1 = (i0 + 1).coerceIn(0, rasterWidth - 1)
                }
                val col0 = i0 / tileWidth
                val col1 = i1 / tileWidth
                result.cols[col0] = 0
                result.cols[col1] = 0
            }
            uIdx++
            lon += deltaLon
        }
        var lat = gridSector.minLatitude.inDegrees
        val deltaLat = gridSector.deltaLatitude.inDegrees / (gridHeight - 1)
        var vIdx = 0
        while (vIdx < gridHeight) {
            // explicitly set the last lat to the max latitude to ensure alignment
            if (vIdx == gridHeight - 1) lat = gridSector.maxLatitude.inDegrees
            if (lat in matrixMinLat..matrixMaxLat) {
                val t = (matrixMaxLat - lat) / matrixDeltaLat
                val v = rasterHeight * t.coerceIn(tMin, tMax) // clamp the vertical coordinate to the raster edge
                val j0 = floor(v - 0.5).toInt().coerceIn(0, rasterHeight - 1)
                val j1 = (j0 + 1).coerceIn(0, rasterHeight - 1)
                val row0 = j0 / tileHeight
                val row1 = j1 / tileHeight
                result.rows[row0] = 0
                result.rows[row1] = 0
            }
            vIdx++
            lat += deltaLat
        }
        for (row in result.rows.keys) {
            for (col in result.cols.keys) {
                val tileArray = fetchTileArray(tileMatrix, row, col)
                if (tileArray != null) result.putTileArray(row, col, tileArray) else return false
            }
        }
        return true
    }

    protected open fun fetchTileBlock(sector: Sector, tileMatrix: TileMatrix, result: TileBlock): Boolean {
        val tileWidth = tileMatrix.tileWidth
        val tileHeight = tileMatrix.tileHeight
        val rasterWidth = tileMatrix.matrixWidth * tileWidth
        val rasterHeight = tileMatrix.matrixHeight * tileHeight
        val matrixMaxLat = tileMatrix.sector.maxLatitude.inDegrees
        val matrixMinLon = tileMatrix.sector.minLongitude.inDegrees
        val matrixDeltaLat = tileMatrix.sector.deltaLatitude.inDegrees
        val matrixDeltaLon = tileMatrix.sector.deltaLongitude.inDegrees
        val intersection = Sector(tileMatrix.sector)
        intersection.intersect(sector)
        val sMin = (intersection.minLongitude.inDegrees - matrixMinLon) / matrixDeltaLon
        val sMax = (intersection.maxLongitude.inDegrees - matrixMinLon) / matrixDeltaLon
        val uMin = floor(rasterWidth * sMin).toInt()
        val uMax = ceil(rasterWidth * sMax).toInt()
        val iMin = uMin.coerceIn(0, rasterWidth - 1)
        val iMax = uMax.coerceIn(0, rasterWidth - 1)
        val colMin = iMin / tileWidth
        val colMax = iMax / tileWidth
        val tMin = (matrixMaxLat - intersection.maxLatitude.inDegrees) / matrixDeltaLat
        val tMax = (matrixMaxLat - intersection.minLatitude.inDegrees) / matrixDeltaLat
        val vMin = floor(rasterHeight * tMin).toInt()
        val vMax = ceil(rasterHeight * tMax).toInt()
        val jMin = vMin.coerceIn(0, rasterHeight - 1)
        val jMax = vMax.coerceIn(0, rasterHeight - 1)
        val rowMin = jMin / tileHeight
        val rowMax = jMax / tileHeight
        result.tileMatrix = tileMatrix
        result.clear()
        for (row in rowMin..rowMax) {
            for (col in colMin..colMax) {
                val tileArray = fetchTileArray(tileMatrix, row, col)
                if (tileArray != null) {
                    result.rows[row] = 0
                    result.cols[col] = 0
                    result.putTileArray(row, col, tileArray)
                } else return false
            }
        }
        return true
    }

    protected open fun fetchTileArray(tileMatrix: TileMatrix, row: Int, column: Int): ShortArray? {
        val key = tileMatrix.tileKey(row, column)
        return coverageCache[key] ?: run {
            // Ignore retrieval of already requested or marked as absent tiles
            if (isRetrievalEnabled && currentRetrievals.size < retrievalQueueSize && !currentRetrievals.contains(key)
                && !absentResourceList.isResourceAbsent(key)) {
                currentRetrievals += key
                mainScope.launch { retrieveTileArray(key, tileMatrix, row, column) }
            }
            null
        }
    }

    protected abstract suspend fun retrieveTileArray(key: Long, tileMatrix: TileMatrix, row: Int, column: Int)

    protected fun retrievalSucceeded(key: Long, value: ShortArray) {
        coverageCache.put(key, value, value.size * 2)
        absentResourceList.unmarkResourceAbsent(key)
        currentRetrievals -= key
        updateTimestamp()
        WorldWind.requestRedraw()
    }

    protected fun retrievalFailed(key: Long) {
        absentResourceList.markResourceAbsent(key)
        currentRetrievals -= key
    }

    protected open fun readHeightGrid(
        gridSector: Sector, gridWidth: Int, gridHeight: Int, tileBlock: TileBlock, result: FloatArray
    ) {
        val tileWidth = tileBlock.tileMatrix.tileWidth
        val tileHeight = tileBlock.tileMatrix.tileHeight
        val rasterWidth = tileBlock.tileMatrix.matrixWidth * tileWidth
        val rasterHeight = tileBlock.tileMatrix.matrixHeight * tileHeight
        val matrixMinLat = tileBlock.tileMatrix.sector.minLatitude.inDegrees
        val matrixMaxLat = tileBlock.tileMatrix.sector.maxLatitude.inDegrees
        val matrixMinLon = tileBlock.tileMatrix.sector.minLongitude.inDegrees
        val matrixMaxLon = tileBlock.tileMatrix.sector.maxLongitude.inDegrees
        val matrixDeltaLat = tileBlock.tileMatrix.sector.deltaLatitude.inDegrees
        val matrixDeltaLon = tileBlock.tileMatrix.sector.deltaLongitude.inDegrees
        val sMin = 1.0 / (2.0 * rasterWidth)
        val sMax = 1.0 - sMin
        val tMin = 1.0 / (2.0 * rasterHeight)
        val tMax = 1.0 - tMin
        var rIdx = 0
        var lat = gridSector.minLatitude.inDegrees
        val deltaLat = gridSector.deltaLatitude.inDegrees / (gridHeight - 1)
        var hIdx = 0
        while (hIdx < gridHeight) {
            // explicitly set the last lat to the max latitude to ensure alignment
            if (hIdx == gridHeight - 1) lat = gridSector.maxLatitude.inDegrees
            val t = (matrixMaxLat - lat) / matrixDeltaLat
            val v = rasterHeight * t.coerceIn(tMin, tMax) // clamp the vertical coordinate to the raster edge
            val b = fract(v - 0.5).toFloat()
            val j0 = floor(v - 0.5).toInt().coerceIn(0, rasterHeight - 1)
            val j1 = (j0 + 1).coerceIn(0, rasterHeight - 1)
            val row0 = j0 / tileHeight
            val row1 = j1 / tileHeight
            var lon = gridSector.minLongitude.inDegrees
            val deltaLon = gridSector.deltaLongitude.inDegrees / (gridWidth - 1)
            var wIdx = 0
            while (wIdx < gridWidth) {
                // explicitly set the last lon to the max longitude to ensure alignment
                if (wIdx == gridWidth - 1) lon = gridSector.maxLongitude.inDegrees
                val s = (lon - matrixMinLon) / matrixDeltaLon
                var u: Double
                var i0: Int
                var i1: Int
                if (tileBlock.tileMatrix.sector.isFullSphere) {
                    u = rasterWidth * fract(s) // wrap the horizontal coordinate
                    i0 = mod(floor(u - 0.5).toInt(), rasterWidth)
                    i1 = mod(i0 + 1, rasterWidth)
                } else {
                    u = rasterWidth * s.coerceIn(sMin, sMax) // clamp the horizontal coordinate
                    i0 = floor(u - 0.5).toInt().coerceIn(0, rasterWidth - 1)
                    i1 = (i0 + 1).coerceIn(0, rasterWidth - 1)
                }
                val a = fract(u - 0.5).toFloat()
                val col0 = i0 / tileWidth
                val col1 = i1 / tileWidth
                if (lat in matrixMinLat..matrixMaxLat && lon in matrixMinLon..matrixMaxLon) {
                    val i0j0 = tileBlock.readTexel(row0, col0, i0 % tileWidth, j0 % tileHeight)
                    val i1j0 = tileBlock.readTexel(row0, col1, i1 % tileWidth, j0 % tileHeight)
                    val i0j1 = tileBlock.readTexel(row1, col0, i0 % tileWidth, j1 % tileHeight)
                    val i1j1 = tileBlock.readTexel(row1, col1, i1 % tileWidth, j1 % tileHeight)
                    result[rIdx] = (1 - a) * (1 - b) * i0j0 + a * (1 - b) * i1j0 + (1 - a) * b * i0j1 + a * b * i1j1
                }
                rIdx++
                wIdx++
                lon += deltaLon
            }
            hIdx++
            lat += deltaLat
        }
    }

    protected open fun scanHeightLimits(sector: Sector, tileBlock: TileBlock, result: FloatArray) {
        val tileWidth = tileBlock.tileMatrix.tileWidth
        val tileHeight = tileBlock.tileMatrix.tileHeight
        val rasterWidth = tileBlock.tileMatrix.matrixWidth * tileWidth
        val rasterHeight = tileBlock.tileMatrix.matrixHeight * tileHeight
        val matrixMaxLat = tileBlock.tileMatrix.sector.maxLatitude.inDegrees
        val matrixMinLon = tileBlock.tileMatrix.sector.minLongitude.inDegrees
        val matrixDeltaLat = tileBlock.tileMatrix.sector.deltaLatitude.inDegrees
        val matrixDeltaLon = tileBlock.tileMatrix.sector.deltaLongitude.inDegrees
        val intersection = Sector(tileBlock.tileMatrix.sector)
        intersection.intersect(sector)
        val sMin = (intersection.minLongitude.inDegrees - matrixMinLon) / matrixDeltaLon
        val sMax = (intersection.maxLongitude.inDegrees - matrixMinLon) / matrixDeltaLon
        val uMin = floor(rasterWidth * sMin).toInt()
        val uMax = ceil(rasterWidth * sMax).toInt()
        val iMin = uMin.coerceIn(0, rasterWidth - 1)
        val iMax = uMax.coerceIn(0, rasterWidth - 1)
        val tMin = (matrixMaxLat - intersection.maxLatitude.inDegrees) / matrixDeltaLat
        val tMax = (matrixMaxLat - intersection.minLatitude.inDegrees) / matrixDeltaLat
        val vMin = floor(rasterHeight * tMin).toInt()
        val vMax = ceil(rasterHeight * tMax).toInt()
        val jMin = vMin.coerceIn(0, rasterHeight - 1)
        val jMax = vMax.coerceIn(0, rasterHeight - 1)
        for (row in tileBlock.rows.keys) {
            val rowJMin = row * tileHeight
            val rowJMax = rowJMin + tileHeight - 1
            val j0 = jMin.coerceIn(rowJMin, rowJMax) % tileHeight
            val j1 = jMax.coerceIn(rowJMin, rowJMax) % tileHeight
            for (col in tileBlock.cols.keys) {
                val colIMin = col * tileWidth
                val colIMax = colIMin + tileWidth - 1
                val i0 = iMin.coerceIn(colIMin, colIMax) % tileWidth
                val i1 = iMax.coerceIn(colIMin, colIMax) % tileWidth
                tileBlock.getTileArray(row, col)?.let { tileArray ->
                    // TODO how often do we read all of tileArray?
                    for (j in j0..j1) for (i in i0..i1) {
                        val pos = i + j * tileWidth
                        val texel = tileArray[pos]
                        if (result[0] > texel) result[0] = texel.toFloat()
                        if (result[1] < texel) result[1] = texel.toFloat()
                    }
                }
            }
        }
    }

    protected open fun assembleTilesList(sector: Sector, resolution: ClosedRange<Angle>): List<Tile> {
        val result = mutableListOf<Tile>()
        val startIdx = tileMatrixSet.indexOfMatrixNearest(resolution.endInclusive)
        val endIdx = tileMatrixSet.indexOfMatrixNearest(resolution.start)
        for (idx in startIdx..endIdx) {
            val tileMatrix = tileMatrixSet.entries[idx]
            val deltaLat = tileMatrix.sector.deltaLatitude.inDegrees / tileMatrix.matrixHeight
            val deltaLon = tileMatrix.sector.deltaLongitude.inDegrees / tileMatrix.matrixWidth
            val minRow = floor((tileMatrix.sector.maxLatitude.inDegrees - sector.maxLatitude.inDegrees) / deltaLat).toInt()
            val maxRow = floor((tileMatrix.sector.maxLatitude.inDegrees - sector.minLatitude.inDegrees) / deltaLat).toInt()
            val minCol = floor((sector.minLongitude.inDegrees - tileMatrix.sector.minLongitude.inDegrees) / deltaLon).toInt()
            val maxCol = floor((sector.maxLongitude.inDegrees - tileMatrix.sector.minLongitude.inDegrees) / deltaLon).toInt()
            for (row in minRow..maxRow) for (col in minCol..maxCol) result.add(Tile(tileMatrix, row, col))
        }
        return result
    }

    protected data class Tile(val tileMatrix: TileMatrix, val row: Int, val col: Int)

    protected open class TileBlock {
        lateinit var tileMatrix: TileMatrix
        val rows = mutableMapOf<Int, Int>()
        val cols = mutableMapOf<Int, Int>()
        private val arrays = mutableMapOf<Long, ShortArray>()
        private var texelRow = -1
        private var texelCol = -1
        private var texelArray: ShortArray? = null

        open fun clear() {
            rows.clear()
            cols.clear()
            arrays.clear()
            texelRow = -1
            texelCol = -1
            texelArray = null
        }

        fun putTileArray(row: Int, column: Int, array: ShortArray) {
            val key = tileMatrix.tileKey(row, column)
            arrays[key] = array
        }

        fun getTileArray(row: Int, column: Int): ShortArray? {
            if (texelRow != row || texelCol != column) {
                texelRow = row
                texelCol = column
                texelArray = arrays[tileMatrix.tileKey(row, column)]
            }
            return texelArray
        }

        fun readTexel(row: Int, column: Int, i: Int, j: Int) =
            getTileArray(row, column)?.get(i + j * tileMatrix.tileWidth) ?: 0
    }
}