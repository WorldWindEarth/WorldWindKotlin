package earth.worldwind.geom

import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.toRadians
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.geom.Position.Companion.fromRadians
import earth.worldwind.shape.PathType
import kotlin.test.*

/**
 * Unit tests for the Location class.
 */
class PositionTest {
    companion object {
        const val LAT = 34.2 // arbitrary latitude
        const val LON = -119.2 // arbitrary longitude
        const val ELEV = 13.7 // arbitrary altitude
        const val TOLERANCE = 1e-10
    }

    /**
     * Tests default constructor's member initialization.
     */
    @Test
    fun testConstructor_Default() {
        val position = Position()
        assertNotNull(position)
        assertEquals(0.0, position.latitude.degrees, 0.0, "latitude")
        assertEquals(0.0, position.longitude.degrees, 0.0, "longitude")
        assertEquals(0.0, position.altitude, 0.0, "altitude")
    }

    /**
     * Tests constructor from degrees member  initialization.
     */
    @Test
    fun testConstructor_Degrees() {
        val oxr = fromDegrees(LAT, LON, ELEV)
        assertNotNull(oxr)
        assertEquals(LAT, oxr.latitude.degrees, 0.0, "latitude")
        assertEquals(LON, oxr.longitude.degrees, 0.0, "longitude")
        assertEquals(ELEV, oxr.altitude, 0.0, "altitude")
    }

    /**
     * Tests the copy constructor.
     */
    @Test
    fun testConstructor_Copy() {
        val oxr = fromDegrees(LAT, LON, ELEV)
        val copy = Position(oxr)
        assertNotNull(oxr)
        assertEquals(LAT, copy.latitude.degrees, 0.0, "latitude")
        assertEquals(LON, copy.longitude.degrees, 0.0, "longitude")
        assertEquals(ELEV, copy.altitude, 0.0, "altitude")
    }


    /**
     * Tests the factory method using decimal degrees and meters.
     */
    @Test
    fun testFromDegrees() {
        val oxr = fromDegrees(LAT, LON, ELEV)
        assertNotNull(oxr)
        assertEquals(LAT, oxr.latitude.degrees, 0.0, "latitude")
        assertEquals(LON, oxr.longitude.degrees, 0.0, "longitude")
        assertEquals(ELEV, oxr.altitude, 0.0, "altitude")
    }

    /**
     * Tests the factory method using radians and meters.
     */
    @Test
    fun testFromRadians() {
        val oxr = fromRadians(toRadians(LAT), toRadians(LON), ELEV)
        assertNotNull(oxr)
        assertEquals(LAT, oxr.latitude.degrees, TOLERANCE, "latitude")
        assertEquals(LON, oxr.longitude.degrees, TOLERANCE, "longitude")
        assertEquals(ELEV, oxr.altitude, 0.0, "altitude")
    }

    /**
     * Tests equality.
     */
    @Test
    fun testEquals() {
        val a = fromDegrees(LAT, LON, ELEV)
        val b = fromDegrees(LAT, LON, ELEV)

        // Assert that each member is checked for equality
        assertEquals(b.latitude.degrees, a.latitude.degrees, 0.0, "equality: latitude")
        assertEquals(b.longitude.degrees, a.longitude.degrees, 0.0, "equality: longitude")
        assertEquals(b.altitude, a.altitude, 0.0, "equality: altitude")
        assertEquals(a, b, "equality")
    }

    /**
     * Tests inequality.
     */
    @Test
    fun testEquals_Inequality() {
        val oxr = fromDegrees(LAT, LON, ELEV)
        val a = fromDegrees(LAT, LAT, ELEV)
        val b = fromDegrees(LON, LON, ELEV)
        val c = fromDegrees(LAT, LON, 0.0)
        assertNotEquals(oxr, a, "inequality")
        assertNotEquals(oxr, b, "inequality")
        assertNotEquals(oxr, c, "inequality")
        assertNotNull(oxr, "inequality")
    }

    /**
     * Ensures hash codes are unique.
     */
    @Test
    fun testHashCode() {
        val oxr = fromDegrees(34.2, -119.2, 13.7)
        val lax = fromDegrees(33.94, -118.4, 38.7)
        val oxrHash = oxr.hashCode()
        val laxHash = lax.hashCode()
        assertNotEquals(oxrHash, laxHash, "oxr hash vs lax hash")
    }

    /**
     * Ensures string output contains member representations.
     */
    @Test
    fun testToString() {
        val oxr = fromDegrees(LAT, LON, ELEV)
        val string = oxr.toString()
        assertTrue(string.contains(LAT.toString()), "lat")
        assertTrue(string.contains(LON.toString()), "lon")
        assertTrue(string.contains(ELEV.toString()), "alt")
    }

    /**
     * Test that we read back the same Location data that we set.
     */
    @Test
    fun testSet() {
        val oxr = fromDegrees(LAT, LON, ELEV)
        val other = Position()
        other.copy(oxr)
        assertEquals(oxr.latitude.degrees, other.latitude.degrees, 0.0, "latitude")
        assertEquals(oxr.longitude.degrees, other.longitude.degrees, 0.0, "longitude")
        assertEquals(oxr.altitude, other.altitude, 0.0, "altitude")
    }

    /**
     * Tests that we read back the same doubles we set.
     */
    @Test
    fun testSet_WithDoubles() {
        val pos = Position()
        pos.set(LAT.degrees, LON.degrees, ELEV)
        assertEquals(LAT, pos.latitude.degrees, 0.0, "latitude")
        assertEquals(LON, pos.longitude.degrees, 0.0, "longitude")
        assertEquals(ELEV, pos.altitude, 0.0, "altitude")
    }

    /**
     * Tests the great circle path interpolation. Ensures the interpolated position lies on the great circle path
     * between start and end.
     */
    @Test
    fun testInterpolateAlongPath() {
        val lax = fromDegrees(33.94, -118.4, 38.7)
        val oxr = fromDegrees(34.2, -119.2, 13.7)
        val distanceToOxr = lax.greatCircleDistance(oxr)
        val azimuthToOxr = lax.greatCircleAzimuth(oxr)
        val amount = 0.25 // percent
        val result = lax.interpolateAlongPath(oxr, PathType.GREAT_CIRCLE, amount, Position())
        val distanceToResult = lax.greatCircleDistance(result)
        val test = lax.greatCircleLocation(azimuthToOxr, distanceToResult, Location())
        assertEquals(distanceToOxr * amount, distanceToResult, TOLERANCE, "interpolated distance")
        assertEquals(test.latitude.degrees, result.latitude.degrees, 0.0, "latitude")
        assertEquals(test.longitude.degrees, result.longitude.degrees, 0.0, "longitude")
    }

    /**
     * Tests the rhumbline path interpolation. Ensures the interpolated position lies on the rhumb line path between
     * start and end.
     */
    @Test
    fun testInterpolateAlongPath_Rhumbline() {
        val lax = fromDegrees(33.94, -118.4, 38.7)
        val oxr = fromDegrees(34.2, -119.2, 13.7)
        val distanceToOxr = lax.rhumbDistance(oxr)
        val azimuthToOxr = lax.rhumbAzimuth(oxr)
        val amount = 0.25 // percent
        val result = lax.interpolateAlongPath(oxr, PathType.RHUMB_LINE, amount, Position())
        val distanceToResult = lax.rhumbDistance(result)
        val test = lax.rhumbLocation(azimuthToOxr, distanceToResult, Location())
        assertEquals(distanceToOxr * amount, distanceToResult, TOLERANCE, "interpolated distance")
        assertEquals(test.latitude.degrees, result.latitude.degrees, TOLERANCE, "latitude")
        assertEquals(test.longitude.degrees, result.longitude.degrees, TOLERANCE, "longitude")
    }

    /**
     * Tests the linear path interpolation. Ensures the interpolated position lies on the linear path between start and
     * end.
     */
    @Test
    fun testInterpolateAlongPath_Linear() {
        val lax = fromDegrees(33.94, -118.4, 38.7)
        val oxr = fromDegrees(34.2, -119.2, 13.7)
        val distanceToOxr = lax.linearDistance(oxr)
        val azimuthToOxr = lax.linearAzimuth(oxr)
        val amount = 0.25 // percent
        val result = lax.interpolateAlongPath(oxr, PathType.LINEAR, amount, Position())
        val distanceToResult = lax.linearDistance(result)
        val test = lax.linearLocation(azimuthToOxr, distanceToResult, Location())
        assertEquals(distanceToOxr * amount, distanceToResult, TOLERANCE, "interpolated distance")
        assertEquals(test.latitude.degrees, result.latitude.degrees, TOLERANCE, "latitude")
        assertEquals(test.longitude.degrees, result.longitude.degrees, TOLERANCE, "longitude")
    }
}