package earth.worldwind.layer.ogc3d.content.spz

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end SPZ parse exercising the JVM gzip inflater. The common-test suite
 * verifies the wire-format decode against hand-rolled inflated bytes; here we round-trip
 * through actual gzip to prove [JvmSpzInflater] hooks into the loader correctly.
 */
class SpzGaussianLoaderJvmTest {

    private val savedInflater = SpzGaussianLoader.inflater

    @BeforeTest fun installInflater() { SpzGaussianLoader.inflater = JvmSpzInflater() }
    @AfterTest fun restoreInflater() { SpzGaussianLoader.inflater = savedInflater }

    @Test fun roundTripsThroughGzip() {
        // One splat at origin, identity rotation, mid-grey RGB, near-zero opacity.
        val numPoints = 1
        val shDegree = 0
        val fractionalBits = 12
        val body = ByteArray(numPoints * 9 + numPoints + numPoints * 3 + numPoints * 3 + numPoints * 3)
        // Positions = 0, alpha = 127, color = 127×3, scale = 127×3, rotation = 127×3 → identity.
        for (i in 9 until body.size) body[i] = 127.toByte()

        val gzip = ByteArrayOutputStream()
        GZIPOutputStream(gzip).use { it.write(body) }

        val payload = ByteArray(SpzGaussianLoader.HEADER_SIZE + gzip.size())
        payload[0] = 'N'.code.toByte()
        payload[1] = 'G'.code.toByte()
        payload[2] = 'S'.code.toByte()
        payload[3] = 'P'.code.toByte()
        // version = 2 LE
        payload[4] = 2
        // numPoints = 1 LE
        payload[8] = 1
        payload[12] = shDegree.toByte()
        payload[13] = fractionalBits.toByte()
        gzip.toByteArray().copyInto(payload, SpzGaussianLoader.HEADER_SIZE)

        val out = SpzGaussianLoader().parse(payload)
        assertEquals(1, out.splatCount)
        assertEquals(3, out.centers.size)
        assertEquals(0f, out.centers[0])
        assertEquals(0f, out.centers[1])
        assertEquals(0f, out.centers[2])
    }
}
