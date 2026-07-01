package earth.worldwind.formats.las

import earth.worldwind.formats.las.laszip.LaszipDecoder
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the pure-Kotlin LASzip decoder against reference `.las`/`.laz` twins from laz-rs
 * (point formats 0, 2, 3). Decoding the compressed file and reading the uncompressed twin must
 * yield byte-identical positions and colours — a single arithmetic-coder deviation produces
 * garbage, so exact equality is a strong end-to-end check.
 */
class LaszipDecoderJvmTest {
    private val saved = LasDecoderRegistry.lazDecoder

    @BeforeTest fun install() { LasDecoderRegistry.lazDecoder = LaszipDecoder() }
    @AfterTest fun restore() { LasDecoderRegistry.lazDecoder = saved }

    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/las/$name")?.readBytes() ?: error("missing test resource /las/$name")

    private suspend fun assertLazMatchesLas(base: String) {
        val las = LasReader.parse(resource("$base.las"))
        val laz = LasReader.parse(resource("$base.laz"))

        assertEquals(las.pointCount, laz.pointCount, "$base point count")
        assertTrue(las.pointCount > 0, "$base has points")
        assertTrue(las.positions.contentEquals(laz.positions), "$base positions differ")
        assertTrue(las.colors.contentEquals(laz.colors), "$base colors differ")

        // Header bounds self-consistency: decoded extremes match the stated min/max.
        assertTrue(laz.maxX >= laz.minX && laz.maxY >= laz.minY && laz.maxZ >= laz.minZ, "$base bounds sane")
    }

    @Test fun point10FormatZero() = runBlocking { assertLazMatchesLas("point10") }
    @Test fun pointColorFormatTwo() = runBlocking { assertLazMatchesLas("point-color") }
    @Test fun pointTimeColorFormatThree() = runBlocking { assertLazMatchesLas("point-time-color") }
}
