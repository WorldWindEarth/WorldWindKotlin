package earth.worldwind.layer.ogc3d.content.spz

import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpzGaussianLoaderTest {

    private val loader = SpzGaussianLoader()
    private val savedInflater = SpzGaussianLoader.inflater

    @BeforeTest fun clearInflater() { SpzGaussianLoader.inflater = null }
    @AfterTest fun restoreInflater() { SpzGaussianLoader.inflater = savedInflater }

    @Test fun supportsRecognisesMagic() {
        val good = ByteArray(16).apply {
            this[0] = 'N'.code.toByte(); this[1] = 'G'.code.toByte()
            this[2] = 'S'.code.toByte(); this[3] = 'P'.code.toByte()
        }
        assertTrue(loader.supports(good))
        // Same length, wrong magic.
        val bad = ByteArray(16).apply {
            this[0] = 'B'.code.toByte(); this[1] = 'A'.code.toByte()
            this[2] = 'D'.code.toByte(); this[3] = '!'.code.toByte()
        }
        assertEquals(false, loader.supports(bad))
        // Too short.
        assertEquals(false, loader.supports(ByteArray(8)))
    }

    @Test fun parseThrowsWithoutInflater() {
        // Inflater unset (BeforeTest cleared it). parse() should detect the magic, then fail
        // with a clear "register an inflater" message rather than crashing the GZIP decode.
        val header = makeHeader(version = 2, numPoints = 0, shDegree = 0, fractionalBits = 12)
        assertFailsWith<IllegalStateException> { loader.parse(header) }
    }

    @Test fun parseRejectsBadMagic() {
        val bad = ByteArray(16) { 0 }
        assertFailsWith<IllegalArgumentException> { loader.parse(bad) }
    }

    @Test fun parseRejectsUnsupportedVersion() {
        val header = makeHeader(version = 99, numPoints = 0, shDegree = 0, fractionalBits = 12)
        SpzGaussianLoader.inflater = SpzInflater { ByteArray(0) }
        assertFailsWith<IllegalArgumentException> { loader.parse(header) }
    }

    @Test fun decodeBodyProducesExpectedPayloadShape() {
        // Construct a 2-splat post-decompression payload by hand so we can verify field
        // decoding without depending on a real gzip stream. shDegree=0 → no SH extras.
        val numPoints = 2
        val fractionalBits = 12
        val body = ByteArray(
            numPoints * 9 + // positions
                numPoints + // alpha
                numPoints * 3 + // colour DC
                numPoints * 3 + // scale
                numPoints * 3, // rotation xyz
        )
        // Positions: splat 0 at origin, splat 1 at +1m along X (raw value = 2^12).
        writeInt24LE(body, 0, 0); writeInt24LE(body, 3, 0); writeInt24LE(body, 6, 0)
        writeInt24LE(body, 9, 1 shl fractionalBits); writeInt24LE(body, 12, 0); writeInt24LE(body, 15, 0)

        val alphaOffset = numPoints * 9
        body[alphaOffset + 0] = 127.toByte() // ~0.5 sigmoid
        body[alphaOffset + 1] = 255.toByte() // → ~1.0 alpha

        val colorOffset = alphaOffset + numPoints
        // Mid-grey for splat 0 (RGB = 127 ⇒ DC SH = 0 ⇒ linear 0.5).
        body[colorOffset + 0] = 127.toByte(); body[colorOffset + 1] = 127.toByte(); body[colorOffset + 2] = 127.toByte()
        // Saturated for splat 1.
        body[colorOffset + 3] = 255.toByte(); body[colorOffset + 4] = 255.toByte(); body[colorOffset + 5] = 255.toByte()

        val scaleOffset = colorOffset + numPoints * 3
        // 0x7f for all axes → exp(0/16) ≈ 1.0 metre.
        for (axis in 0..5) body[scaleOffset + axis] = 127.toByte()

        val rotOffset = scaleOffset + numPoints * 3
        // (0, 0, 0) for both splats → identity quaternion (w=1).
        for (axis in 0..5) body[rotOffset + axis] = 127.toByte()

        val payload = loader.decodeBody(body, numPoints, shDegree = 0, fractionalBits = fractionalBits)

        assertEquals(2, payload.splatCount)
        assertEquals(0f, payload.centers[0], absoluteTolerance = 1e-4f)
        assertEquals(1f, payload.centers[3], absoluteTolerance = 1e-3f) // splat 1 at ~1m X

        assertTrue(payload.opacities[0] in 0.4f..0.6f) // sigmoid near zero
        assertTrue(payload.opacities[1] > 0.7f)

        val rgba = payload.rgba
        assertNotNull(rgba)
        assertEquals(0, rgba.size % 4)
        // Identity quaternion: x=y=z=0, w=1.
        assertEquals(0f, payload.rotations[0], absoluteTolerance = 1e-2f)
        assertEquals(0f, payload.rotations[1], absoluteTolerance = 1e-2f)
        assertEquals(0f, payload.rotations[2], absoluteTolerance = 1e-2f)
        assertTrue(abs(payload.rotations[3]) > 0.99f)

        // Scale ≈ 1m.
        assertTrue(payload.scales[0] in 0.9f..1.1f)

        // No SH bands → null.
        assertNull(payload.sphericalHarmonics)
        assertEquals(0, payload.sphericalHarmonicsBands)
    }

    @Test fun decodeBodySupportsHigherShDegree() {
        // shDegree=1 → 4 coeffs per channel → 3 extra coeffs per channel per splat → 9 bytes/splat.
        val numPoints = 1
        val fractionalBits = 12
        val shDegree = 1
        val coeffsPerChannel = (shDegree + 1) * (shDegree + 1) // 4
        val shExtraBytes = numPoints * 3 * (coeffsPerChannel - 1) // 9
        val body = ByteArray(
            numPoints * 9 + numPoints + numPoints * 3 + numPoints * 3 + numPoints * 3 + shExtraBytes,
        )
        // Fill mid-byte 127 so every decoded coeff lands near 0 — easier than zeroing.
        for (i in body.indices) body[i] = 127.toByte()

        val payload = loader.decodeBody(body, numPoints, shDegree = shDegree, fractionalBits = fractionalBits)
        assertEquals(1, payload.splatCount)
        assertEquals(shDegree, payload.sphericalHarmonicsBands)
        val sh = payload.sphericalHarmonics
        assertNotNull(sh)
        assertEquals(shExtraBytes, sh.size)
        // Each coeff: (127 - 127.5) / 128 ≈ -0.0039.
        for (v in sh) assertTrue(abs(v) < 0.01f, "expected near-zero SH coeff, got $v")
    }

    // ---------- helpers ----------

    private fun makeHeader(version: Int, numPoints: Int, shDegree: Int, fractionalBits: Int): ByteArray {
        val out = ByteArray(16)
        out[0] = 'N'.code.toByte(); out[1] = 'G'.code.toByte()
        out[2] = 'S'.code.toByte(); out[3] = 'P'.code.toByte()
        writeUInt32LE(out, 4, version)
        writeUInt32LE(out, 8, numPoints)
        out[12] = shDegree.toByte()
        out[13] = fractionalBits.toByte()
        out[14] = 0
        out[15] = 0
        return out
    }

    private fun writeUInt32LE(out: ByteArray, offset: Int, v: Int) {
        out[offset] = (v and 0xFF).toByte()
        out[offset + 1] = ((v ushr 8) and 0xFF).toByte()
        out[offset + 2] = ((v ushr 16) and 0xFF).toByte()
        out[offset + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun writeInt24LE(out: ByteArray, offset: Int, v: Int) {
        out[offset] = (v and 0xFF).toByte()
        out[offset + 1] = ((v ushr 8) and 0xFF).toByte()
        out[offset + 2] = ((v ushr 16) and 0xFF).toByte()
    }
}
