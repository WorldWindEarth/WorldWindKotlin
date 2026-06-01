package earth.worldwind.layer.ogc3d

import earth.worldwind.layer.ogc3d.content.GaussianLoader
import earth.worldwind.layer.ogc3d.content.GaussianPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GaussianLoaderTest {

    /**
     * A concrete loader that claims bytes starting with the ASCII sentinel `WWGS` (a fake
     * "WorldWind Gaussian Splat" magic). Demonstrates the [GaussianLoader] contract
     * without committing to any real codec.
     */
    private class FakeLoader : GaussianLoader {
        var parsedCount = 0; private set

        override fun supports(bytes: ByteArray): Boolean {
            if (bytes.size < 4) return false
            return bytes[0] == 'W'.code.toByte() && bytes[1] == 'W'.code.toByte() &&
                bytes[2] == 'G'.code.toByte() && bytes[3] == 'S'.code.toByte()
        }

        override fun parse(bytes: ByteArray): GaussianPayload {
            parsedCount++
            // Manufacture a tiny payload to exercise the data shape; we don't render.
            val splatCount = 2
            return GaussianPayload(
                splatCount = splatCount,
                centers = floatArrayOf(0f, 0f, 0f,  1f, 0f, 0f),
                scales = floatArrayOf(0.1f, 0.1f, 0.1f,  0.2f, 0.2f, 0.2f),
                rotations = floatArrayOf(1f, 0f, 0f, 0f,  1f, 0f, 0f, 0f),
                opacities = floatArrayOf(1f, 1f),
                rgba = byteArrayOf(255.toByte(), 0, 0, 255.toByte(),  0, 255.toByte(), 0, 255.toByte()),
            )
        }
    }

    @Test fun supportsMatchesMagic() {
        val loader = FakeLoader()
        assertTrue(loader.supports("WWGS".encodeToByteArray() + byteArrayOf(0, 1, 2)))
        assertFalse(loader.supports("ABCD".encodeToByteArray()))
        assertFalse(loader.supports(ByteArray(2)))
    }

    @Test fun parseProducesPayloadWithExpectedShape() {
        val loader = FakeLoader()
        val payload = loader.parse("WWGS".encodeToByteArray())
        assertEquals(2, payload.splatCount)
        assertEquals(6, payload.centers.size)
        assertEquals(6, payload.scales.size)
        assertEquals(8, payload.rotations.size)
        assertEquals(2, payload.opacities.size)
        val rgba = payload.rgba
        assertNotNull(rgba)
        assertEquals(8, rgba.size)
        assertNull(payload.sphericalHarmonics)
        assertEquals(0, payload.sphericalHarmonicsBands)
    }

    @Test fun parseCountIncrements() {
        val loader = FakeLoader()
        loader.parse("WWGS".encodeToByteArray())
        loader.parse("WWGS".encodeToByteArray())
        assertEquals(2, loader.parsedCount)
    }
}
