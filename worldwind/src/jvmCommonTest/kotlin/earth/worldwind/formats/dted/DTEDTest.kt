package earth.worldwind.formats.dted

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Unit tests for the DTED reader. Each test synthesises a small DTED tile in memory
 * (no real .dt0 / .dt1 / .dt2 file required) so they run fast and don't need test
 * fixtures.
 */
class DTEDTest {

    @Test
    fun parse_headerFields() {
        val bytes = synthDted(latSW = 45, lonSW = -120, width = 5, height = 5, level = 1)
        val dted = DTED.parse(bytes)
        assertEquals(5, dted.width)
        assertEquals(5, dted.height)
        assertEquals(1, dted.level)
        assertEquals("Unclassified", dted.classLevel)
        assertEquals(45.0, dted.sector.minLatitude.inDegrees, 1e-9)
        assertEquals(46.0, dted.sector.maxLatitude.inDegrees, 1e-9)
        assertEquals(-120.0, dted.sector.minLongitude.inDegrees, 1e-9)
        assertEquals(-119.0, dted.sector.maxLongitude.inDegrees, 1e-9)
        assertEquals(46.0, dted.origin.latitude.inDegrees, 1e-9)
        assertEquals(-120.0, dted.origin.longitude.inDegrees, 1e-9)
    }

    @Test
    fun parse_southernHemisphere() {
        val bytes = synthDted(latSW = -30, lonSW = 150, width = 3, height = 3, level = 2)
        val dted = DTED.parse(bytes)
        assertEquals(2, dted.level)
        assertEquals(-30.0, dted.sector.minLatitude.inDegrees, 1e-9)
        assertEquals(150.0, dted.sector.minLongitude.inDegrees, 1e-9)
    }

    @Test
    fun parse_elevationGridRowFlipAndMinMax() {
        // Per-column ramp: file stores south-to-north (i=0 south, i=h-1 north),
        // reader flips so row 0 is north. Each column = (column index) * 10 + lat index.
        val bytes = synthDted(width = 4, height = 3) { col, latIdx -> (col * 100 + latIdx).toShort() }
        val dted = DTED.parse(bytes)

        // Row 0 is north (file's latIdx=2 for height=3)
        assertEquals(2.toShort(), dted.elevations[0 * 4 + 0])  // col 0, latIdx 2
        assertEquals(102.toShort(), dted.elevations[0 * 4 + 1]) // col 1, latIdx 2
        // Row 2 (south, latIdx=0)
        assertEquals(0.toShort(), dted.elevations[2 * 4 + 0])   // col 0, latIdx 0
        assertEquals(300.toShort(), dted.elevations[2 * 4 + 3]) // col 3, latIdx 0

        assertEquals(0.0, dted.minElevation, 1e-9)
        assertEquals(302.0, dted.maxElevation, 1e-9)
    }

    @Test
    fun parse_decodesNegativeElevations_signMagnitude() {
        // Real DTED encodes negatives sign-magnitude; sub-sea-level posts must decode to their value,
        // not become holes (the two's-complement decode bug). height=3: latIdx 0=south, 2=north.
        val bytes = synthDted(width = 3, height = 3) { col, latIdx ->
            when {
                col == 0 && latIdx == 0 -> -430  // Dead-Sea-ish negative
                col == 1 && latIdx == 1 -> -1     // just below zero
                else -> 250
            }.toShort()
        }
        val dted = DTED.parse(bytes)
        assertEquals((-430).toShort(), dted.elevations[2 * 3 + 0]) // col 0, latIdx 0 -> south row
        assertEquals((-1).toShort(), dted.elevations[1 * 3 + 1])   // col 1, latIdx 1 -> middle row
        assertEquals(250.toShort(), dted.elevations[0 * 3 + 2])    // col 2, latIdx 2 -> north row
        assertEquals(-430.0, dted.minElevation, 1e-9)
        assertEquals(250.0, dted.maxElevation, 1e-9)
    }

    @Test
    fun parse_nullSentinelAndOutOfRange() {
        // height=2 so each column has [latIdx=0 (south), latIdx=1 (north)].
        // Flag a few posts as null or out-of-range; reader should normalise to Short.MIN_VALUE.
        val bytes = synthDted(width = 3, height = 2) { col, latIdx ->
            when {
                col == 0 && latIdx == 0 -> DTED.NODATA_VALUE.toShort()        // -32767 sentinel
                col == 1 && latIdx == 1 -> (DTED.MAX_VALUE + 100).toShort()   // out of [-12000,+9000]
                col == 2 && latIdx == 0 -> (DTED.MIN_VALUE - 100).toShort()   // out of range below
                else -> 500.toShort()
            }
        }
        val dted = DTED.parse(bytes)
        // Row 0 (north, latIdx=1)
        assertEquals(500.toShort(), dted.elevations[0 * 3 + 0])
        assertEquals(Short.MIN_VALUE, dted.elevations[0 * 3 + 1]) // out of range above
        assertEquals(500.toShort(), dted.elevations[0 * 3 + 2])
        // Row 1 (south, latIdx=0)
        assertEquals(Short.MIN_VALUE, dted.elevations[1 * 3 + 0]) // -32767 nulled
        assertEquals(500.toShort(), dted.elevations[1 * 3 + 1])
        assertEquals(Short.MIN_VALUE, dted.elevations[1 * 3 + 2]) // out of range below

        // min/max should ignore the nulled values
        assertEquals(500.0, dted.minElevation, 1e-9)
        assertEquals(500.0, dted.maxElevation, 1e-9)
    }

    @Test
    fun parse_checksumFailure() {
        val bytes = synthDted(width = 2, height = 2)
        // Corrupt the first elevation byte; the stored checksum no longer matches.
        bytes[DTED.DATA_OFFSET + DTED.REC_HEADER_SIZE] = (bytes[DTED.DATA_OFFSET + DTED.REC_HEADER_SIZE] + 1).toByte()
        val ex = assertFailsWith<IllegalStateException> { DTED.parse(bytes) }
        assertNotNull(ex.message)
    }

    @Test
    fun parse_invokeOperator_equivalentToParse() {
        val bytes = synthDted(width = 2, height = 2)
        val viaParse = DTED.parse(bytes)
        val viaInvoke = DTED(bytes)
        assertContentEquals(viaParse.elevations, viaInvoke.elevations)
        assertEquals(viaParse.sector.minLatitude, viaInvoke.sector.minLatitude)
    }

    @Test
    fun readMetadata_headerOnly() {
        val bytes = synthDted(latSW = 12, lonSW = 34, width = 7, height = 9, level = 0)
        // Truncate to just the header — readMetadata must not require the data records.
        val headerOnly = bytes.copyOfRange(0, DTED.DATA_OFFSET)
        val meta = DTED.readMetadata(headerOnly)
        assertEquals(7, meta.width)
        assertEquals(9, meta.height)
        assertEquals(0, meta.level)
        assertEquals(12.0, meta.sector.minLatitude.inDegrees, 1e-9)
        assertEquals(34.0, meta.sector.minLongitude.inDegrees, 1e-9)
    }

    @Test
    fun read_streamingFile_roundTrip() {
        val bytes = synthDted(width = 4, height = 4) { c, l -> (c * 10 + l).toShort() }
        val tmp = File.createTempFile("dted-test-", ".dt1").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
        val streamed = DTED.read(tmp)
        val inMemory = DTED.parse(bytes)
        assertContentEquals(inMemory.elevations, streamed.elevations)
        assertEquals(inMemory.sector.minLatitude, streamed.sector.minLatitude)
        assertEquals(inMemory.minElevation, streamed.minElevation, 1e-9)
        assertEquals(inMemory.maxElevation, streamed.maxElevation, 1e-9)
    }

    @Test
    fun readDownsampled_nativeTarget_equalsFullRead() {
        val bytes = synthDted(width = 5, height = 5) { c, l -> (c * 100 + l).toShort() }
        val tmp = File.createTempFile("dted-native-", ".dt2").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
        // Target == native dimensions → identical grid to the full byte-array parse.
        val native = DTED.read(tmp, 5, 5)
        assertContentEquals(DTED.parse(bytes).elevations, native)
    }

    @Test
    fun readDownsampled_subsamplesNativeGrid() {
        // 5×5 native, value = col*100 + fileLatIndex. Subsample to 3×3 picks native indices 0,2,4
        // on both axes; the result must match those samples from the full parse (row 0 = north).
        val bytes = synthDted(width = 5, height = 5) { c, l -> (c * 100 + l).toShort() }
        val tmp = File.createTempFile("dted-sub-", ".dt2").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
        val full = DTED.parse(bytes).elevations
        val down = DTED.read(tmp, 3, 3)
        assertEquals(3 * 3, down.size)
        for (ty in 0 until 3) for (tx in 0 until 3) {
            assertEquals(full[(2 * ty) * 5 + (2 * tx)], down[ty * 3 + tx])
        }
    }

    @Test
    fun readWindowed_subQuadrantSamplesCorrectRegion() {
        // 5×5 native, value = col*100 + fileLatIndex. Read the NW quadrant (west columns [0,0.5],
        // north rows [0,0.5]) at 3×3 — must equal the NW 3×3 corner of the full north-origin grid.
        val bytes = synthDted(width = 5, height = 5) { c, l -> (c * 100 + l).toShort() }
        val tmp = File.createTempFile("dted-win-", ".dt2").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
        val full = DTED.parse(bytes).elevations // row 0 = north
        val nw = DTED.read(tmp, 0.0, 0.5, 0.0, 0.5, 3, 3)
        assertEquals(3 * 3, nw.size)
        for (ty in 0 until 3) for (tx in 0 until 3) {
            assertEquals(full[ty * 5 + tx], nw[ty * 3 + tx])
        }
        // The full-cell 2-arg overload equals the (0,1,0,1) window.
        assertContentEquals(DTED.read(tmp, 4, 4), DTED.read(tmp, 0.0, 1.0, 0.0, 1.0, 4, 4))
    }

    @Test
    fun readMetadata_file_doesNotReadElevationBytes() {
        // Build a header-plus-corrupt-data file. readMetadata should still succeed.
        val bytes = synthDted(width = 3, height = 3)
        for (i in DTED.DATA_OFFSET until bytes.size) bytes[i] = 0xFF.toByte() // corrupt all data
        val tmp = File.createTempFile("dted-meta-", ".dt1").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
        val meta = DTED.readMetadata(tmp)
        assertEquals(3, meta.width)
        assertEquals(3, meta.height)
    }

}
