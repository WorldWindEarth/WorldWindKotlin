package earth.worldwind.globe.elevation.coverage.dted

import earth.worldwind.formats.dted.synthDted
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrix
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
 * Tests for the single best-level [DtedElevationCoverage]: [DtedElevationCoverage.create] walks the
 * directory once (name-only) and builds one coverage that serves the finest level present per cell, and
 * [DtedSourceFactory] reads a cell's windowed tile.
 */
class DtedElevationCoverageTest {

    private lateinit var tmpDir: File

    @BeforeTest
    fun setUp() { tmpDir = createTempDirectory(prefix = "dted-cov-test-").toFile() }

    @AfterTest
    fun tearDown() { tmpDir.deleteRecursively() }

    @Test
    fun create_mixedLevels_oneCoverage_finestPerCell() {
        // dt0 base + dt2 detail; cell (45,10) has both → deduped to its finest (dt2).
        writeTile(tmpDir, 45, 10, level = 0, name = "n45e010.dt0")
        writeTile(tmpDir, 45, 10, level = 2, name = "n45e010.dt2")
        writeTile(tmpDir, 45, 11, level = 2, name = "n45e011.dt2")
        val coverage = DtedElevationCoverage.create(tmpDir)!!
        assertEquals("DTED 30m", coverage.displayName) // finest level present is dt2 (~30 m)
        assertEquals(2, coverage.tileCount) // 2 distinct cells (no dt0/dt2 duplication)
        assertTrue(coverage.tileMatrixSet.maxResolution.inDegrees <= 1.0 / 3600.0 + 1e-9) // resolves to dt2
    }

    @Test
    fun create_mixedLevels_servesFinestLevelData_regardlessOfWriteOrder() = runBlocking {
        // Distinct constant value per level so the decoded bytes reveal which file is actually read.
        // Cell (45,10): dt0 written before dt2. Cell (46,10): dt2 written before dt0 — proves the choice is
        // by level (dt2 > dt0), not by directory/write order.
        writeTile(tmpDir, 45, 10, level = 0, name = "n45e010.dt0", width = 4, height = 4) { _, _ -> 200 }
        writeTile(tmpDir, 45, 10, level = 2, name = "n45e010.dt2", width = 4, height = 4) { _, _ -> 8000 }
        writeTile(tmpDir, 46, 10, level = 2, name = "n46e010.dt2", width = 4, height = 4) { _, _ -> 7000 }
        writeTile(tmpDir, 46, 10, level = 0, name = "n46e010.dt0", width = 4, height = 4) { _, _ -> 300 }

        val coverage = DtedElevationCoverage.create(tmpDir)!!
        assertEquals(2, coverage.tileCount) // each cell deduped to one file
        val matrix = coverage.tileMatrixSet.entries[0] // level 0 = one tile per 1° cell

        // Read each cell's tile through the coverage's OWN source factory and assert the dt2 value, not dt0.
        assertCellElevation(coverage, matrix, lat = 45.5, lon = 10.5, expected = 8000) // dt2 over dt0
        assertCellElevation(coverage, matrix, lat = 46.5, lon = 10.5, expected = 7000) // dt2 over dt0 (dt2 written first)
    }

    @Test
    fun create_dt0Only_pyramidStaysCoarse_notOverSubdivided() {
        writeTile(tmpDir, 45, 10, level = 0, name = "n45e010.dt0")
        val coverage = DtedElevationCoverage.create(tmpDir)!!
        assertTrue(coverage.tileMatrixSet.maxResolution.inDegrees <= 1.0 / 120.0 + 1e-9)  // dt0 native
        assertTrue(coverage.tileMatrixSet.maxResolution.inDegrees > 1.0 / 3600.0)          // not faked to 30 m
    }

    @Test
    fun create_emptyDir_returnsNull() {
        assertNull(DtedElevationCoverage.create(tmpDir))
    }

    @Test
    fun buildTileMatrixSet_perExtent_oneTilePerCell_nativeResolution_notFullSphere() {
        val extent = Sector.fromDegrees(45.0, 10.0, 2.0, 3.0) // 2° lat × 3° lon
        val tms = DtedElevationCoverage.buildTileMatrixSet(2, extent)
        assertEquals(3, tms.entries[0].matrixWidth)  // one tile per 1° cell
        assertEquals(2, tms.entries[0].matrixHeight)
        assertTrue(!tms.sector.isFullSphere)         // per-extent → no longitude wrap in the sampler
        assertTrue(tms.entries.last().resolution.inDegrees <= 1.0 / 3600.0 + 1e-9)
        val dt0 = DtedElevationCoverage.buildTileMatrixSet(0, extent)
        assertTrue(dt0.maxResolution.inDegrees > tms.maxResolution.inDegrees) // dt0 coarser than dt2
    }

    @Test
    fun layout_parsing_combinedFlippedAndTokens() {
        assertEquals(45 to 34, DtedLayout.parseLatLon("n45e034"))
        assertEquals(-12 to -79, DtedLayout.parseLatLon("s12w079"))
        assertNull(DtedLayout.parseLatLon("random_tile"))
        assertEquals(45, DtedLayout.parseLat("n45"))
        assertNull(DtedLayout.parseLat("e034"))
        assertEquals(34, DtedLayout.parseLon("e034"))
        assertEquals(45 to 10, DtedLayout.inferCorner(File(tmpDir, "e010/n45.dt1")))
        assertEquals(2, DtedLayout.dtedLevel("n45e010.DT2"))
        assertEquals(-1, DtedLayout.dtedLevel("notes.txt"))
    }

    @Test
    fun sourceFactory_readsPresentCell_realValues_nullForAbsent() = runBlocking {
        writeTile(tmpDir, 45, 10, level = 1, name = "n45e010.dt1", width = 4, height = 4) { col, lat ->
            (col * 10 + lat + 100).toShort()
        }
        val coverage = DtedElevationCoverage.create(tmpDir)!!
        val matrix = coverage.tileMatrixSet.entries[0]
        val factory = DtedSourceFactory(mapOf(cellKey(45, 10) to File(tmpDir, "n45e010.dt1")))

        val (pRow, pCol) = tileRowCol(matrix, 45.5, 10.5)
        val data = factory.createElevationSource(matrix, pRow, pCol).asElevationDataFactory().fetchElevationData()
        assertNotNull(data)
        val buf = data as ShortBuffer
        assertEquals(matrix.tileWidth * matrix.tileHeight, buf.remaining())
        while (buf.hasRemaining()) {
            val v = buf.get()
            assertTrue(v.toInt() in 100..133, "out-of-range elevation $v — wrong file/offset/orientation")
        }
        assertNull(DtedSourceFactory(emptyMap()).createElevationSource(matrix, pRow, pCol)
            .asElevationDataFactory().fetchElevationData())
        assertEquals("DTED", factory.contentType)
    }

    @Test
    fun elevationLimits_hugeSector_usesDatasetBound_withoutLoadingTiles() {
        writeTile(tmpDir, 45, 10, level = 1, name = "n45e010.dt1") { _, _ -> 250 }
        writeTile(tmpDir, 60, 30, level = 1, name = "n60e030.dt1") // widen extent so the query is "huge"
        val coverage = DtedElevationCoverage.create(tmpDir)!!
        val result = floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE)
        coverage.getElevationLimits(Sector.fromDegrees(44.0, 9.0, 18.0, 22.0), result)
        assertEquals(-500f, result[0])
        assertEquals(9000f, result[1])
    }

    /** Read the cell tile containing [lat]/[lon] through the coverage's own source factory and assert
     *  every decoded post equals [expected] — i.e. the bytes came from the file the coverage selected. */
    private suspend fun assertCellElevation(
        coverage: DtedElevationCoverage, matrix: TileMatrix, lat: Double, lon: Double, expected: Int,
    ) {
        val (row, col) = tileRowCol(matrix, lat, lon)
        val data = coverage.elevationSourceFactory.createElevationSource(matrix, row, col)
            .asElevationDataFactory().fetchElevationData()
        assertNotNull(data, "no tile for $lat,$lon")
        val buf = data as ShortBuffer
        while (buf.hasRemaining()) assertEquals(expected.toShort(), buf.get(), "wrong source file at $lat,$lon")
    }

    private fun cellKey(latSW: Int, lonSW: Int) =
        (latSW.toLong() and 0xFFFFFFFFL) shl 32 or (lonSW.toLong() and 0xFFFFFFFFL)

    /** The level-0 matrix tile (row, col) whose sector contains [lat]/[lon]. */
    private fun tileRowCol(matrix: TileMatrix, lat: Double, lon: Double): Pair<Int, Int> {
        for (row in 0 until matrix.matrixHeight) for (col in 0 until matrix.matrixWidth) {
            if (matrix.tileSector(row, col).contains(lat.degrees, lon.degrees)) return row to col
        }
        error("no tile for $lat,$lon")
    }

    private fun writeTile(
        dir: File, latSW: Int, lonSW: Int, level: Int,
        width: Int = 3, height: Int = 3, name: String? = null,
        elevation: (column: Int, latIndex: Int) -> Short = { _, _ -> 100 },
    ): File {
        val file = File(dir, name ?: "tile_${latSW}_$lonSW.dt$level")
        file.writeBytes(synthDted(latSW = latSW, lonSW = lonSW, width = width, height = height, level = level, elevation = elevation))
        return file
    }
}
