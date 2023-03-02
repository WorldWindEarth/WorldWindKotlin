package earth.worldwind.formats.tiff

import java.nio.ByteBuffer
import kotlin.test.*

class TiffTest {
    private lateinit var geotiffData: ByteArray
    private lateinit var blendtiffData: ByteArray

    @BeforeTest
    fun setup() {
        geotiffData = setupData("test_worldwind_geotiff.tif")
        blendtiffData = setupData("test_worldwind_blend.tif")
    }

    private fun setupData(resourceName: String) = javaClass.classLoader!!.getResourceAsStream(resourceName)!!.use { it.readBytes() }

    @Test
    fun testImageWidthAndLength_GeoTiff() {
        val tiff = Tiff(ByteBuffer.wrap(geotiffData))
        val file = tiff.subfiles[0]
        val expected = 512
        val actualWidth = file.imageWidth
        val actualLength = file.imageLength
        assertEquals(expected, actualWidth, "image width")
        assertEquals(expected, actualLength, "image length")
    }

    @Test
    fun testGetOffsets_GeoTiff() {
        val tiff = Tiff(ByteBuffer.wrap(geotiffData))
        val file = tiff.subfiles[0]
        // the first twelve values and the last
        val expectedOffsets = intArrayOf(
            930, 9122, 17314, 25506, 33698, 41890, 50082, 58274, 66466, 74658, 82850, 91042, 517026
        )
        val actualOffsets = file.stripOffsets
        assertNotNull(actualOffsets)

        // modify the actual offsets to limit the number of test points, didn't want to write in all of the offsets
        val modActualOffsets = actualOffsets.copyOfRange(0, 13)
        modActualOffsets[12] = actualOffsets[actualOffsets.size - 1]
        assertContentEquals(expectedOffsets, modActualOffsets, "image offsets")
    }

    @Test
    fun testGetDataSize_GeoTiff() {
        val tiff = Tiff(ByteBuffer.wrap(geotiffData))
        val file = tiff.subfiles[0]
        val expectedSize = 524288
        val actualSize = file.getDataSize()
        assertEquals(expectedSize, actualSize, "image geotiffData size")
    }

    @Test
    fun testGetData_Execution_GeoTiff() {
        val tiff = Tiff(ByteBuffer.wrap(geotiffData))
        val file = tiff.subfiles[0]
        val data = ByteBuffer.allocate(file.getDataSize())
        val expectedPosition = file.getDataSize()
        file.getData(data)
        assertEquals(expectedPosition, data.position(), "bytebuffer position after geotiffData load")
    }

    @Test
    fun testGetBitsPerSample_GeoTiff() {
        val tiff = Tiff(ByteBuffer.wrap(geotiffData))
        val file = tiff.subfiles[0]
        val expectedBitsPerSample = 16
        val expectedComponentsPerPixel = 1
        val actualBitsPerSample = file.bitsPerSample
        assertEquals(expectedComponentsPerPixel, actualBitsPerSample.size, "bits per sample components")
        assertEquals(expectedBitsPerSample, actualBitsPerSample[0], "bits per sample values")
    }

    @Test
    fun testGetByteCounts_GeoTiff() {
        val tiff = Tiff(ByteBuffer.wrap(geotiffData))
        val file = tiff.subfiles[0]
        val expectedByteCounts = intArrayOf(8192, 8192, 8192, 8192, 8192, 8192, 8192, 8192, 8192, 8192, 8192, 8192, 8192)
        val actualByteCounts = file.stripByteCounts
        assertNotNull(actualByteCounts)

        // modify the actual bytes counts in order to reduce the number of test points
        val modActualByteCounts = actualByteCounts.copyOf(13)
        modActualByteCounts[12] = actualByteCounts[actualByteCounts.size - 1]
        assertContentEquals(expectedByteCounts, modActualByteCounts, "byte counts")
    }

    @Test
    fun testGetCompression_GeoTiff() {
        val tiff = Tiff(ByteBuffer.wrap(geotiffData))
        val file = tiff.subfiles[0]
        val expectedValue = 1
        val actualValue = file.compression
        assertEquals(expectedValue, actualValue, "compression type")
    }

    @Test
    fun testGetPhotometricInterpretation_GeoTiff() {
        val tiff = Tiff(ByteBuffer.wrap(geotiffData))
        val file = tiff.subfiles[0]
        val expectedValue = 1
        val actualValue = file.photometricInterpretation
        assertEquals(expectedValue, actualValue, "photometric interpretation")
    }

    @Test
    fun testGetResolutionUnit_GeoTiff() {
        val tiff = Tiff(ByteBuffer.wrap(geotiffData))
        val file = tiff.subfiles[0]
        val expectedValue = 2
        val actualValue = file.resolutionUnit
        assertEquals(expectedValue, actualValue, "resolution unit")
    }

    @Test
    fun testGetRowsPerStrip_GeoTiff() {
        val tiff = Tiff(ByteBuffer.wrap(geotiffData))
        val file = tiff.subfiles[0]
        val expectedValue = 8
        val actualValue = file.rowsPerStrip
        assertEquals(expectedValue, actualValue, "rows per strip")
    }

    @Test
    fun testGetSampleFormat_GeoTiff() {
        val tiff = Tiff(ByteBuffer.wrap(geotiffData))
        val file = tiff.subfiles[0]
        val expectedSampleFormat = 2
        val expectedComponentsPerPixel = 1
        val actualSampleFormat = file.sampleFormat
        assertEquals(expectedComponentsPerPixel, actualSampleFormat.size, "sample format components")
        assertEquals(expectedSampleFormat, actualSampleFormat[0], "sample format values")
    }

    @Test
    fun testSamplesPerPixel_GeoTiff() {
        val tiff = Tiff(ByteBuffer.wrap(geotiffData))
        val file = tiff.subfiles[0]
        val expectedValue = 1
        val actualValue = file.samplesPerPixel
        assertEquals(expectedValue, actualValue, "samples per pixel")
    }

    @Test
    fun testGetResolutions_GeoTiff() {
        val tiff = Tiff(ByteBuffer.wrap(geotiffData))
        val file = tiff.subfiles[0]
        val delta = 1e-9
        val expectedValue = 72.0
        val xResolution = file.xResolution
        val yResolution = file.yResolution
        assertEquals(expectedValue, xResolution, delta, "x resolution")
        assertEquals(expectedValue, yResolution, delta, "y resolution")
    }

    @Test
    fun testImageWidthAndLength_BlendTiff() {
        val tiff = Tiff(ByteBuffer.wrap(blendtiffData))
        val file = tiff.subfiles[0]
        val expectedWidth = 640
        val expectedLength = 400
        val actualWidth = file.imageWidth
        val actualLength = file.imageLength
        assertEquals(expectedWidth, actualWidth, "image width")
        assertEquals(expectedLength, actualLength, "image length")
    }

    @Test
    fun testGetOffsets_BlendTiff() {
        val tiff = Tiff(ByteBuffer.wrap(blendtiffData))
        val file = tiff.subfiles[0]
        val expectedOffsets = intArrayOf(8, 40968, 81928, 122888, 163848, 204808, 245768)
        val actualOffsets = file.stripOffsets
        assertContentEquals(expectedOffsets, actualOffsets, "image offsets")
    }

    @Test
    fun testGetDataSize_BlendTiff() {
        val tiff = Tiff(ByteBuffer.wrap(blendtiffData))
        val file = tiff.subfiles[0]
        val expectedSize = 256000
        val actualSize = file.getDataSize()
        assertEquals(expectedSize, actualSize, "image geotiffData size")
    }

    @Test
    fun testGetData_Execution_BlendTiff() {
        val tiff = Tiff(ByteBuffer.wrap(blendtiffData))
        val file = tiff.subfiles[0]
        val data = ByteBuffer.allocate(file.getDataSize())
        val expectedPosition = file.getDataSize()
        file.getData(data)
        assertEquals(expectedPosition, data.position(), "bytebuffer position after geotiffData load")
    }

    @Test
    fun testGetBitsPerSample_BlendTiff() {
        val tiff = Tiff(ByteBuffer.wrap(blendtiffData))
        val file = tiff.subfiles[0]
        val expectedBitsPerSample = 8
        val expectedComponentsPerPixel = 1
        val actualBitsPerSample = file.bitsPerSample
        assertEquals(expectedComponentsPerPixel, actualBitsPerSample.size, "bits per sample components")
        assertEquals(expectedBitsPerSample, actualBitsPerSample[0], "bits per sample values")
    }

    @Test
    fun testGetByteCounts_BlendTiff() {
        val tiff = Tiff(ByteBuffer.wrap(blendtiffData))
        val file = tiff.subfiles[0]
        val expectedByteCounts = intArrayOf(40960, 40960, 40960, 40960, 40960, 40960, 10240)
        val actualByteCounts = file.stripByteCounts
        assertContentEquals(expectedByteCounts, actualByteCounts, "byte counts")
    }

    @Test
    fun testGetCompression_BlendTiff() {
        val tiff = Tiff(ByteBuffer.wrap(blendtiffData))
        val file = tiff.subfiles[0]
        val expectedValue = 1
        val actualValue = file.compression
        assertEquals(expectedValue, actualValue, "compression type")
    }

    @Test
    fun testGetPhotometricInterpretation_BlendTiff() {
        val tiff = Tiff(ByteBuffer.wrap(blendtiffData))
        val file = tiff.subfiles[0]
        val expectedValue = 1
        val actualValue = file.photometricInterpretation
        assertEquals(expectedValue, actualValue, "photometric interpretation")
    }

    @Test
    fun testGetResolutionUnit_BlendTiff() {
        val tiff = Tiff(ByteBuffer.wrap(blendtiffData))
        val file = tiff.subfiles[0]
        val expectedValue = 2
        val actualValue = file.resolutionUnit
        assertEquals(expectedValue, actualValue, "resolution unit")
    }

    @Test
    fun testGetRowsPerStrip_BlendTiff() {
        val tiff = Tiff(ByteBuffer.wrap(blendtiffData))
        val file = tiff.subfiles[0]
        val expectedValue = 64
        val actualValue = file.rowsPerStrip
        assertEquals(expectedValue, actualValue, "rows per strip")
    }

    @Test
    fun testGetSampleFormat_BlendTiff() {
        val tiff = Tiff(ByteBuffer.wrap(blendtiffData))
        val file = tiff.subfiles[0]
        val expectedSampleFormat = 1
        val expectedComponentsPerPixel = 1
        val actualSampleFormat = file.sampleFormat
        assertEquals(expectedComponentsPerPixel, actualSampleFormat.size, "sample format components")
        assertEquals(expectedSampleFormat, actualSampleFormat[0], "sample format values")
    }

    @Test
    fun testSamplesPerPixel_BlendTiff() {
        val tiff = Tiff(ByteBuffer.wrap(blendtiffData))
        val file = tiff.subfiles[0]
        val expectedValue = 1
        val actualValue = file.samplesPerPixel
        assertEquals(expectedValue, actualValue, "samples per pixel")
    }

    @Test
    fun testGetResolutions_BlendTiff() {
        val tiff = Tiff(ByteBuffer.wrap(blendtiffData))
        val file = tiff.subfiles[0]
        val delta = 1e-9
        val expectedValue = 96.0
        val xResolution = file.xResolution
        val yResolution = file.yResolution
        assertEquals(expectedValue, xResolution, delta, "x resolution")
        assertEquals(expectedValue, yResolution, delta, "y resolution")
    }

    /**
     * Tiff 6.0 provides two mechanisms for storing chunked imagery data, strips and tiles. This test ensures the
     * [Subfile]s method for converting the individual tiles to a single image buffer is functioning properly. The test
     * uses a 3x3 grid of 16x16 pixels with 3 samples per pixel and 8 bits per sample. The tiles are stored in tiles
     * and referenced by the tileOffset field. For this test, the original tiles use byte values indicating their tile
     * index in the tileOffset array. This method also tests that overlap of a tile past the image border is considered.
     */
    @Test
    fun testTileCombination() {
        val raw = ByteBuffer.allocate(6912)
        raw.put('M'.code.toByte())
        raw.put('M'.code.toByte())
        raw.putShort(42)
        val tiff = Tiff(raw)
        raw.clear()
        val file = Subfile(tiff, 0, true)
        file.tileWidth = 16
        file.tileLength = 16
        file.imageWidth = 40
        file.imageLength = 40
        file.samplesPerPixel = 3
        file.bitsPerSample = intArrayOf(8, 8, 8)
        // canned continuous offsets
        file.tileOffsets = intArrayOf(0, 768, 768 * 2, 768 * 3, 768 * 4, 768 * 5, 768 * 6, 768 * 7, 768 * 8).also {
            for (bOffset in it.indices) {
                val bytes = ByteArray(768)
                // each chunk of tiles should use the value of their index
                bytes.fill(bOffset.toByte())
                raw.put(bytes, 0, 768)
            }
        }
        val result = ByteBuffer.allocate(6912)
        file.combineTiles(result)
        // sample the result 37.5% through the tile in each tile of the grid
        val actualTile0 = result[(6 + 16 * 0 + (6 + 16 * 0) * 40) * 3]
        val actualTile1 = result[(6 + 16 * 1 + (6 + 16 * 0) * 40) * 3]
        val actualTile2 = result[(6 + 16 * 2 + (6 + 16 * 0) * 40) * 3]
        val actualTile3 = result[(6 + 16 * 0 + (6 + 16 * 1) * 40) * 3]
        val actualTile4 = result[(6 + 16 * 1 + (6 + 16 * 1) * 40) * 3]
        val actualTile5 = result[(6 + 16 * 2 + (6 + 16 * 1) * 40) * 3]
        val actualTile6 = result[(6 + 16 * 0 + (6 + 16 * 2) * 40) * 3]
        val actualTile7 = result[(6 + 16 * 1 + (6 + 16 * 2) * 40) * 3]
        val actualTile8 = result[(6 + 16 * 2 + (6 + 16 * 2) * 40) * 3]
        assertEquals(0.toByte(), actualTile0, "tile 0 value")
        assertEquals(1.toByte(), actualTile1, "tile 1 value")
        assertEquals(2.toByte(), actualTile2, "tile 2 value")
        assertEquals(3.toByte(), actualTile3, "tile 3 value")
        assertEquals(4.toByte(), actualTile4, "tile 4 value")
        assertEquals(5.toByte(), actualTile5, "tile 5 value")
        assertEquals(6.toByte(), actualTile6, "tile 6 value")
        assertEquals(7.toByte(), actualTile7, "tile 7 value")
        assertEquals(8.toByte(), actualTile8, "tile 8 value")
    }
}