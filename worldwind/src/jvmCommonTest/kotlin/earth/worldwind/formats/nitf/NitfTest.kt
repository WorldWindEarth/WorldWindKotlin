package earth.worldwind.formats.nitf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for the NITF 2.1 reader. Each test synthesises a small NITF in
 * memory (no real .ntf fixture) so they run fast and don't need test files
 * committed alongside the source.
 */
class NitfTest {

    // --- File header ----------------------------------------------------------

    @Test
    fun parse_fileHeaderFields() {
        val bytes = synthNitf(NitfFixture())
        val nitf = Nitf.parse(bytes)
        assertEquals(NitfFormat.NITF_02_10, nitf.format)
        assertEquals("TESTSTATN", nitf.fileHeader.originatingStationId)
        assertEquals("20260523000000", nitf.fileHeader.fileDateTime)
        assertEquals(1, nitf.imageSegments.size)
    }

    @Test
    fun parse_metadataOnly_yieldsSegmentTableWithoutImageDecode() {
        val bytes = synthNitf(NitfFixture())
        val header = Nitf.readMetadata(bytes)
        assertEquals(1, header.imageSegments.size)
        // The segment offset table is enough to seek to image data later.
        val loc = header.imageSegments[0]
        assertEquals(404, loc.headerOffset) // 388 + 16 (one segment table entry)
        assertTrue(loc.dataLength > 0)
    }

    @Test
    fun isSupported_recognisesNitfAndNsifMagic() {
        val nitfBytes = synthNitf(NitfFixture())
        assertTrue(Nitf.isSupported(nitfBytes))
        // Rewrite the magic to a junk prefix and verify rejection.
        val junk = nitfBytes.copyOf()
        junk[0] = 'X'.code.toByte()
        assertEquals(false, Nitf.isSupported(junk))
    }

    // --- Image segment metadata ----------------------------------------------

    @Test
    fun parse_imageSegmentMetadata_decimalIgeolo() {
        val bytes = synthNitf(NitfFixture())
        val seg = Nitf.parse(bytes).imageSegments[0]

        assertEquals("MISSION001", seg.imageId)
        assertEquals(NitfPixelValueType.INT, seg.pixelValueType)
        assertEquals(NitfImageRepresentation.MONO, seg.imageRepresentation)
        assertEquals(NitfCompression.NC, seg.compression)
        assertEquals(NitfImageMode.BAND_BLOCK, seg.imageMode)
        assertEquals(1, seg.numBands)
        assertEquals(8, seg.bitsPerPixel)

        // Corners + sector from the synthesised IGEOLO (45..46, -120..-119).
        val sec = assertNotNull(seg.sector)
        assertEquals(45.0, sec.minLatitude.inDegrees, 1e-6)
        assertEquals(46.0, sec.maxLatitude.inDegrees, 1e-6)
        assertEquals(-120.0, sec.minLongitude.inDegrees, 1e-6)
        assertEquals(-119.0, sec.maxLongitude.inDegrees, 1e-6)

        val corners = assertNotNull(seg.corners)
        assertEquals(46.0, corners[0].latitude.inDegrees, 1e-6) // NW
        assertEquals(-120.0, corners[0].longitude.inDegrees, 1e-6)
        assertEquals(45.0, corners[2].latitude.inDegrees, 1e-6) // SE
        assertEquals(-119.0, corners[2].longitude.inDegrees, 1e-6)
    }

    // --- IGEOLO encodings -----------------------------------------------------

    @Test
    fun decode_geographicDmsIgeolo() {
        val igeolo = "452030N1200000W" + "452030N1190000W" +
                     "450000N1190000W" + "450000N1200000W"
        val decoded = assertNotNull(NitfCoordinates.decode(NitfCoordinateSystem.GEOGRAPHIC, igeolo))
        // NW = 45°20'30"N 120°00'00"W
        assertEquals(45.341666, decoded.corners[0].latitude.inDegrees, 1e-5)
        assertEquals(-120.0, decoded.corners[0].longitude.inDegrees, 1e-5)
    }

    @Test
    fun decode_utmNorthIgeolo_roundtripsToLatLon() {
        // Zone 10, central meridian -123°. Pick the central meridian point at
        // the equator: easting 500000, northing 0 → lat 0, lon -123.
        val corner = "10500000" + "0000000"
        val igeolo = corner.repeat(4)
        val decoded = assertNotNull(NitfCoordinates.decode(NitfCoordinateSystem.UTM_NORTH, igeolo))
        assertEquals(0.0, decoded.corners[0].latitude.inDegrees, 1e-6)
        assertEquals(-123.0, decoded.corners[0].longitude.inDegrees, 1e-6)
    }

    // --- Uncompressed pixel decode -------------------------------------------

    @Test
    fun decodeArgb_singleBandMono_8bit_imodeB() {
        val bytes = synthNitf(NitfFixture(pixel = { _, r, c -> (r * 16 + c) and 0xFF }))
        val seg = Nitf.parse(bytes).imageSegments[0]
        val argb = NitfImageReader.decodeArgb(seg, bytes)
        assertEquals(64, argb.size) // 8 × 8
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val expected = (r * 16 + c) and 0xFF
                val v = argb[r * 8 + c]
                assertEquals(0xFF, (v ushr 24) and 0xFF, "alpha at ($r,$c)")
                assertEquals(expected, (v ushr 16) and 0xFF, "red at ($r,$c)")
                assertEquals(expected, (v ushr 8) and 0xFF, "green at ($r,$c)")
                assertEquals(expected, v and 0xFF, "blue at ($r,$c)")
            }
        }
    }

    @Test
    fun decodeArgb_threeBandRgb_8bit_imodeB_blockGrid() {
        // 8×8 image laid out as a 2×2 grid of 4×4 blocks, RGB, IMODE B.
        val fixture = NitfFixture(
            width = 8, height = 8,
            blocksPerRow = 2, blocksPerCol = 2,
            pixelsPerBlockH = 4, pixelsPerBlockV = 4,
            nbands = 3, irep = "RGB", imode = 'B',
            pixel = { band, r, c ->
                when (band) { 0 -> r * 16 + c; 1 -> 255 - (r * 16 + c); else -> (r + c) * 16 } and 0xFF
            }
        )
        val bytes = synthNitf(fixture)
        val seg = Nitf.parse(bytes).imageSegments[0]
        val argb = NitfImageReader.decodeArgb(seg, bytes)

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val v = argb[r * 8 + c]
                val expR = (r * 16 + c) and 0xFF
                val expG = (255 - (r * 16 + c)) and 0xFF
                val expB = ((r + c) * 16) and 0xFF
                assertEquals(expR, (v ushr 16) and 0xFF, "R($r,$c)")
                assertEquals(expG, (v ushr 8) and 0xFF, "G($r,$c)")
                assertEquals(expB, v and 0xFF, "B($r,$c)")
            }
        }
    }

    @Test
    fun decodeArgb_threeBandRgb_imodeP_matchesB() {
        val pixel: (Int, Int, Int) -> Int = { band, r, c ->
            when (band) { 0 -> r * 31; 1 -> c * 31; else -> (r * c) % 251 } and 0xFF
        }
        val bytesB = synthNitf(NitfFixture(
            blocksPerRow = 2, blocksPerCol = 2, pixelsPerBlockH = 4, pixelsPerBlockV = 4,
            nbands = 3, irep = "RGB", imode = 'B', pixel = pixel
        ))
        val bytesP = synthNitf(NitfFixture(
            blocksPerRow = 2, blocksPerCol = 2, pixelsPerBlockH = 4, pixelsPerBlockV = 4,
            nbands = 3, irep = "RGB", imode = 'P', pixel = pixel
        ))
        val segB = Nitf.parse(bytesB).imageSegments[0]
        val segP = Nitf.parse(bytesP).imageSegments[0]
        val argbB = NitfImageReader.decodeArgb(segB, bytesB)
        val argbP = NitfImageReader.decodeArgb(segP, bytesP)
        for (i in argbB.indices) assertEquals(argbB[i], argbP[i], "imode P vs B at $i")
    }

    @Test
    fun decodeArgb_imodeR_andS_matchB() {
        val pixel: (Int, Int, Int) -> Int = { band, r, c ->
            when (band) { 0 -> r * 9 + c; 1 -> r * 7 + c * 3; else -> r + c * 13 } and 0xFF
        }
        val bytesB = synthNitf(NitfFixture(
            blocksPerRow = 2, blocksPerCol = 2, pixelsPerBlockH = 4, pixelsPerBlockV = 4,
            nbands = 3, irep = "RGB", imode = 'B', pixel = pixel
        ))
        val bytesR = synthNitf(NitfFixture(
            blocksPerRow = 2, blocksPerCol = 2, pixelsPerBlockH = 4, pixelsPerBlockV = 4,
            nbands = 3, irep = "RGB", imode = 'R', pixel = pixel
        ))
        val bytesS = synthNitf(NitfFixture(
            blocksPerRow = 2, blocksPerCol = 2, pixelsPerBlockH = 4, pixelsPerBlockV = 4,
            nbands = 3, irep = "RGB", imode = 'S', pixel = pixel
        ))
        val refB = NitfImageReader.decodeArgb(Nitf.parse(bytesB).imageSegments[0], bytesB)
        val outR = NitfImageReader.decodeArgb(Nitf.parse(bytesR).imageSegments[0], bytesR)
        val outS = NitfImageReader.decodeArgb(Nitf.parse(bytesS).imageSegments[0], bytesS)
        for (i in refB.indices) {
            assertEquals(refB[i], outR[i], "imode R vs B at $i")
            assertEquals(refB[i], outS[i], "imode S vs B at $i")
        }
    }

    @Test
    fun decodeArgb_singleBandMono_16bit_unsignedScalesToGreyRamp() {
        val pixel: (Int, Int, Int) -> Int = { _, r, c -> ((r * 8 + c) * 257) and 0xFFFF } // 16-bit ramp
        val bytes = synthNitf(NitfFixture(nbpp = 16, pixel = pixel))
        val seg = Nitf.parse(bytes).imageSegments[0]
        val argb = NitfImageReader.decodeArgb(seg, bytes)
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val v = argb[r * 8 + c]
                val expected = (r * 8 + c) and 0xFF // 16-bit value >>> 8 == row*8+col
                assertEquals(expected, v and 0xFF, "16-bit grey at ($r,$c)")
            }
        }
    }

    // --- NM masked block -----------------------------------------------------

    @Test
    fun decodeArgb_nmMask_missingBlockIsZero() {
        val fixture = NitfFixture(
            blocksPerRow = 2, blocksPerCol = 2,
            pixelsPerBlockH = 4, pixelsPerBlockV = 4,
            masked = true,
            maskedBlocks = setOf(0), // mask out the top-left block
            pixel = { _, r, c -> ((r + 1) * (c + 1)) and 0xFF }
        )
        val bytes = synthNitf(fixture)
        val seg = Nitf.parse(bytes).imageSegments[0]
        val argb = NitfImageReader.decodeArgb(seg, bytes)

        // Top-left 4x4 block all transparent black (argb == 0xFF000000 because
        // alpha is fixed in our converter and RGB stays 0).
        for (r in 0 until 4) {
            for (c in 0 until 4) {
                val v = argb[r * 8 + c]
                assertEquals(0, v and 0xFFFFFF, "masked block pixel at ($r,$c) RGB = 0")
            }
        }

        // Top-right block (idx 1) is present — sample a pixel.
        val v = argb[0 * 8 + 4]
        assertEquals(((0 + 1) * (4 + 1)) and 0xFF, v and 0xFF)
    }

    @Test
    fun decodeArgb_nmMask_transparentCodeMapsToZero() {
        val fixture = NitfFixture(
            transparentCode = 0xAB,
            masked = true,
            pixel = { _, r, c -> if (r == 3 && c == 3) 0xAB else (r * 16 + c) and 0xFF }
        )
        val bytes = synthNitf(fixture)
        val seg = Nitf.parse(bytes).imageSegments[0]
        val argb = NitfImageReader.decodeArgb(seg, bytes)
        // The one pixel set to TPXCD must round-trip to RGB=0,0,0.
        assertEquals(0, argb[3 * 8 + 3] and 0xFFFFFF, "TPXCD pixel becomes zero")
        // Sanity: a non-TPXCD pixel still carries its value.
        assertEquals((1 * 16 + 2) and 0xFF, argb[1 * 8 + 2] and 0xFF)
    }
}
