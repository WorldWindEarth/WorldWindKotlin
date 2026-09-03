package earth.worldwind.formats

import earth.worldwind.formats.geotiff.TiffTestFixtures
import java.util.zip.Deflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Round-trip tests for the cross-platform inflater against `java.util.zip.Deflater` —
 * the encoder that produced the Deflate-compressed GeoTIFFs it has to read.
 */
class InflateTest {

    private fun roundTrip(original: ByteArray) {
        val compressed = TiffTestFixtures.deflate(original)
        assertContentEquals(original, Inflate.inflate(compressed, 0, compressed.size, original.size))
    }

    @Test
    fun inflatesHighlyCompressibleData() {
        // Long runs exercise back-references at every distance the length codes cover.
        roundTrip(ByteArray(64 * 1024) { (it / 512).toByte() })
    }

    @Test
    fun inflatesIncompressibleData() {
        // Random bytes push the encoder onto stored / near-stored blocks.
        roundTrip(Random(7).nextBytes(40000))
    }

    @Test
    fun inflatesElevationLikeData() {
        // The shape a DEM strip actually has: smooth 16-bit terrain, which is what the
        // dynamic-Huffman path sees in practice.
        val original = ByteArray(32 * 1024)
        for (i in original.indices step 2) {
            val value = 1000 + (i / 2) % 250
            original[i] = (value and 0xFF).toByte()
            original[i + 1] = ((value shr 8) and 0xFF).toByte()
        }
        roundTrip(original)
    }

    @Test
    fun inflatesRawDeflateWithoutZlibHeader() {
        val original = ByteArray(4096) { (it * 31).toByte() }
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true) // nowrap: no zlib header
        deflater.setInput(original)
        deflater.finish()
        val compressed = ByteArray(original.size * 2)
        val size = deflater.deflate(compressed)
        deflater.end()
        assertContentEquals(original, Inflate.inflate(compressed, 0, size, original.size))
    }

    @Test
    fun truncatedInputYieldsWhatDecodedSoFar() {
        val original = ByteArray(8192) { (it / 16).toByte() }
        val compressed = TiffTestFixtures.deflate(original)
        // A short read must not throw: a corrupt block still renders the rows that survived.
        assertEquals(original.size, Inflate.inflate(compressed, 0, compressed.size / 2, original.size).size)
    }
}
