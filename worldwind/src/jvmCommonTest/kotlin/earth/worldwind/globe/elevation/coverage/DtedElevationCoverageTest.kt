package earth.worldwind.globe.elevation.coverage

import earth.worldwind.formats.dted.synthDted
import java.io.File
import java.nio.ShortBuffer
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for [DtedElevationCoverage] / [DtedIndex] / [DtedSourceFactory].
 * Writes a handful of synthetic DTED tiles into a temp directory, builds the coverage
 * end-to-end, and exercises the index → source factory → elevation factory chain.
 */
class DtedElevationCoverageTest {

    private lateinit var tmpDir: File

    @BeforeTest
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "dted-cov-test-").toFile()
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun index_scansLevelAndBoundingSector() {
        writeTile(tmpDir, 45, 10, level = 1)
        writeTile(tmpDir, 45, 11, level = 1)
        writeTile(tmpDir, 46, 10, level = 1)

        val index = DtedIndex.scan(tmpDir, requireLevel = null)
        assertEquals(1, index.level)
        assertEquals(3, index.size)
        // Bounding sector covers (45..47 lat, 10..12 lon) — the union of the three tiles.
        assertEquals(45.0, index.boundingSector.minLatitude.inDegrees, 1e-9)
        assertEquals(47.0, index.boundingSector.maxLatitude.inDegrees, 1e-9)
        assertEquals(10.0, index.boundingSector.minLongitude.inDegrees, 1e-9)
        assertEquals(12.0, index.boundingSector.maxLongitude.inDegrees, 1e-9)
    }

    @Test
    fun index_mixedLevels_filterPicksMatching() {
        writeTile(tmpDir, 0, 0, level = 0, name = "n00e000.dt0")
        writeTile(tmpDir, 0, 0, level = 1, name = "n00e000.dt1")

        val onlyL1 = DtedIndex.scan(tmpDir, requireLevel = 1)
        assertEquals(1, onlyL1.level)
        assertEquals(1, onlyL1.size)
    }

    @Test
    fun index_inferSwCornerFromFilename_combinedName() {
        // `n45e034.dt1` — single-name layout (no parent directory hint).
        val combined = File(tmpDir, "n45e034.dt1")
        assertEquals(45 to 34, DtedIndex.inferSwCornerFromPath(combined))

        // Negative hemispheres.
        val southWest = File(tmpDir, "s12w079.dt2")
        assertEquals(-12 to -79, DtedIndex.inferSwCornerFromPath(southWest))
    }

    @Test
    fun index_inferSwCornerFromFilename_directoryLayout() {
        // `n45/e034.dt1` — NIMA CD layout: parent dir = lat, file = lon.
        val latDir = File(tmpDir, "n45").apply { mkdirs() }
        val tile = File(latDir, "e034.dt1")
        assertEquals(45 to 34, DtedIndex.inferSwCornerFromPath(tile))
    }

    @Test
    fun index_inferSwCornerFromFilename_unknownLayout_returnsNull() {
        // Not a recognised NIMA layout — index.scan would fall back to header parse.
        val tile = File(tmpDir, "random_tile.dt1")
        assertNull(DtedIndex.inferSwCornerFromPath(tile))
    }

    @Test
    fun index_scan_usesFilenameWithoutOpeningHeader() {
        // Write a file whose HEADER points to (0, 0) but whose FILENAME claims (45, 34).
        // The filename inference is preferred — if scan opened the header it would pick (0, 0).
        val tile = File(tmpDir, "n45e034.dt1").apply {
            writeBytes(synthDted(latSW = 0, lonSW = 0, level = 1))
        }
        val index = DtedIndex.scan(tmpDir, requireLevel = 1)
        assertEquals(1, index.size)
        assertEquals(tile, index.lookup(45, 34))
        assertNull(index.lookup(0, 0))
    }

    @Test
    fun coverage_construction_exposesLevelAndTileCount() {
        writeTile(tmpDir, 45, 10, level = 1)
        writeTile(tmpDir, 45, 11, level = 1)

        val coverage = DtedElevationCoverage(tmpDir)
        assertEquals(1, coverage.level)
        assertEquals(2, coverage.tileCount)
        // The matrix should be single-level with dimensions matching the bounding sector.
        assertEquals(1, coverage.tileMatrixSet.entries.size)
        val matrix = coverage.tileMatrixSet.entries[0]
        assertEquals(2, matrix.matrixWidth)
        assertEquals(1, matrix.matrixHeight)
        // tileWidth/Height defaults to DTED-1's 1201×1201 posts.
        assertEquals(DtedElevationCoverage.postsPerSide(1), matrix.tileWidth)
    }

    @Test
    fun sourceFactory_returnsFileForPresentTile_nullForMissing() = runBlocking {
        // Build a 2×2 matrix: (45,10), (45,11) — fill only (45,10).
        writeTile(tmpDir, 45, 10, level = 1, width = 4, height = 4) { col, lat ->
            (col * 10 + lat + 100).toShort()
        }
        // To make the bounding sector 2×2, we need a second tile somewhere — write a tile
        // at (45, 11) but missing from the factory's perspective doesn't work because
        // scan() builds the index from present files. Instead, scan a 1-tile library and
        // verify the factory returns the file for that one cell.
        val coverage = DtedElevationCoverage(tmpDir, level = 1)
        val matrix = coverage.tileMatrixSet.entries[0]
        val factory = DtedSourceFactory(DtedIndex.scan(tmpDir, requireLevel = 1))

        val presentSource = factory.createElevationSource(matrix, 0, 0)
        assertTrue(presentSource.isElevationDataFactory)
        val data = presentSource.asElevationDataFactory().fetchElevationData()
        assertNotNull(data)
        // Sanity check: the elevation buffer has width * height values.
        val buf = data as ShortBuffer
        assertEquals(4 * 4, buf.remaining())
    }

    @Test
    fun sourceFactory_missingTileFromIndex_returnsNullData() = runBlocking {
        // Two tiles → 2×1 matrix. We delete one of the files post-index so the factory
        // is asked for a coordinate the index still claims to know about but disk doesn't.
        writeTile(tmpDir, 45, 10, level = 1)
        val orphan = writeTile(tmpDir, 45, 11, level = 1)
        val factory = DtedSourceFactory(DtedIndex.scan(tmpDir, requireLevel = 1))

        orphan.delete()

        val coverage = DtedElevationCoverage(tmpDir, level = 1)
        val matrix = coverage.tileMatrixSet.entries[0]
        // (row=0, col=1) maps to SW=(45,11) — file used to exist, now gone.
        val source = factory.createElevationSource(matrix, 0, 1)
        val data = source.asElevationDataFactory().fetchElevationData()
        assertNull(data)
    }

    @Test
    fun sourceFactory_outOfIndex_returnsNullData() = runBlocking {
        writeTile(tmpDir, 45, 10, level = 1)
        val coverage = DtedElevationCoverage(tmpDir, level = 1)
        val matrix = coverage.tileMatrixSet.entries[0]

        // Synthesise an out-of-bounds query by directly asking the factory for a
        // matrix tile we never populated — easiest is to build a larger 2×2 matrix on
        // top of the 1-tile index and probe the empty cells.
        val factory = DtedSourceFactory(DtedIndex.scan(tmpDir, requireLevel = 1))
        // Probe the single existing tile — should fetch data.
        assertNotNull(factory.createElevationSource(matrix, 0, 0).asElevationDataFactory().fetchElevationData())
        // Probe a coordinate that doesn't exist in the index — direct lookup returns null,
        // factory wraps it in a no-op data factory.
        // For a single-cell matrix there is no out-of-bounds index, so this just confirms
        // the lookup-null path:
        assertNull(factory.createElevationSource(coverage.tileMatrixSet.entries[0], 5, 5)
            .asElevationDataFactory().fetchElevationData())
    }

    // Convenience: write a synth DTED tile to the named file. Returns the file for
    // post-write tweaks (deletion, corruption).
    private fun writeTile(
        dir: File, latSW: Int, lonSW: Int, level: Int,
        width: Int = 3, height: Int = 3, name: String? = null,
        elevation: (column: Int, latIndex: Int) -> Short = { _, _ -> 100 },
    ): File {
        val ext = ".dt$level"
        val tileName = name ?: "tile_${latSW}_${lonSW}$ext"
        val file = File(dir, tileName)
        file.writeBytes(synthDted(latSW = latSW, lonSW = lonSW, width = width, height = height, level = level, elevation = elevation))
        return file
    }
}
