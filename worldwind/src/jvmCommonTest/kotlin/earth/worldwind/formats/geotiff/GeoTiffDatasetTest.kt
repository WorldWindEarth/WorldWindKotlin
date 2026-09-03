package earth.worldwind.formats.geotiff

import earth.worldwind.geom.Sector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the tiled GeoTIFF reader. Every fixture is synthesised in memory by
 * [TiffTestFixtures], so the tests cover the real binary layout — headers, IFD chains,
 * tile offsets, compression — without shipping sample files.
 */
class GeoTiffDatasetTest {
    private val width = 40
    private val height = 24
    /** Raster (0,0) sits at 10°E 50°N with 0.01° pixels, so the image spans 10..10.4 / 49.76..50. */
    private val minLongitude = 10.0
    private val maxLatitude = 50.0
    private val pixelSize = 0.01

    /** Distinct value per pixel, so a misplaced sample is obvious rather than plausible. */
    private fun rampValue(x: Int, y: Int) = (x + y * 100).toDouble()

    private fun fullSector() = Sector.fromDegrees(
        maxLatitude - height * pixelSize, minLongitude, height * pixelSize, width * pixelSize
    )

    private fun open(bytes: ByteArray) = assertNotNull(GeoTiffDataset.open(ByteArrayTiffDataSource(bytes)))

    @Test
    fun readsGeoreferencedBounds() {
        val dataset = open(
            TiffTestFixtures.tiledTiff(width, height, tileWidth = 16, tileHeight = 16) { x, y, _ -> rampValue(x, y) }
        )
        assertEquals(minLongitude, dataset.sector.minLongitude.inDegrees, 1e-9)
        assertEquals(minLongitude + width * pixelSize, dataset.sector.maxLongitude.inDegrees, 1e-9)
        assertEquals(maxLatitude - height * pixelSize, dataset.sector.minLatitude.inDegrees, 1e-9)
        assertEquals(maxLatitude, dataset.sector.maxLatitude.inDegrees, 1e-9)
        assertEquals(pixelSize, dataset.degreesPerPixel, 1e-12)
        assertTrue(dataset.isElevation)
    }

    @Test
    fun resamplesEveryTileOfAFloatElevationRaster() {
        val dataset = open(
            TiffTestFixtures.tiledTiff(width, height, tileWidth = 16, tileHeight = 16) { x, y, _ -> rampValue(x, y) }
        )
        val posts = assertNotNull(dataset.sampleElevation(fullSector(), width, height))
        // Sampling the raster's own sector at its own size must reproduce it exactly, which
        // also proves tile indexing and edge-tile padding are right (40x24 is not tile-aligned).
        for (y in 0 until height) for (x in 0 until width) {
            assertEquals(rampValue(x, y).toInt().toShort(), posts[y * width + x], "post ($x, $y)")
        }
    }

    @Test
    fun readsDeflateWithHorizontalPredictor() {
        val dataset = open(
            TiffTestFixtures.tiledTiff(
                width, height, tileWidth = 16, tileHeight = 16,
                bitsPerSample = 16, sampleFormat = TiffConstants.SampleFormat.SIGNED,
                compression = TiffTestFixtures.DEFLATE, predictor = 2,
            ) { x, y, _ -> rampValue(x, y) }
        )
        val posts = assertNotNull(dataset.sampleElevation(fullSector(), width, height))
        for (y in 0 until height) for (x in 0 until width) {
            assertEquals(rampValue(x, y).toInt().toShort(), posts[y * width + x], "post ($x, $y)")
        }
    }

    @Test
    fun readsPackBitsCompressedRaster() {
        val dataset = open(
            TiffTestFixtures.tiledTiff(
                width, height, tileWidth = 16, tileHeight = 16,
                bitsPerSample = 16, sampleFormat = TiffConstants.SampleFormat.SIGNED,
                compression = TiffTestFixtures.PACK_BITS,
            ) { x, y, _ -> rampValue(x, y) }
        )
        val posts = assertNotNull(dataset.sampleElevation(fullSector(), width, height))
        assertEquals(rampValue(7, 5).toInt().toShort(), posts[5 * width + 7])
        assertEquals(rampValue(39, 23).toInt().toShort(), posts[23 * width + 39])
    }

    @Test
    fun readsBigEndianSamples() {
        // Motorola byte order exercises the decoder's mirrored sample-unpacking loops, which
        // are hand-rolled per (width, format, endianness) rather than shared.
        val dataset = open(
            TiffTestFixtures.tiledTiff(
                width, height, tileWidth = 16, tileHeight = 16,
                bitsPerSample = 16, sampleFormat = TiffConstants.SampleFormat.SIGNED,
                bigEndian = true,
            ) { x, y, _ -> rampValue(x, y) }
        )
        val posts = assertNotNull(dataset.sampleElevation(fullSector(), width, height))
        for (y in 0 until height) for (x in 0 until width) {
            assertEquals(rampValue(x, y).toInt().toShort(), posts[y * width + x], "post ($x, $y)")
        }
    }

    @Test
    fun readsBigEndianFloatSamples() {
        val dataset = open(
            TiffTestFixtures.tiledTiff(
                width, height, tileWidth = 16, tileHeight = 16, bigEndian = true
            ) { x, y, _ -> rampValue(x, y) }
        )
        val posts = assertNotNull(dataset.sampleElevation(fullSector(), width, height))
        assertEquals(rampValue(12, 9).toInt().toShort(), posts[9 * width + 12])
        assertEquals(rampValue(39, 23).toInt().toShort(), posts[23 * width + 39])
    }

    @Test
    fun readsUnsignedSamplesWithoutSignExtension() {
        // 16-bit unsigned values above 0x7FFF must not come back negative — the unsigned
        // loops mask the sign-extended read that the signed ones rely on.
        val dataset = open(
            TiffTestFixtures.tiledTiff(
                width = 8, height = 8, tileWidth = 8, tileHeight = 8,
                bitsPerSample = 16, sampleFormat = TiffConstants.SampleFormat.UNSIGNED,
            ) { x, _, _ -> if (x == 0) 40000.0 else 100.0 }
        )
        val sector = Sector.fromDegrees(50.0 - 8 * pixelSize, minLongitude, 8 * pixelSize, 8 * pixelSize)
        val posts = assertNotNull(dataset.sampleElevation(sector, 8, 8))
        // 40000 exceeds Int16, so it clamps at the coverage's max rather than wrapping negative.
        assertEquals(Short.MAX_VALUE, posts[0], "unsigned sample stays positive")
        assertEquals(100.toShort(), posts[4])
    }

    @Test
    fun readsBigTiffHeaders() {
        val dataset = open(
            TiffTestFixtures.tiledTiff(
                width, height, tileWidth = 16, tileHeight = 16, bigTiff = true
            ) { x, y, _ -> rampValue(x, y) }
        )
        assertEquals(width, dataset.primary.imageWidth)
        val posts = assertNotNull(dataset.sampleElevation(fullSector(), width, height))
        assertEquals(rampValue(33, 19).toInt().toShort(), posts[19 * width + 33])
    }

    @Test
    fun treatsNoDataAsMissingPosts() {
        val dataset = open(
            TiffTestFixtures.tiledTiff(
                width, height, tileWidth = 16, tileHeight = 16, noData = "-9999"
            ) { x, y, _ -> if (x < 10) -9999.0 else rampValue(x, y) }
        )
        val posts = assertNotNull(dataset.sampleElevation(fullSector(), width, height))
        assertEquals(Short.MIN_VALUE, posts[3 * width + 2], "void post")
        assertEquals(rampValue(20, 3).toInt().toShort(), posts[3 * width + 20], "valid post")
    }

    @Test
    fun buildsResolutionLadderFromOverviews() {
        val dataset = open(
            TiffTestFixtures.tiledTiff(
                width = 64, height = 64, tileWidth = 16, tileHeight = 16,
                overviewFactors = intArrayOf(2, 4),
            ) { x, y, _ -> rampValue(x, y) }
        )
        assertEquals(3, dataset.levels.size)
        assertEquals(64, dataset.levels[0].imageWidth, "full resolution comes first")
        assertEquals(32, dataset.levels[1].imageWidth)
        assertEquals(16, dataset.levels[2].imageWidth)
        // A tile at native resolution reads the full-resolution raster; a tile four times
        // coarser reads the smallest overview instead of decimating it.
        assertEquals(0, dataset.selectLevel(dataset.levelDegreesPerPixel(0)))
        assertEquals(2, dataset.selectLevel(dataset.levelDegreesPerPixel(2)))
        assertEquals(2, dataset.selectLevel(dataset.levelDegreesPerPixel(2) * 8))
    }

    @Test
    fun leavesAreasOutsideTheRasterMissing() {
        val bytes = TiffTestFixtures.tiledTiff(width, height, tileWidth = 16, tileHeight = 16) { x, y, _ ->
            rampValue(x, y)
        }
        val dataset = open(bytes)
        // A sector twice as wide as the raster, anchored at its west edge: the eastern half
        // has no source coverage and must come back as missing rather than as clamped data.
        val wide = Sector.fromDegrees(
            maxLatitude - height * pixelSize, minLongitude, height * pixelSize, width * pixelSize * 2
        )
        val posts = assertNotNull(dataset.sampleElevation(wide, width, height))
        assertTrue(posts[height / 2 * width + 5] != Short.MIN_VALUE, "west half is covered")
        assertEquals(Short.MIN_VALUE, posts[height / 2 * width + width - 2], "east half is outside the raster")
        // A sector nowhere near the raster is refused outright, so no tile is even allocated.
        assertNull(dataset.sampleElevation(Sector.fromDegrees(-40.0, -70.0, 1.0, 1.0), width, height))
    }

    @Test
    fun convertsRgbPixelsToArgb() {
        val dataset = open(
            TiffTestFixtures.tiledTiff(
                width, height, tileWidth = 16, tileHeight = 16,
                samplesPerPixel = 3, bitsPerSample = 8,
                sampleFormat = TiffConstants.SampleFormat.UNSIGNED,
                photometric = TiffConstants.PhotometricInterpretation.RGB,
                compression = TiffTestFixtures.PACK_BITS,
            ) { x, _, band -> (band * 64 + x).toDouble() }
        )
        val pixels = assertNotNull(dataset.sampleArgb(fullSector(), width, height))
        val pixel = pixels[3 * width + 10]
        assertEquals(0xFF, (pixel ushr 24) and 0xFF, "opaque")
        assertEquals(10, (pixel ushr 16) and 0xFF, "red")
        assertEquals(74, (pixel ushr 8) and 0xFF, "green")
        assertEquals(138, pixel and 0xFF, "blue")
    }

    @Test
    fun rejectsNonTiffBytes() {
        val bytes = ByteArray(64) { 0x7F }
        assertTrue(runCatching { GeoTiffDataset.open(ByteArrayTiffDataSource(bytes)) }.isFailure)
    }
}
