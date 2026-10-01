package earth.worldwind.formats.geotiff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for telling maps from terrain. GeoTIFF has no tag that states which it is, so each
 * case here pins one rung of the inference ladder — and the ambiguous rung is asserted to
 * stay ambiguous rather than being guessed at.
 */
class GeoTiffContentTest {
    private fun open(bytes: ByteArray) = assertNotNull(GeoTiffDataset.open(ByteArrayTiffDataSource(bytes)))

    private fun raster(
        samplesPerPixel: Int = 1,
        bitsPerSample: Int = 16,
        sampleFormat: Int = TiffConstants.SampleFormat.UNSIGNED,
        photometric: Int = TiffConstants.PhotometricInterpretation.BLACK_IS_ZERO,
        noData: String? = null,
        sample: (Int, Int, Int) -> Double = { x, y, _ -> (x + y).toDouble() },
    ) = open(
        TiffTestFixtures.tiledTiff(
            width = 32, height = 32, tileWidth = 16, tileHeight = 16,
            samplesPerPixel = samplesPerPixel, bitsPerSample = bitsPerSample,
            sampleFormat = sampleFormat, photometric = photometric, noData = noData,
            sample = sample,
        )
    )

    @Test
    fun rgbBandsAreImagery() {
        val hint = raster(
            samplesPerPixel = 3, bitsPerSample = 8,
            sampleFormat = TiffConstants.SampleFormat.UNSIGNED,
            photometric = TiffConstants.PhotometricInterpretation.RGB,
        ).contentHint
        assertEquals(GeoTiffContent.IMAGERY, hint.content)
        assertEquals("3 bands", hint.reason)
    }

    @Test
    fun eightBitGrayIsImagery() {
        // One 8-bit band spans 255 steps: a grayscale picture, never terrain in metres.
        val hint = raster(bitsPerSample = 8).contentHint
        assertEquals(GeoTiffContent.IMAGERY, hint.content)
        assertEquals("single 8-bit band", hint.reason)
    }

    @Test
    fun whiteIsZeroIsImagery() {
        // The scanned-chart convention; height fields are never stored inverted.
        val hint = raster(photometric = TiffConstants.PhotometricInterpretation.WHITE_IS_ZERO).contentHint
        assertEquals(GeoTiffContent.IMAGERY, hint.content)
    }

    @Test
    fun floatSamplesAreElevation() {
        val hint = raster(
            bitsPerSample = 32, sampleFormat = TiffConstants.SampleFormat.IEEE_FLOAT
        ).contentHint
        assertEquals(GeoTiffContent.ELEVATION, hint.content)
        assertTrue("float" in hint.reason, hint.reason)
    }

    @Test
    fun signedSamplesAreElevation() {
        // Signed 16-bit is the SRTM / DTED convention; imagery is unsigned.
        val hint = raster(sampleFormat = TiffConstants.SampleFormat.SIGNED).contentHint
        assertEquals(GeoTiffContent.ELEVATION, hint.content)
        assertTrue("signed" in hint.reason, hint.reason)
    }

    @Test
    fun noDataMakesAnUnsignedBandElevation() {
        val hint = raster(noData = "0").contentHint
        assertEquals(GeoTiffContent.ELEVATION, hint.content)
        assertTrue("GDAL_NODATA" in hint.reason, hint.reason)
    }

    @Test
    fun unsignedGrayWithoutEvidenceIsAmbiguous() {
        val dataset = raster()
        assertEquals(GeoTiffContent.AMBIGUOUS, dataset.contentHint.content)
        // isElevation keeps serving a caller that asked for terrain, even when undecided.
        assertTrue(dataset.isElevation)
    }

    @Test
    fun probeCallsFullRangeSamplesImagery() {
        // Landsat / Sentinel panchromatic counts run far past any terrain height.
        val dataset = raster { x, _, _ -> if (x == 0) 41000.0 else 600.0 }
        assertEquals(GeoTiffContent.AMBIGUOUS, dataset.contentHint.content)
        val probe = dataset.probeContent()
        assertEquals(GeoTiffContent.IMAGERY, probe.content)
        assertTrue("beyond any terrain" in probe.reason, probe.reason)
    }

    @Test
    fun probeLeavesTerrainSizedRangeAmbiguous() {
        // 0..4095 is both 12-bit imagery and lowland terrain in metres; nothing in the pixels
        // separates them, so the verdict must stay undecided for the UI to ask.
        val dataset = raster { x, y, _ -> (x * 64 + y).toDouble() }
        val probe = dataset.probeContent()
        assertEquals(GeoTiffContent.AMBIGUOUS, probe.content)
        assertTrue("plausible as both" in probe.reason, probe.reason)
    }

    @Test
    fun probePassesThroughADecidedHint() {
        val dataset = raster(sampleFormat = TiffConstants.SampleFormat.SIGNED)
        val probe = dataset.probeContent()
        assertEquals(GeoTiffContent.ELEVATION, probe.content)
        // Decided by tags, so the probe reports the same evidence rather than reading pixels.
        assertEquals(dataset.contentHint.reason, probe.reason)
    }

    @Test
    fun paletteIsImagery() {
        val hint = raster(
            bitsPerSample = 8, photometric = TiffConstants.PhotometricInterpretation.RGB_PALETTE
        ).contentHint
        assertEquals(GeoTiffContent.IMAGERY, hint.content)
    }

    @Test
    fun grayWithAlphaIsImagery() {
        // Two bands where the second is alpha: the band count alone wouldn't catch this, so
        // the ExtraSamples rung is what rules out terrain.
        val dataset = open(
            TiffTestFixtures.tiledTiff(
                width = 32, height = 32, tileWidth = 16, tileHeight = 16,
                samplesPerPixel = 2, bitsPerSample = 16,
                sampleFormat = TiffConstants.SampleFormat.UNSIGNED,
                extraSamples = intArrayOf(2), // unassociated alpha
            ) { x, y, band -> if (band == 1) 65535.0 else (x + y).toDouble() }
        )
        assertEquals(1, dataset.primary.alphaBand)
        assertEquals(GeoTiffContent.IMAGERY, dataset.contentHint.content)
        assertEquals("alpha band", dataset.contentHint.reason)
    }

    @Test
    fun probeReadsTheCoarsestOverviewNotFullResolution() {
        // Out-of-range values sit only on odd columns, which the 2x overview steps over. If the
        // probe read full resolution it would call this imagery; reading the overview it cannot
        // see them, so the verdict must stay undecided.
        val dataset = open(
            TiffTestFixtures.tiledTiff(
                width = 64, height = 64, tileWidth = 16, tileHeight = 16,
                bitsPerSample = 16, sampleFormat = TiffConstants.SampleFormat.UNSIGNED,
                overviewFactors = intArrayOf(2),
            ) { x, _, _ -> if (x % 2 == 1) 50000.0 else 700.0 }
        )
        assertEquals(2, dataset.levels.size)
        val probe = dataset.probeContent()
        assertEquals(GeoTiffContent.AMBIGUOUS, probe.content)
        // Name the values it did see, so this can't pass by failing to read anything at all.
        assertTrue("700" in probe.reason, probe.reason)
    }

    @Test
    fun probeSpreadsAcrossBlocksRatherThanReadingTheFirst() {
        // 64x64 in 16x16 tiles is 16 blocks; the telltale values live only in block 12, so a
        // probe that stopped at block 0 would miss them.
        val dataset = open(
            TiffTestFixtures.tiledTiff(
                width = 64, height = 64, tileWidth = 16, tileHeight = 16,
                bitsPerSample = 16, sampleFormat = TiffConstants.SampleFormat.UNSIGNED,
            ) { x, y, _ -> if (x < 16 && y >= 48) 50000.0 else 700.0 }
        )
        assertEquals(GeoTiffContent.IMAGERY, dataset.probeContent().content)
    }

    @Test
    fun verticalCrsGeoKeyIsExposedAndDecisive() {
        // GeoKeys: geographic model, pixel-is-area, WGS 84, plus VerticalCSTypeGeoKey = EGM96.
        val bytes = TiffTestFixtures.tiledTiff(
            width = 32, height = 32, tileWidth = 16, tileHeight = 16, bitsPerSample = 16,
            sampleFormat = TiffConstants.SampleFormat.UNSIGNED,
            geoKeys = intArrayOf(
                1, 1, 0, 4,
                1024, 0, 1, 2,
                1025, 0, 1, 1,
                2048, 0, 1, 4326,
                GeoTiffConstants.VERTICAL_CS_TYPE_GEO_KEY, 0, 1, 5171,
            ),
        ) { x, y, _ -> (x + y).toDouble() }
        val dataset = open(bytes)
        assertEquals(5171.0, dataset.primary.geoKeys[GeoTiffConstants.VERTICAL_CS_TYPE_GEO_KEY])
        assertTrue(dataset.primary.hasVerticalCrs)
        assertEquals(GeoTiffContent.ELEVATION, dataset.contentHint.content)
        assertEquals("vertical CRS geokey", dataset.contentHint.reason)
    }
}
