package earth.worldwind.globe.elevation.coverage

import earth.worldwind.formats.dted.DTED
import earth.worldwind.formats.dted.read
import earth.worldwind.formats.dted.readMetadata
import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrix
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.util.Logger.DEBUG
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.INFO
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.isLoggable
import earth.worldwind.util.Logger.log
import earth.worldwind.util.Logger.logMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.Buffer
import java.nio.ShortBuffer
import kotlin.math.floor

/**
 * Tiled elevation coverage backed by a local directory of DTED files
 * (`.dt0` / `.dt1` / `.dt2`, per MIL-PRF-89020B).
 *
 * Scans the directory tree once at construction, reads each tile's UHL header to
 * recover its south-west corner (integer-degree key), and builds an in-memory
 * index. The matrix is a single resolution level sized to the bounding sector
 * of the indexed tiles, with `tileWidth = tileHeight = postsPerSide` matching
 * the DTED level (121 / 1201 / 3601). Per-tile reads stream from disk via the
 * `DTED.read(file)` overload, so peak memory per tile stays at one column
 * record (~7 KB on .dt2) rather than the full file (~26 MB).
 *
 * If a library mixes levels, pass the desired [level] explicitly to filter; the
 * default (`null`) picks the level of the first file found and ignores the rest.
 * Tiles missing from the bounding rectangle simply resolve to `MISSING_DATA`.
 */
open class DtedElevationCoverage private constructor(
    private val rootDir: File,
    private val index: DtedIndex,
) : TiledElevationCoverage(buildTileMatrixSet(index), DtedSourceFactory(index)) {

    /**
     * @param rootDir root directory to scan recursively for DTED files
     * @param level   restrict the index to one DTED level (`0`, `1`, or `2`).
     *                When `null`, the level of the first file found is used.
     */
    constructor(rootDir: File, level: Int? = null) : this(rootDir, DtedIndex.scan(rootDir, level))

    init {
        log(INFO, "DtedElevationCoverage: level=${index.level} tiles=${index.size} sector=${index.boundingSector}")
    }

    /** The DTED level actually loaded (0/1/2). */
    val level: Int get() = index.level
    /** Number of tile files indexed under [rootDir]. */
    val tileCount: Int get() = index.size

    override fun clone() = DtedElevationCoverage(rootDir, index).also {
        it.displayName = displayName
        it.sector.copy(sector)
    }

    companion object {
        /** Number of latitude posts per side for a given DTED level. */
        fun postsPerSide(level: Int) = when (level) {
            0 -> 121
            1 -> 1201
            2 -> 3601
            else -> error(
                logMessage(ERROR, "DtedElevationCoverage", "postsPerSide", "Unknown DTED level $level")
            )
        }

        private fun buildTileMatrixSet(index: DtedIndex): TileMatrixSet {
            val sector = index.boundingSector
            // Bounding sector spans integer degrees because every DTED tile starts
            // at an integer-degree SW corner. matrixWidth/Height = degrees of span.
            val matrixWidth = sector.deltaLongitude.inDegrees.toInt().coerceAtLeast(1)
            val matrixHeight = sector.deltaLatitude.inDegrees.toInt().coerceAtLeast(1)
            val posts = postsPerSide(index.level)
            return TileMatrixSet(sector, listOf(TileMatrix(sector, 0, matrixWidth, matrixHeight, posts, posts)))
        }
    }
}

/** Immutable map from `(latDegSW, lonDegSW)` to the file containing that 1° tile. */
internal class DtedIndex(
    val level: Int,
    private val tiles: Map<Long, File>,
    val boundingSector: Sector,
) {
    val size: Int get() = tiles.size

    fun lookup(latDegSW: Int, lonDegSW: Int): File? = tiles[key(latDegSW, lonDegSW)]

    companion object {
        private fun key(latDegSW: Int, lonDegSW: Int): Long =
            (latDegSW.toLong() and 0xFFFFFFFFL) shl 32 or (lonDegSW.toLong() and 0xFFFFFFFFL)

        /** Walk [root] depth-first, locate every `.dt0/.dt1/.dt2` file, and build the
         *  index. The SW corner is inferred from the path first (NIMA-style `n45/e034.dt1`
         *  or `n45e034.dt1`) — opening 3428 bytes per tile is wasteful on a 10k-tile
         *  library — and we fall back to reading the UHL header when the path doesn't
         *  match. Files whose path-and-header both fail are skipped with a warning so a
         *  single bad tile doesn't break the whole coverage. */
        fun scan(root: File, requireLevel: Int?): DtedIndex {
            require(root.isDirectory) {
                logMessage(ERROR, "DtedIndex", "scan", "Not a directory: $root")
            }
            val tiles = mutableMapOf<Long, File>()
            var chosenLevel: Int? = requireLevel
            var minLat = Int.MAX_VALUE
            var maxLat = Int.MIN_VALUE
            var minLon = Int.MAX_VALUE
            var maxLon = Int.MIN_VALUE
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val ext = file.extension.lowercase()
                val fileLevel = when (ext) {
                    "dt0" -> 0
                    "dt1" -> 1
                    "dt2" -> 2
                    else -> return@forEach
                }
                if (chosenLevel != null && fileLevel != chosenLevel) return@forEach
                val swCorner = inferSwCornerFromPath(file) ?: readSwCornerFromHeader(file) ?: return@forEach
                if (chosenLevel == null) chosenLevel = fileLevel
                val (latDegSW, lonDegSW) = swCorner
                tiles[key(latDegSW, lonDegSW)] = file
                if (latDegSW < minLat) minLat = latDegSW
                if (latDegSW + 1 > maxLat) maxLat = latDegSW + 1
                if (lonDegSW < minLon) minLon = lonDegSW
                if (lonDegSW + 1 > maxLon) maxLon = lonDegSW + 1
            }
            require(tiles.isNotEmpty()) {
                logMessage(ERROR, "DtedIndex", "scan", "No DTED tiles found under $root")
            }
            val sector = Sector.fromDegrees(
                minLat.toDouble(), minLon.toDouble(),
                (maxLat - minLat).toDouble(), (maxLon - minLon).toDouble()
            )
            return DtedIndex(chosenLevel!!, tiles, sector)
        }

        // NIMA layout: `n45/e034.dt1` (lat dir + lon file) or single-name `n45e034.dt1`.
        // The directory form is what shipped on the original DTED CDs; the combined form
        // is common when a library has been flattened. Hemisphere letters are case-insensitive.
        private val LATLON_REGEX = Regex("""([NnSs])(\d{1,2})([EeWw])(\d{1,3})""")
        private val LAT_REGEX = Regex("""([NnSs])(\d{1,2})""")
        private val LON_REGEX = Regex("""([EeWw])(\d{1,3})""")

        internal fun inferSwCornerFromPath(file: File): Pair<Int, Int>? {
            val name = file.nameWithoutExtension
            // Try combined name first: `n45e034.dt1`.
            LATLON_REGEX.matchEntire(name)?.let { m ->
                val lat = m.groupValues[2].toInt() * if (m.groupValues[1].equals("S", true)) -1 else 1
                val lon = m.groupValues[4].toInt() * if (m.groupValues[3].equals("W", true)) -1 else 1
                return lat to lon
            }
            // Then directory-style: parent is `n45`, file is `e034.dt1`.
            val parentName = file.parentFile?.name ?: return null
            val latMatch = LAT_REGEX.matchEntire(parentName) ?: return null
            val lonMatch = LON_REGEX.matchEntire(name) ?: return null
            val lat = latMatch.groupValues[2].toInt() * if (latMatch.groupValues[1].equals("S", true)) -1 else 1
            val lon = lonMatch.groupValues[2].toInt() * if (lonMatch.groupValues[1].equals("W", true)) -1 else 1
            return lat to lon
        }

        private fun readSwCornerFromHeader(file: File): Pair<Int, Int>? = try {
            val meta = DTED.readMetadata(file)
            floor(meta.sector.minLatitude.inDegrees).toInt() to floor(meta.sector.minLongitude.inDegrees).toInt()
        } catch (e: Throwable) {
            log(WARN, "Skipping unreadable DTED tile $file: ${e.message}")
            null
        }
    }
}

/** Builds [ElevationSource]s backed by a [DtedIndex]. Returns an [ElevationSource.ElevationDataFactory]
 *  for every tile coordinate — even ones missing from the index — so the framework's
 *  factory contract (must always return a source) stays satisfied. The factory itself
 *  returns `null` for missing tiles, which the abstract coverage treats as
 *  `MISSING_DATA`. */
internal class DtedSourceFactory(private val index: DtedIndex) : ElevationSourceFactory {
    override val contentType: String = "DTED-Local"

    override fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int): ElevationSource {
        val tileSector = tileMatrix.tileSector(row, column)
        val latDegSW = floor(tileSector.minLatitude.inDegrees).toInt()
        val lonDegSW = floor(tileSector.minLongitude.inDegrees).toInt()
        return ElevationSource.fromElevationDataFactory(DtedTileFactory(index.lookup(latDegSW, lonDegSW)))
    }
}

private class DtedTileFactory(private val file: File?) : ElevationSource.ElevationDataFactory {
    override suspend fun fetchElevationData(): Buffer? {
        val f = file ?: return null
        if (!f.isFile) return null
        return withContext(Dispatchers.IO) {
            val dted = DTED.read(f)
            if (isLoggable(DEBUG)) log(
                DEBUG,
                "DTED tile loaded: ${f.name} sector=${dted.sector} min=${dted.minElevation} max=${dted.maxElevation}"
            )
            ShortBuffer.wrap(dted.elevations)
        }
    }

    override fun toString(): String = file?.toString() ?: "<missing DTED tile>"
}
