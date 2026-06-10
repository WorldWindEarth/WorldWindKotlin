package earth.worldwind.globe.elevation.coverage.dted

import earth.worldwind.WorldWind
import earth.worldwind.formats.dted.DTED
import earth.worldwind.formats.dted.readOverview
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.globe.elevation.coverage.dted.DtedSourceFactory.Companion.packCell
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A single tiled elevation coverage over a local directory of DTED tiles (`.dt0`/`.dt1`/`.dt2`), built by
 * [create]: each 1° cell is served from the finest level present (dt2 > dt1 > dt0) — one pyramid, one cache,
 * no duplicated data. Coarse cells upsample smoothly via bilinear windowed reads; zoomed-out grids
 * sparse-sample a per-cell overview off the render thread (the ATAK `NodeElevationData` pattern).
 */
open class DtedElevationCoverage private constructor(
    private val cellFiles: Map<Long, File>,
    private val finestLevel: Int,
    extent: Sector,
) : TiledElevationCoverage(buildTileMatrixSet(finestLevel, extent), DtedSourceFactory(cellFiles)) {

    /** Number of 1° cells served (one file per cell — the finest level present there). */
    val tileCount: Int get() = cellFiles.size

    init {
        displayName = "DTED ${resolutionLabel(finestLevel)}"
        // ~128 KB tiles → a generous working set, capped for huge libraries.
        val posts = tilePosts(finestLevel)
        val tileBytes = posts.toLong() * posts * 2L
        setupCoverageCache((tileBytes * 768L).coerceIn(48L * 1024 * 1024, 64L * 1024 * 1024))
    }

    override fun clone() = DtedElevationCoverage(cellFiles, finestLevel, tileMatrixSet.sector).also {
        it.displayName = displayName
        it.sector.copy(sector)
    }

    /** Height limits only size tile bounding boxes, never the rendered surface. Reuse the base per-tile
     *  min/max (ElevationImage) from already-cached tiles with retrieval disabled — the render thread must
     *  not scan or fetch here (that was a self-sustaining redraw storm) — falling back to a dataset bound. */
    override fun doGetElevationLimits(sector: Sector, result: FloatArray) {
        if (!this.sector.intersects(sector)) return
        if (!isWideQuery(sector)) {
            // Cached tiles only; isRetrievalEnabled is shared base state, so save/restore rather than leave it flipped.
            val wasRetrievalEnabled = isRetrievalEnabled
            isRetrievalEnabled = false
            try {
                val targetIdx = tileMatrixSet.indexOfMatrixNearest(sector.deltaLatitude.div(GET_HEIGHT_LIMIT_SAMPLES))
                val tileBlock = TileBlock()
                for (idx in targetIdx downTo 0) {
                    if (fetchTileBlock(sector, tileMatrixSet.entries[idx], tileBlock)) {
                        scanHeightLimits(sector, tileBlock, result)
                        return
                    }
                }
            } finally {
                isRetrievalEnabled = wasRetrievalEnabled
            }
        }
        if (result[0] > DATASET_MIN) result[0] = DATASET_MIN
        if (result[1] < DATASET_MAX) result[1] = DATASET_MAX
    }

    /** Coarse grids are sparse-sampled from a cached per-cell sub-sample (read off the render thread);
     *  finer grids use the inherited native tile path. Row-major south-up, raw elevation. */
    override fun doGetElevationGrid(gridSector: Sector, gridWidth: Int, gridHeight: Int, result: FloatArray) {
        if (!sector.intersects(gridSector)) return
        if (!isWideQuery(gridSector)) {
            super.doGetElevationGrid(gridSector, gridWidth, gridHeight, result)
            return
        }
        if (gridWidth < 1 || gridHeight < 1) return
        val minLat = gridSector.minLatitude.inDegrees
        val maxLat = gridSector.maxLatitude.inDegrees
        val minLon = gridSector.minLongitude.inDegrees
        val maxLon = gridSector.maxLongitude.inDegrees
        val deltaLat = if (gridHeight > 1) (maxLat - minLat) / (gridHeight - 1) else 0.0
        val deltaLon = if (gridWidth > 1) (maxLon - minLon) / (gridWidth - 1) else 0.0
        var toLoad: MutableSet<Long>? = null
        var rIdx = 0
        for (hIdx in 0 until gridHeight) {
            val lat = if (hIdx == gridHeight - 1) maxLat else minLat + hIdx * deltaLat
            for (wIdx in 0 until gridWidth) {
                val lon = if (wIdx == gridWidth - 1) maxLon else minLon + wIdx * deltaLon
                // A point on a cell's south/west integer edge belongs to two cells; prefer the one with data (no seam).
                var latCell = floor(lat).toInt()
                var lonCell = floor(lon).toInt()
                if (lat == latCell.toDouble() && packCell(latCell, lonCell) !in cellFiles &&
                    packCell(latCell - 1, lonCell) in cellFiles) latCell--
                if (lon == lonCell.toDouble() && packCell(latCell, lonCell) !in cellFiles &&
                    packCell(latCell, lonCell - 1) in cellFiles) lonCell--
                val k = packCell(latCell, lonCell)
                val grid = cellCache[k]
                if (grid != null) {
                    if (grid.isNotEmpty()) sample(grid, lat - latCell, lon - lonCell)?.let { result[rIdx] = it }
                } else (toLoad ?: LinkedHashSet<Long>().also { toLoad = it }).add(k)
                rIdx++
            }
        }
        toLoad?.let { requestCells(it) }
    }

    // Per-cell coarse [SUBSAMPLE]² overview (EMPTY = no data). Read on the render thread, written in the
    // [requestCells] continuation; concurrent so the two never corrupt each other across threads.
    private val cellCache = ConcurrentHashMap<Long, ShortArray>()
    private val cellsInFlight: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Read the missing cells' coarse sub-samples on the IO dispatcher, cache them on the render thread,
     *  then bump the timestamp and redraw once. */
    private fun requestCells(cells: Set<Long>) {
        val fresh = cells.filterTo(ArrayList()) { cellsInFlight.add(it) }
        if (fresh.isEmpty()) return
        mainScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                fresh.map { k -> k to (cellFiles[k]?.let { readSubsample(it) } ?: EMPTY) }
            }
            for ((k, grid) in loaded) cellCache[k] = grid
            cellsInFlight.removeAll(fresh.toHashSet())
            updateTimestamp()
            WorldWind.requestRedraw()
        }
    }

    override fun clear() {
        cellCache.clear()
        cellsInFlight.clear()
        super.clear()
    }

    companion object {
        /** Sectors wider/taller than this many 1° cells take the conservative dataset bound / sub-sample. */
        private const val LIMIT_CELL_SPAN = 8
        /** Conservative global DTED land elevation range (meters) for coarse height-limit queries. */
        private const val DATASET_MIN = -500f
        private const val DATASET_MAX = 9000f
        /** Fixed posts per pyramid tile (~128 KB array); detail comes from subdividing cells, not bigger tiles. */
        const val TILE_POSTS = 256
        private const val SUBSAMPLE = 16  // coarse per-cell sub-sample size for zoomed-out grids (ATAK node grid)
        private const val NO_DATA = Short.MIN_VALUE
        private val EMPTY = ShortArray(0)

        /** Walk [rootDir] once (name-only, no header reads) and build one coverage serving the finest level
         *  present per 1° cell (dt2 > dt1 > dt0) — no per-level composite. Null if [rootDir] holds no DTED;
         *  run off the main thread (directory I/O). */
        fun create(rootDir: File): DtedElevationCoverage? {
            val files = HashMap<Long, File>()
            val bestLevel = HashMap<Long, Int>()
            var finest = -1
            var minLat = Int.MAX_VALUE; var maxLat = Int.MIN_VALUE
            var minLon = Int.MAX_VALUE; var maxLon = Int.MIN_VALUE
            fun visit(dir: File) {
                val entries = dir.listFiles() ?: return
                for (e in entries) {
                    if (e.isDirectory) { visit(e); continue }
                    val level = DtedLayout.dtedLevel(e.name)
                    if (level < 0) continue
                    val (lat, lon) = DtedLayout.inferCorner(e) ?: continue
                    val k = packCell(lat, lon)
                    if (level > (bestLevel[k] ?: -1)) { bestLevel[k] = level; files[k] = e } // finest level wins
                    if (level > finest) finest = level
                    if (lat < minLat) minLat = lat
                    if (lat + 1 > maxLat) maxLat = lat + 1
                    if (lon < minLon) minLon = lon
                    if (lon + 1 > maxLon) maxLon = lon + 1
                }
            }
            visit(rootDir)
            if (files.isEmpty()) return null
            val extent = Sector.fromDegrees(
                minLat.toDouble(), minLon.toDouble(), (maxLat - minLat).toDouble(), (maxLon - minLon).toDouble()
            )
            return DtedElevationCoverage(files, finest, extent)
        }

        /** Number of latitude posts per side for a given DTED level. */
        fun postsPerSide(level: Int) = when (level) {
            0 -> 121; 1 -> 1201; 2 -> 3601
            else -> error(logMessage(ERROR, "DtedElevationCoverage", "postsPerSide", "Unknown DTED level $level"))
        }

        /** Approximate post spacing for a DTED [level] (dt0 ≈ 900 m, dt1 ≈ 90 m, dt2 ≈ 30 m), for the display name. */
        private fun resolutionLabel(level: Int) = when (level) {
            0 -> "900m"; 1 -> "90m"; 2 -> "30m"
            else -> error(logMessage(ERROR, "DtedElevationCoverage", "resolutionLabel", "Unknown DTED level $level"))
        }

        /** [TILE_POSTS], capped at the native count so coarse DTED levels aren't upsampled past their resolution. */
        fun tilePosts(level: Int) = minOf(TILE_POSTS, postsPerSide(level))

        /** A per-extent fixed-tile LOD pyramid for [level]: one tile per 1° cell at level 0, finer levels
         *  double to the level's native spacing. Per-extent (not global) so the matrix sector is never
         *  full-sphere (the inherited sampler would then wrap longitude, wrong for bounded DTED). */
        internal fun buildTileMatrixSet(level: Int, extent: Sector): TileMatrixSet {
            val matrixWidth = extent.deltaLongitude.inDegrees.roundToInt().coerceAtLeast(1)
            val matrixHeight = extent.deltaLatitude.inDegrees.roundToInt().coerceAtLeast(1)
            val posts = tilePosts(level)
            val nativeResolution = (1.0 / (postsPerSide(level) - 1)).degrees
            return TileMatrixSet.fromTilePyramid(extent, matrixWidth, matrixHeight, posts, posts, nativeResolution)
        }

        /** True when [sector] spans ≥ [LIMIT_CELL_SPAN] 1° cells in either axis: too wide for the per-cell
         *  coarse grid / cached height-limit paths, so the conservative dataset bound is used instead. */
        private fun isWideQuery(sector: Sector): Boolean {
            val latSpan = floor(sector.maxLatitude.inDegrees).toInt() - floor(sector.minLatitude.inDegrees).toInt()
            val lonSpan = floor(sector.maxLongitude.inDegrees).toInt() - floor(sector.minLongitude.inDegrees).toInt()
            return latSpan >= LIMIT_CELL_SPAN || lonSpan >= LIMIT_CELL_SPAN
        }

        /** A cell file's coarse [SUBSAMPLE]² sub-sample, or [EMPTY] if no data / unreadable. Pure I/O. */
        private fun readSubsample(file: File): ShortArray = try {
            DTED.readOverview(file, SUBSAMPLE).takeIf { g -> g.any { it != NO_DATA } } ?: EMPTY
        } catch (e: Throwable) {
            logMessage(WARN, "DtedElevationCoverage", "readSubsample", "Failed to read overview of ${file.name}: ${e.message}")
            EMPTY
        }

        /** Sample a [SUBSAMPLE]² north-up cell sub-sample at a fractional position within the cell
         *  ([latFrac]/[lonFrac] in `[0,1]`, 0 = south / west). */
        private fun sample(grid: ShortArray, latFrac: Double, lonFrac: Double): Float? {
            val col = (lonFrac.coerceIn(0.0, 1.0) * (SUBSAMPLE - 1)).roundToInt()
            val row = ((1.0 - latFrac.coerceIn(0.0, 1.0)) * (SUBSAMPLE - 1)).roundToInt() // north-up: row 0 = north
            val s = grid[row * SUBSAMPLE + col]
            return if (s != NO_DATA) s.toFloat() else null
        }
    }
}
