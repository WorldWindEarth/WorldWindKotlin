package earth.worldwind.globe.elevation

import java.nio.ShortBuffer
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals

/** Verifies the elevation cache PNG codec round-trips int16 grids losslessly (encode uses
 *  UP row filtering; decode must recover every sample bit-exactly). */
class ElevationPngRoundTripTest {

    private val size = 256

    /** Deterministic value-noise fBm terrain, int16 metres. */
    private fun terrain(seed: Int, octaves: Int, amp: Double, base: Double): ShortArray {
        fun hash(x: Int, y: Int, s: Int): Double {
            var h = x * 374761393 + y * 668265263 + s * 1442695040
            h = (h xor (h shr 13)) * 1274126177
            return ((h xor (h shr 16)) and 0xFFFFFF) / 0xFFFFFF.toDouble()
        }
        fun smooth(x: Double, y: Double, s: Int): Double {
            val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
            val fx = x - x0; val fy = y - y0
            val u = fx * fx * (3 - 2 * fx); val v = fy * fy * (3 - 2 * fy)
            return hash(x0, y0, s) * (1 - u) * (1 - v) + hash(x0 + 1, y0, s) * u * (1 - v) +
                hash(x0, y0 + 1, s) * (1 - u) * v + hash(x0 + 1, y0 + 1, s) * u * v
        }
        val data = ShortArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            var value = base; var freq = 4.0 / size; var a = amp
            repeat(octaves) { o -> value += a * (smooth(x * freq, y * freq, seed + o) - 0.5); freq *= 2; a *= 0.5 }
            data[y * size + x] = value.toInt().coerceIn(-32000, 32000).toShort()
        }
        return data
    }

    @Test
    fun roundTripsTerrainLosslessly() {
        val decoder = ElevationDecoder()
        val datasets = mapOf(
            "smooth-hills" to terrain(1, 3, 800.0, 500.0),
            "rough-mountain" to terrain(2, 7, 2500.0, 1500.0),
            "flat-coastal" to terrain(3, 2, 60.0, 5.0),
        )
        for ((name, data) in datasets) {
            val bytes = decoder.encodePng(ShortBuffer.wrap(data.copyOf()), size, size)
            val decoded = decoder.decodePng(bytes) as ShortBuffer
            for (i in data.indices) assertEquals(data[i], decoded[i], "round-trip mismatch at $i ($name)")
        }
    }

    @Test
    fun roundTripsNoDataSentinelAndExtremes() {
        val decoder = ElevationDecoder()
        val data = ShortArray(size * size) { i ->
            when (i % 5) {
                0 -> Short.MIN_VALUE // NO_DATA sentinel
                1 -> Short.MAX_VALUE
                2 -> 0
                3 -> -11000
                else -> 8848
            }
        }
        val bytes = decoder.encodePng(ShortBuffer.wrap(data.copyOf()), size, size)
        val decoded = decoder.decodePng(bytes) as ShortBuffer
        for (i in data.indices) assertEquals(data[i], decoded[i], "round-trip mismatch at $i")
    }
}
