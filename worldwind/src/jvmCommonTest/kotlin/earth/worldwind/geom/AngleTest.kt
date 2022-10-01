package earth.worldwind.geom

import earth.worldwind.geom.Angle.Companion.clampLatitude
import earth.worldwind.geom.Angle.Companion.clampLongitude
import earth.worldwind.geom.Angle.Companion.normalizeAngle360
import earth.worldwind.geom.Angle.Companion.normalizeLatitude
import earth.worldwind.geom.Angle.Companion.normalizeLongitude
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AngleTest {
    companion object {
        const val THETA = 34.2 // arbitrary latitude
        const val PHI = -119.2 // arbitrary longitude
    }

    @Test
    fun testNormalizeDegrees() {
        // Starting at the prime meridian, travel eastward around the globe
        assertEquals(0.0, normalizeAngle360(0.0), 0.0, "zero")
        assertEquals(180.0, normalizeAngle360(180.0), 0.0, "180")
        assertEquals(181.0, normalizeAngle360(181.0), 0.0, "181")
        assertEquals(359.0, normalizeAngle360(359.0), 0.0, "359")
        assertEquals(0.0, normalizeAngle360(360.0), 0.0, "360")
        assertEquals(1.0, normalizeAngle360(361.0), 0.0, "361")
        assertEquals(0.0, normalizeAngle360(720.0), 0.0, "720")
        assertEquals(1.0, normalizeAngle360(721.0), 0.0, "721")
        // Starting at the prime meridian, travel westward around the globe
        assertEquals(359.0, normalizeAngle360(-1.0), 0.0, "-1")
        assertEquals(1.0, normalizeAngle360(-359.0), 0.0, "-359")
        assertEquals(0.0, normalizeAngle360(-360.0), 0.0, "-360")
        assertEquals(359.0, normalizeAngle360(-361.0), 0.0, "-361")
        assertEquals(1.0, normalizeAngle360(-719.0), 0.0, "-719")
        assertEquals(0.0, normalizeAngle360(-720.0), 0.0, "-720")
        assertEquals(359.0, normalizeAngle360(-721.0), 0.0, "-721")
        // NaN should be propagated
        assertTrue(normalizeAngle360(Double.NaN).isNaN(), "NaN")
    }

    /**
     * Ensures normalizeLatitude returns a correct value within the range of +/- 90 degrees. A typical use case is to
     * normalize the result of a latitude used in an arithmetic function.
     */
    @Test
    fun testNormalizeLatitude() {
        // Orbit the globe, starting at the equator and move north
        // testing the sign at the hemisphere boundaries.
        assertEquals(0.0, normalizeLatitude(0.0), 0.0, "zero")
        assertEquals(1.0, normalizeLatitude(1.0), 0.0, "1")
        // Test at the North Pole
        assertEquals(89.0, normalizeLatitude(89.0), 0.0, "89")
        assertEquals(90.0, normalizeLatitude(90.0), 0.0, "90")
        assertEquals(89.0, normalizeLatitude(91.0), 0.0, "91")

        // Test at the equator, continue moving south
        assertEquals(1.0, normalizeLatitude(179.0), 0.0, "179")
        assertEquals(0.0, normalizeLatitude(180.0), 0.0, "180")
        assertEquals(-1.0, normalizeLatitude(181.0), 0.0, "181")

        // Test at the South Pole
        assertEquals(-89.0, normalizeLatitude(269.0), 0.0, "269")
        assertEquals(-90.0, normalizeLatitude(270.0), 0.0, "270")
        assertEquals(-89.0, normalizeLatitude(271.0), 0.0, "271")

        // Test at the prime meridian
        assertEquals(-1.0, normalizeLatitude(359.0), 0.0, "359")
        assertEquals(0.0, normalizeLatitude(360.0), 0.0, "360")
        assertEquals(1.0, normalizeLatitude(361.0), 0.0, "361")
        assertEquals(1.0, normalizeLatitude(721.0), 0.0, "721")

        // Test negative values
        assertEquals(-1.0, normalizeLatitude(-1.0), 0.0, "-1")
        assertEquals(-89.0, normalizeLatitude(-89.0), 0.0, "-89")
        assertEquals(-90.0, normalizeLatitude(-90.0), 0.0, "-90")
        assertEquals(-89.0, normalizeLatitude(-91.0), 0.0, "-91")
        assertEquals(-1.0, normalizeLatitude(-179.0), 0.0, "-179")
        assertEquals(0.0, normalizeLatitude(-180.0), 0.0, "-180")
        assertEquals(1.0, normalizeLatitude(-181.0), 0.0, "-181")
        assertEquals(89.0, normalizeLatitude(-269.0), 0.0, "-269")
        assertEquals(90.0, normalizeLatitude(-270.0), 0.0, "-270")
        assertEquals(89.0, normalizeLatitude(-271.0), 0.0, "-271")
        assertEquals(1.0, normalizeLatitude(-359.0), 0.0, "-359")
        assertEquals(0.0, normalizeLatitude(-360.0), 0.0, "-360")
        assertEquals(-1.0, normalizeLatitude(-361.0), 0.0, "-361")
        assertEquals(1.0, normalizeLatitude(-719.0), 0.0, "-719")
        assertEquals(-1.0, normalizeLatitude(-721.0), 0.0, "-721")

        // NaN is propagated.
        assertTrue(normalizeLatitude(Double.NaN).isNaN(), "NaN")
    }

    /**
     * Ensures normalizeLongitude returns a correct value within the range of +/- 180 degrees. A typical use case is to
     * normalize the result of a longitude used in an arithmetic function.
     */
    @Test
    fun testNormalizeLongitude() {
        // Test "normal" data
        assertEquals(0.0, normalizeLongitude(0.0), 0.0, "zero")
        assertEquals(-90.0, normalizeLongitude(270.0), 0.0, "270")
        assertEquals(90.0, normalizeLongitude(-270.0), 0.0, "-270")

        // Test int'l date line boundaries
        assertEquals(179.0, normalizeLongitude(179.0), 0.0, "179")
        assertEquals(180.0, normalizeLongitude(180.0), 0.0, "180")
        assertEquals(-179.0, normalizeLongitude(181.0), 0.0, "181")

        // Test prime meridian boundaries
        assertEquals(1.0, normalizeLongitude(1.0), 0.0, "1")
        assertEquals(-1.0, normalizeLongitude(-1.0), 0.0, "-1")
        assertEquals(-1.0, normalizeLongitude(359.0), 0.0, "359")
        assertEquals(0.0, normalizeLongitude(360.0), 0.0, "360")
        assertEquals(1.0, normalizeLongitude(361.0), 0.0, "361")
        assertEquals(-1.0, normalizeLongitude(719.0), 0.0, "719")
        assertEquals(0.0, normalizeLongitude(720.0), 0.0, "720")
        assertEquals(1.0, normalizeLongitude(721.0), 0.0, "721")

        // Assert negative -180 retains sign
        assertEquals(-180.0, normalizeLongitude(-180.0), 0.0, "-180")

        // Propagate NaNs
        assertTrue(normalizeLongitude(Double.NaN).isNaN(), "NaN")
    }

    /**
     * Ensures clampLatitude clamps to +/-90 degrees.
     */
    @Test
    fun testClampLatitude() {
        // Test "normal" data
        assertEquals(0.0, clampLatitude(0.0), 0.0, "0")
        assertEquals(THETA, clampLatitude(THETA), 0.0, "THETA")
        assertEquals(-THETA, clampLatitude(-THETA), 0.0, "-THETA")

        // Test boundaries
        assertEquals(90.0, clampLatitude(90.0), 0.0, "90")
        assertEquals(-90.0, clampLatitude(-90.0), 0.0, "-90")

        // Test clamping
        assertEquals(90.0, clampLatitude(91.0), 0.0, "91")
        assertEquals(-90.0, clampLatitude(-91.0), 0.0, "-91")
    }

    /**
     * Ensures clampLatitude clamps to +/-180 degrees.
     */
    @Test
    fun testClampLongitude() {
        // Test "normal" data
        assertEquals(0.0, clampLongitude(0.0), 0.0, "0")
        assertEquals(PHI, clampLongitude(PHI), 0.0, "PHI")
        assertEquals(-PHI, clampLongitude(-PHI), 0.0, "-PHI")

        // Test boundaries
        assertEquals(180.0, clampLongitude(180.0), 0.0, "180")
        assertEquals(-180.0, clampLongitude(-180.0), 0.0, "-180")

        // Test clamping
        assertEquals(180.0, clampLongitude(181.0), 0.0, "181")
        assertEquals(-180.0, clampLongitude(-181.0), 0.0, "-181")
    }
}