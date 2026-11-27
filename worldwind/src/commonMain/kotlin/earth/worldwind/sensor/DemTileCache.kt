package earth.worldwind.sensor

import earth.worldwind.geom.Sector
import earth.worldwind.globe.Globe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Elevation posts of one fixed tile of a [DemTileCache], with a max pyramid over its cells.
 *
 * Posts run row by row from the south-west corner: post (row, column) sits at
 * `minLatitude + row * cellDegrees`, `minLongitude + column * cellDegrees`, and a tile of [cells]
 * cells per side has `cells + 1` posts per side, the edge posts shared with the neighbouring tile.
 * Heights are above the ellipsoid, as [Globe.getElevationGrid] gives them.
 */
class DemTile internal constructor(
    val sector: Sector,
    val cells: Int,
    val cellDegrees: Double,
    internal val heights: FloatArray,
    /** [earth.worldwind.globe.elevation.ElevationModel.timestamp] taken before the posts were read. */
    internal val elevationTimestamp: Long,
) {
    val minLatitude = sector.minLatitude.inDegrees
    val minLongitude = sector.minLongitude.inDegrees
    private val posts = cells + 1

    /**
     * `levels[k]` holds the highest post of every block of `2^k × 2^k` cells, `(cells shr k)²`
     * of them row by row. Level 0 is the cells themselves: a bilinear cell is never higher than
     * its highest corner, so a ray above a block's maximum cannot meet the ground anywhere in it.
     */
    internal val levels: Array<FloatArray>
    val topLevel: Int

    init {
        require(cells > 0 && cells and (cells - 1) == 0) { "Cells per tile must be a power of two" }
        var size = cells
        val level0 = FloatArray(size * size)
        for (r in 0 until size) for (c in 0 until size) {
            level0[r * size + c] = max(max(post(r, c), post(r, c + 1)), max(post(r + 1, c), post(r + 1, c + 1)))
        }
        val list = mutableListOf(level0)
        while (size > 1) {
            val child = list.last()
            val childSize = size
            size /= 2
            val parent = FloatArray(size * size)
            for (r in 0 until size) for (c in 0 until size) {
                val i = 2 * r * childSize + 2 * c
                parent[r * size + c] = max(max(child[i], child[i + 1]), max(child[i + childSize], child[i + childSize + 1]))
            }
            list += parent
        }
        levels = list.toTypedArray()
        topLevel = levels.size - 1
    }

    /** The highest post of the whole tile. */
    val maxHeight get() = levels[topLevel][0]

    internal fun post(row: Int, column: Int) = heights[row * posts + column]

    internal fun blockMax(level: Int, row: Int, column: Int) = levels[level][row * (cells shr level) + column]
}

/**
 * Elevations for sensor footprints, cut into fixed tiles on a global latitude/longitude grid and
 * shared by every sensor that looks at the same ground. A tile is read once with
 * [Globe.getElevationGrid] on [Dispatchers.Default], and read again only when the elevation model
 * reports a change inside it, such as finer data arriving for a tile that was first read from a
 * coarser level.
 *
 * Every method is meant for one thread, the one [scope] dispatches on (the render thread), and
 * fetches come back to it before they are published.
 *
 * @param resolutionMeters intended post spacing; sized to the elevation source, finer only costs memory
 * @param cellsPerTile cells per tile side, a power of two; 256 at 30 m is a tile of about 7.7 km
 * @param capacity tiles kept before the least recently used is dropped; each costs about
 * `cellsPerTile² × 5.3` bytes (posts plus pyramid), about 350 KB at 256
 */
class DemTileCache(
    val globe: Globe,
    private val scope: CoroutineScope,
    resolutionMeters: Double = 30.0,
    val cellsPerTile: Int = 256,
    val capacity: Int = 64,
) {
    /** Tile side in degrees, chosen so a whole number of tiles fits 360° (and an even number fits 180°). */
    val tileDegrees: Double
    val cellDegrees: Double
    private val columns: Int
    private val rows: Int

    init {
        require(cellsPerTile > 0 && cellsPerTile and (cellsPerTile - 1) == 0) { "Cells per tile must be a power of two" }
        require(resolutionMeters > 0.0) { "Resolution must be positive" }
        val wanted = resolutionMeters * cellsPerTile / METERS_PER_DEGREE
        var count = ceil(360.0 / wanted).toInt()
        if (count % 2 == 1) count++
        columns = count
        rows = count / 2
        tileDegrees = 360.0 / count
        cellDegrees = tileDegrees / cellsPerTile
    }

    /** Bumped whenever a tile is published, so solvers know a result may have changed. */
    var generation = 0L
        private set

    /** Number of tile reads running in the background. */
    val pendingFetches get() = inFlight

    /** Most tile reads that may run at once. */
    var maxConcurrentFetches = 4

    /** How often [refreshChanged] asks the elevation model about loaded tiles. */
    var changeCheckInterval: Duration = 1.seconds

    private class Entry(var tile: DemTile? = null, var lastUsed: Long = 0, var job: Job? = null)

    private val entries = HashMap<Long, Entry>()
    private var useClock = 0L
    private var inFlight = 0
    private var lastChangeCheck = TimeSource.Monotonic.markNow()

    fun columnOf(longitudeDegrees: Double) =
        floor((longitudeDegrees + 180.0) / tileDegrees).toInt().let { if (it >= columns) it - columns else if (it < 0) it + columns else it }

    fun rowOf(latitudeDegrees: Double) =
        floor((latitudeDegrees + 90.0) / tileDegrees).toInt().coerceIn(0, rows - 1)

    /** The loaded tile under a location, or null while it is read; asking also schedules the read. */
    fun tileAt(latitudeDegrees: Double, longitudeDegrees: Double) = tile(rowOf(latitudeDegrees), columnOf(longitudeDegrees))

    /** The loaded tile at a grid position, or null while it is read; asking also schedules the read. */
    fun tile(row: Int, column: Int): DemTile? {
        val key = row.toLong() * columns + column
        val entry = entries[key]?.also { it.lastUsed = ++useClock } ?: Entry(lastUsed = ++useClock).also {
            entries[key] = it
            evictIfFull() // the new entry is the most recent, so it is never the one dropped
        }
        if (entry.tile == null) fetch(entry, row, column)
        return entry.tile
    }

    /**
     * Reads again the loaded tiles whose elevations changed since they were read. Cheap to call
     * every frame: the elevation model is asked at most once per [changeCheckInterval]. A changed
     * tile stays in use until its new posts are published.
     */
    fun refreshChanged() {
        if (lastChangeCheck.elapsedNow() < changeCheckInterval) return
        lastChangeCheck = TimeSource.Monotonic.markNow()
        val model = globe.elevationModel
        for ((key, entry) in entries) {
            val tile = entry.tile ?: continue
            if (entry.job == null && model.isChangedSince(tile.elevationTimestamp, tile.sector)) {
                fetch(entry, (key / columns).toInt(), (key % columns).toInt())
            }
        }
    }

    /** Drops every tile and cancels the reads in flight. */
    fun clear() {
        for (entry in entries.values) entry.job?.cancel()
        entries.clear()
    }

    private fun fetch(entry: Entry, row: Int, column: Int) {
        if (entry.job != null || inFlight >= maxConcurrentFetches) return
        val sector = Sector.fromDegrees(row * tileDegrees - 90.0, column * tileDegrees - 180.0, tileDegrees, tileDegrees)
        inFlight++
        entry.job = scope.launch {
            try {
                val tile = withContext(Dispatchers.Default) { read(sector) }
                entry.tile = tile
                generation++
            } finally {
                entry.job = null
                inFlight--
            }
        }
    }

    private fun read(sector: Sector): DemTile {
        val posts = cellsPerTile + 1
        // Taken before reading, so data that lands while the grid is read still counts as a change
        val timestamp = globe.elevationModel.timestamp
        val heights = FloatArray(posts * posts)
        globe.getElevationGrid(sector, posts, posts, heights)
        return DemTile(sector, cellsPerTile, cellDegrees, heights, timestamp)
    }

    private fun evictIfFull() {
        while (entries.size > capacity) {
            var oldestKey = -1L
            var oldestUse = Long.MAX_VALUE
            for ((key, entry) in entries) if (entry.job == null && entry.lastUsed < oldestUse) {
                oldestUse = entry.lastUsed
                oldestKey = key
            }
            if (oldestKey < 0) return // everything is still being read
            entries.remove(oldestKey)
        }
    }

    companion object {
        /** Metres per degree of latitude, near enough for sizing cells. */
        const val METERS_PER_DEGREE = 111_319.49
    }
}
