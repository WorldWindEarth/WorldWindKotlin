package earth.worldwind.geom

import earth.worldwind.geom.Angle.Companion.POS180
import earth.worldwind.geom.Angle.Companion.POS90
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.toDegrees
import earth.worldwind.geom.Angle.Companion.toRadians
import earth.worldwind.geom.Location.Companion.fromDegrees
import earth.worldwind.geom.Location.Companion.fromRadians
import earth.worldwind.shape.PathType
import kotlin.math.sqrt
import kotlin.math.ulp
import kotlin.test.*

/**
 * Unit tests for the Location class.
 */
class LocationTest {
    companion object {
        const val LAT = 34.2 // arbitrary latitude
        const val LON = -119.2 // arbitrary longitude
        const val TOLERANCE = 1e-10
    }

    /**
     * Tests default constructor's member initialization.
     */
    @Test
    fun testConstructor_Default() {
        val location = Location()
        assertNotNull(location)
        assertEquals(0.0, location.latitude.degrees, 0.0, "latitude")
        assertEquals(0.0, location.longitude.degrees, 0.0, "longitude")
    }

    /**
     * Tests constructor from degrees member  initialization.
     */
    @Test
    fun testConstructor_Degrees() {
        val location = fromDegrees(LAT, LON)
        assertNotNull(location)
        assertEquals(LAT, location.latitude.degrees, 0.0, "latitude")
        assertEquals(LON, location.longitude.degrees, 0.0, "longitude")
    }

    /**
     * Tests the copy constructor.
     */
    @Test
    fun testConstructor_Copy() {
        val oxr = fromDegrees(LAT, LON)
        val copy = Location(oxr)
        assertNotNull(oxr)
        assertEquals(LAT, copy.latitude.degrees, 0.0, "latitude")
        assertEquals(LON, copy.longitude.degrees, 0.0, "longitude")
    }

    /**
     * Tests factory method's member initialization from degrees.
     */
    @Test
    fun testFromDegrees() {
        val location = fromDegrees(LAT, LON)
        assertEquals(LAT, location.latitude.degrees, Double.MIN_VALUE, "latitude")
        assertEquals(LON, location.longitude.degrees, Double.MIN_VALUE, "longitude")
    }

    /**
     * Test factory method's member initialization from radians
     */
    @Test
    fun testFromRadians() {
        val location = fromRadians(toRadians(LAT), toRadians(LON))
        assertEquals(LAT, location.latitude.degrees, location.latitude.degrees.ulp, "latitude")
        assertEquals(LON, location.longitude.degrees, location.longitude.degrees.ulp, "longitude")
    }

    /**
     * Tests equality.
     */
    @Test
    fun testEquals() {
        val a = fromDegrees(LAT, LON)
        val b = fromDegrees(LAT, LON)

        // Assert that each member is checked for equality
        assertEquals(b.latitude.degrees, a.latitude.degrees, 0.0, "equality: latitude")
        assertEquals(b.longitude.degrees, a.longitude.degrees, 0.0, "equality: longitude")
        assertEquals(a, a, "equality") // equality with self
        assertEquals(a, b, "equality")
    }

    /**
     * Tests inequality.
     */
    @Test
    fun testEquals_Inequality() {
        val a = fromDegrees(LAT, LON)
        val b = fromDegrees(LAT, LAT)
        val c = fromDegrees(LON, LON)
        assertNotEquals(a, b, "inequality")
        assertNotEquals(a, c, "inequality")
        assertNotNull(a, "inequality")
    }

    /**
     * Ensures hash codes are unique.
     */
    @Test
    fun testHashCode() {
        val lax = fromRadians(0.592539, -2.066470)
        val jfk = fromRadians(0.709185, -1.287762)
        val laxHash = lax.hashCode()
        val jfkHash = jfk.hashCode()
        assertNotEquals(jfkHash, laxHash, "jfk hash vs lax hash")
    }

    /**
     * Ensures string output contains member representations.
     */
    @Test
    fun testToString() {
        val oxr = fromDegrees(LAT, LON)
        val string = oxr.toString()
        assertTrue(string.contains(LAT.toString()), "lat")
        assertTrue(string.contains(LON.toString()), "lon")
    }

    @Test
    fun testLocationsCrossAntimeridian() {
        val locations = mutableListOf<Location>()
        locations.add(fromDegrees(0.0, 165.0))
        locations.add(fromDegrees(0.0, -165.0))
        val isCrossed = Location.locationsCrossAntimeridian(locations)
        assertTrue(isCrossed, "expected to cross")
    }

    @Test
    fun testLocationsCrossAntimeridian_Antipodal() {
        val locations = mutableListOf<Location>()
        locations.add(fromDegrees(0.0, -90.0))
        locations.add(fromDegrees(0.0, 90.0))
        val isCrossed = Location.locationsCrossAntimeridian(locations)
        assertFalse(isCrossed, "antipodal")
    }

    @Test
    fun testLocationsCrossAntimeridian_AlmostAntipodal_DoesCross() {
        val locations = mutableListOf<Location>()
        locations.add(fromDegrees(0.0, -90.0000001))
        locations.add(fromDegrees(0.0, 90.0))
        val isCrossed = Location.locationsCrossAntimeridian(locations)
        assertTrue(isCrossed, "nearly antipodal")
    }

    @Test
    fun testLocationsCrossAntimeridian_AlmostAntipodal_DoesNotCross() {
        val locations = mutableListOf<Location>()
        locations.add(fromDegrees(0.0, -89.9999999))
        locations.add(fromDegrees(0.0, 90.0))
        val isCrossed = Location.locationsCrossAntimeridian(locations)
        assertFalse(isCrossed, "nearly antipodal")
    }

    @Test
    fun testLocationsCrossAntimeridian_OnAntimeridian_SameSideWest() {
        val locations = mutableListOf<Location>()
        locations.add(fromDegrees(90.0, -180.0))
        locations.add(fromDegrees(-90.0, -180.0))
        val isCrossed = Location.locationsCrossAntimeridian(locations)
        assertFalse(isCrossed, "coincident with antimeridian, west side")
    }

    @Test
    fun testLocationsCrossAntimeridian_OnAntimeridian_SameSideEast() {
        val locations = mutableListOf<Location>()
        locations.add(fromDegrees(90.0, 180.0))
        locations.add(fromDegrees(-90.0, 180.0))
        val isCrossed = Location.locationsCrossAntimeridian(locations)
        assertFalse(isCrossed, "coincident with antimeridian, east side")
    }

    @Test
    fun testLocationsCrossAntimeridian_OnAntimeridian_OppositeSides() {
        val locations = mutableListOf<Location>()
        locations.add(fromDegrees(0.0, -180.0))
        locations.add(fromDegrees(0.0, 180.0))
        val isCrossed = Location.locationsCrossAntimeridian(locations)
        assertFalse(isCrossed, "coincident with antimeridian, opposite sides")
    }

    @Test
    fun testLocationsCrossAntimeridian_OutsideNormalRange() {
        val locations = mutableListOf<Location>()
        locations.add(fromDegrees(0.0, -181.0))
        locations.add(fromDegrees(0.0, 181.0))
        val isCrossed = Location.locationsCrossAntimeridian(locations)
        assertTrue(isCrossed, "181(-179) to -181(179) expected to cross")
    }

    @Test
    fun testLocationsCrossAntimeridian_OutsideNormalRangeWest() {
        val locations = mutableListOf<Location>()
        locations.add(fromDegrees(0.0, -179.0))
        locations.add(fromDegrees(0.0, -181.0))
        val isCrossed = Location.locationsCrossAntimeridian(locations)
        assertTrue(isCrossed, "-179 to -181(179) expected to cross")
    }

    @Test
    fun testLocationsCrossAntimeridian_OutsideNormalRangeEast() {
        val locations = mutableListOf<Location>()
        locations.add(fromDegrees(0.0, 179.0))
        locations.add(fromDegrees(0.0, 181.0))
        val isCrossed = Location.locationsCrossAntimeridian(locations)
        assertTrue(isCrossed, "179 to 181(-179) expected to cross")
    }

    @Test
    fun testLocationsCrossAntimeridian_OneLocation() {
        val locations = mutableListOf<Location>()
        locations.add(fromDegrees(0.0, 165.0))
        val isCrossed = Location.locationsCrossAntimeridian(locations)
        assertFalse(isCrossed, "list of one location is not expected to cross")
    }

    @Test
    fun testLocationsCrossAntimeridian_NoLocations() {
        val locations = mutableListOf<Location>()
        val isCrossed = Location.locationsCrossAntimeridian(locations)
        assertFalse(isCrossed, "empty list of locations is not expected to cross")
    }

    /**
     * Ensures empty list argument is handled correctly.
     */
    @Test
    fun testLocationsCrossAntimeridian_EmptyList() {
        val isCrossed = Location.locationsCrossAntimeridian(ArrayList())
        assertFalse(isCrossed, "empty list")
    }

    /**
     * Tests that we read back the same doubles we set.
     */
    @Test
    fun testSet_WithDoubles() {
        val location = Location()
        location.set(LAT.degrees, LON.degrees)
        assertEquals(LAT, location.latitude.degrees, 0.0, "latitude")
        assertEquals(LON, location.longitude.degrees, 0.0, "longitude")
    }

    /**
     * Test that we read back the same Location data that we set.
     */
    @Test
    fun testSet() {
        val oxr = fromDegrees(LAT, LON)
        val location = Location()
        location.copy(oxr)
        assertEquals(oxr.latitude.degrees, location.latitude.degrees, 0.0, "latitude")
        assertEquals(oxr.longitude.degrees, location.longitude.degrees, 0.0, "longitude")
    }

    /**
     * Tests the great circle path interpolation. Ensures the interpolated location lies on the great circle path
     * between start and end.
     */
    @Test
    fun testInterpolateAlongPath() {
        val lax = fromRadians(0.592539, -2.066470)
        val jfk = fromRadians(0.709185, -1.287762)
        val distanceToJfk = lax.greatCircleDistance(jfk)
        val azimuthToJfk = lax.greatCircleAzimuth(jfk)
        val amount = 0.25 // percent
        val result = lax.interpolateAlongPath(jfk, PathType.GREAT_CIRCLE, amount, Location())
        val distanceToResult = lax.greatCircleDistance(result)
        val test = lax.greatCircleLocation(azimuthToJfk, distanceToResult, Location())
        assertEquals(distanceToJfk * amount, distanceToResult, TOLERANCE, "interpolated distance")
        assertEquals(test.latitude.degrees, result.latitude.degrees, 0.0, "latitude")
        assertEquals(test.longitude.degrees, result.longitude.degrees, 0.0, "longitude")
    }

    /**
     * Tests the rhumbline path interpolation. Ensures the interpolated location lies on the rhumb line path between
     * start and end.
     */
    @Test
    fun testInterpolateAlongPath_Rhumbline() {
        val lax = fromRadians(0.592539, -2.066470)
        val jfk = fromRadians(0.709185, -1.287762)
        val distanceToJfk = lax.rhumbDistance(jfk)
        val azimuthToJfk = lax.rhumbAzimuth(jfk)
        val amount = 0.25 // percent
        val result = lax.interpolateAlongPath(jfk, PathType.RHUMB_LINE, amount, Location())
        val distanceToResult = lax.rhumbDistance(result)
        val test = lax.rhumbLocation(azimuthToJfk, distanceToResult, Location())
        assertEquals(distanceToJfk * amount, distanceToResult, TOLERANCE, "interpolated distance")
        assertEquals(test.latitude.degrees, result.latitude.degrees, 0.0, "latitude")
        assertEquals(test.longitude.degrees, result.longitude.degrees, 0.0, "longitude")
    }

    /**
     * Tests the linear path interpolation. Ensures the interpolated location lies on the linear path between start and
     * end.
     */
    @Test
    fun testInterpolateAlongPath_Linear() {
        val lax = fromRadians(0.592539, -2.066470)
        val oxr = fromDegrees(34.2, -119.2)
        val distanceToOxr = lax.linearDistance(oxr)
        val azimuthToOxr = lax.linearAzimuth(oxr)
        val amount = 0.25 // percent
        val result = lax.interpolateAlongPath(oxr, PathType.LINEAR, amount, Location())
        val distanceToResult = lax.linearDistance(result)
        val test = lax.linearLocation(azimuthToOxr, distanceToResult, Location())
        assertEquals(distanceToOxr * amount, distanceToResult, TOLERANCE, "interpolated distance")
        // Math.ulp delta was added due to migration to Java 11, which uses IEEE-floats instead of x87 FPU
        assertEquals(test.latitude.degrees, result.latitude.degrees, test.latitude.degrees.ulp, "latitude")
        assertEquals(test.longitude.degrees, result.longitude.degrees, test.longitude.degrees.ulp, "longitude")
    }

    /**
     * Tests the path interpolation using coincident start and end points.
     */
    @Test
    fun testInterpolateAlongPath_Coincident() {
        val start = fromDegrees(34.2, -119.2)
        val end = Location(start)
        val amount = 0.25 // percent
        val result = start.interpolateAlongPath(end, PathType.LINEAR, amount, Location())
        assertEquals(result, end)
    }

    /**
     * Ensures azimuth to north pole is 0.
     */
    @Test
    fun testGreatCircleAzimuth_North() {
        val origin = Location()
        val northPole = fromDegrees(90.0, 0.0)
        val azimuth = origin.greatCircleAzimuth(northPole).normalize360()
        assertEquals(0.0, azimuth.degrees, TOLERANCE, "north to pole")
    }

    /**
     * Ensures azimuth to south pole is 180.
     */
    @Test
    fun testGreatCircleAzimuth_South() {
        val origin = Location()
        val southPole = fromDegrees(-90.0, 0.0)
        val azimuth = origin.greatCircleAzimuth(southPole).normalize360()
        assertEquals(180.0, azimuth.degrees, TOLERANCE, "south to pole")
    }

    /**
     * Ensures eastward azimuth to dateline is 90.
     */
    @Test
    fun testGreatCircleAzimuth_East() {
        val origin = Location()
        val east = fromDegrees(0.0, 180.0)
        val azimuth = origin.greatCircleAzimuth(east).normalize360()
        assertEquals(90.0, azimuth.degrees, TOLERANCE, "east to dateline")
    }

    /**
     * Ensures westward azimuth to dateline is 270.
     */
    @Test
    fun testGreatCircleAzimuth_West() {
        val origin = Location()
        val west = fromDegrees(0.0, -180.0)
        val azimuth = origin.greatCircleAzimuth(west).normalize360()
        assertEquals(270.0, azimuth.degrees, TOLERANCE, "west to dateline")
    }

    /**
     * Ensures correct distance to known location.
     */
    @Test
    fun testGreatCircleDistance() {
        val begin = fromDegrees(90.0, 45.0)
        val end = fromDegrees(36.0, 180.0)
        val distance = toDegrees(begin.greatCircleDistance(end))
        assertEquals(54.0, distance, TOLERANCE, "known spherical distance")
    }

    /**
     * Ensures distance from prime meridian to dateline is +/- 180.
     */
    @Test
    fun testGreatCircleDistance_AlongEquator() {
        // Assert accurate max distances along the equator
        val origin = Location()
        val eastToDateLine = origin.greatCircleDistance(fromDegrees(0.0, 180.0))
        assertEquals(toRadians(180.0), eastToDateLine, TOLERANCE, "prime meridian to dateline east")
        val westToDateLine = origin.greatCircleDistance(fromDegrees(0.0, -180.0))
        assertEquals(toRadians(180.0), westToDateLine, TOLERANCE, "prime meridian to dateline west")
        val west = fromDegrees(0.0, -22.5)
        val sideToSide = west.greatCircleDistance(fromDegrees(0.0, 22.5))
        assertEquals(toRadians(45.0), sideToSide, TOLERANCE, "22.5 east to 22.5 west")
    }

    /**
     * Ensures distance correct distance across prime meridian.
     */
    @Test
    fun testGreatCircleDistance_AcrossMeridian() {
        val west = fromDegrees(0.0, -22.5)
        val east = fromDegrees(0.0, 22.5)
        val sideToSide = west.greatCircleDistance(east)
        assertEquals(toRadians(45.0), sideToSide, TOLERANCE, "22.5 east to 22.5 west")
    }

    /**
     * Ensures distance correct distance across prime meridian.
     */
    @Test
    fun testGreatCircleDistance_AcrossDateline() {
        val west = fromDegrees(0.0, -157.5)
        val east = fromDegrees(0.0, 157.5)
        val sideToSide = west.greatCircleDistance(east)
        assertEquals(toRadians(45.0), sideToSide, TOLERANCE, "157.5 east to 157.5 west")
    }

    /**
     * Ensures distance from equator to poles is +/- 90.
     */
    @Test
    fun testGreatCircleDistance_AlongMeridians() {
        // Assert accurate max distances along lines of longitude
        val origin = Location()

        // Equator to North pole
        val northToPole = origin.greatCircleDistance(fromDegrees(90.0, 0.0))
        assertEquals(toRadians(90.0), northToPole, TOLERANCE, "equator to north pole")

        // Equator to South pole
        val southToPole = origin.greatCircleDistance(fromDegrees(-90.0, 0.0))
        assertEquals(toRadians(90.0), southToPole, TOLERANCE, "equator to south pole")

        // South pole to North Pole
        val southPole = fromDegrees(-90.0, 0.0)
        val northPole = fromDegrees(90.0, 0.0)
        val poleToPole = southPole.greatCircleDistance(northPole)
        assertEquals(toRadians(180.0), poleToPole, TOLERANCE, "south pole to north pole")
        val south = fromDegrees(-22.5, 0.0)
        val north = fromDegrees(22.5, 0.0)
        val southToNorth = south.greatCircleDistance(north)
        assertEquals(toRadians(45.0), southToNorth, TOLERANCE, "22.5 deg south to 22.5 north")
    }

    @Test
    fun testGreatCircleLocation_NorthPole() {
        // Trivial tests along prime meridian
        val origin = Location()
        val result = Location()
        origin.greatCircleLocation(ZERO, toRadians(90.0), result)
        assertEquals(90.0, result.latitude.degrees, Double.MIN_VALUE, "north pole latitude")
    }

    @Test
    fun testGreatCircleLocation_SouthPole() {
        // Trivial tests along prime meridian
        val origin = Location()
        val result = Location()
        origin.greatCircleLocation(POS180, toRadians(90.0), result)
        assertEquals(-90.0, result.latitude.degrees, Double.MIN_VALUE, "south pole latitude")
    }

    /**
     * Ensures the correct azimuth to a known location.
     */
    @Test
    fun testRhumbAzimuth() {
        // From Ed Williams Aviation Formulary:
        //  LAX is 33deg 57min N, 118deg 24min W (0.592539, -2.066470),
        //  JFK is 40deg 38min N,  73deg 47min W (0.709185, -1.287762),
        //  LAX to JFK rhumb line course of 79.3 degrees (1.384464 radians)
        val lax = fromRadians(0.592539, -2.066470)
        val jfk = fromRadians(0.709185, -1.287762)
        val courseRadians = 1.384464
        val azimuth = lax.rhumbAzimuth(jfk)
        assertEquals(courseRadians, azimuth.radians, 1e-6, "lax to jfk")
    }

    /**
     * Ensures the correct azimuth along the equator.
     */
    @Test
    fun testRhumbAzimuth_AlongEquator() {
        val origin = fromDegrees(0.0, 0.0)
        val east = fromDegrees(0.0, 1.0)
        val west = fromDegrees(0.0, -1.0)
        val azimuthEast = origin.rhumbAzimuth(east)
        val azimuthWest = origin.rhumbAzimuth(west)
        assertEquals(90.0, azimuthEast.degrees, 0.0, "expecting 90")
        assertEquals(-90.0, azimuthWest.degrees, 0.0, "expecting -90")
    }

    /**
     * Ensures the correct azimuth along a meridian.
     */
    @Test
    fun testRhumbAzimuth_AlongMeridian() {
        val begin = fromDegrees(0.0, 0.0)
        val north = fromDegrees(1.0, 0.0)
        val south = fromDegrees(-1.0, 0.0)
        val azimuthNorth = begin.rhumbAzimuth(north)
        val azimuthSouth = begin.rhumbAzimuth(south)
        assertEquals(0.0, azimuthNorth.degrees, 0.0, "expecting 0")
        assertEquals(180.0, azimuthSouth.degrees, 0.0, "expecting 180")
    }

    /**
     * Ensures the correct azimuth from poles.
     */
    @Test
    fun testRhumbAzimuth_FromPoles() {
        val northPole = fromDegrees(90.0, 0.0)
        val southPole = fromDegrees(-90.0, 0.0)
        val end = fromDegrees(0.0, 0.0)
        val azimuthNorth = southPole.rhumbAzimuth(end)
        val azimuthSouth = northPole.rhumbAzimuth(end)
        assertEquals(0.0, azimuthNorth.degrees, 0.0, "expecting 0")
        assertEquals(180.0, azimuthSouth.degrees, 0.0, "expecting 180")
    }

    /**
     * Ensures the correct azimuth (shortest distance) across the +/-180 meridian.
     */
    @Test
    fun testRhumbAzimuth_AcrossDateline() {
        val end = fromDegrees(45.0, 165.0)
        val begin = fromDegrees(45.0, -165.0)
        val azimuth = begin.rhumbAzimuth(end)

        // Expecting an east course from +165 to -165
        assertEquals(-90.0, azimuth.degrees, 0.0, "expecting -90")
    }

    /**
     * Ensures a zero azimuth for coincident locations.
     */
    @Test
    fun testRhumbAzimuth_CoincidentLocations() {
        val begin = fromDegrees(LAT, LON)
        val end = fromDegrees(LAT, LON)
        val azimuth = begin.rhumbAzimuth(end)
        assertEquals(0.0, azimuth.degrees, 0.0, "expecting zero")
    }

    /**
     * Ensures correct distance between known locations.
     */
    @Test
    fun testRhumbDistance() {
        // From Ed Williams Aviation Formulary:
        //  LAX is 33deg 57min N, 118deg 24min W (0.592539, -2.066470),
        //  JFK is 40deg 38min N,  73deg 47min W (0.709185, -1.287762),
        //  LAX to JFK rhumbline course of 79.3 degrees (1.384464 radians)
        //  LAX to JFK rhumbline distance of 2164.6nm (0.629650 radians)
        val lax = fromRadians(0.592539, -2.066470)
        val jfk = fromRadians(0.709185, -1.287762)
        val distanceFromLaxToJfk = 0.629650
        val distance = lax.rhumbDistance(jfk) // radians
        assertEquals(distanceFromLaxToJfk, distance, 1e-6, "lax to jfk")
    }

    /**
     * Ensures correct distance across the +/- 180 meridian.
     */
    @Test
    fun testRhumbDistance_AcrossDateline() {
        val end = fromDegrees(0.0, 165.0)
        val begin = fromDegrees(0.0, -165.0)
        val distance = begin.rhumbDistance(end)
        assertEquals(30.0, toDegrees(distance), TOLERANCE, "expecting 30 degrees")
    }

    /**
     * Ensures a zero distance for coincident locations.
     */
    @Test
    fun testRhumbDistance_CoincidentLocations() {
        val begin = fromDegrees(LAT, LON)
        val end = fromDegrees(LAT, LON)
        val distance = begin.rhumbDistance(end)
        assertEquals(0.0, distance, 0.0, "expecting zero")
    }

    /**
     * Ensures the correct location using a known azimuth and distance.
     */
    @Test
    fun testRhumbLocation() {
        // From Ed Williams Aviation Formulary:
        //  LAX is 33deg 57min N, 118deg 24min W (0.592539, -2.066470),
        //  JFK is 40deg 38min N,  73deg 47min W (0.709185, -1.287762),
        //  LAX to JFK rhumbline course of 79.3 degrees (1.384464 radians)
        //  LAX to JFK rhumbline distance of 2164.6nm (0.629650 radians)
        val lax = fromRadians(0.592539, -2.066470)
        val jfk = fromRadians(0.709185, -1.287762)
        val distanceFromLaxToJfk = 0.6296498957149533
        val course = 79.32398087460811.degrees
        val location = Location()
        lax.rhumbLocation(course, distanceFromLaxToJfk, location)
        assertEquals(jfk.latitude.degrees, location.latitude.degrees, TOLERANCE, "jfk latitude")
        assertEquals(jfk.longitude.degrees, location.longitude.degrees, TOLERANCE, "jfk longitude")
    }

    /**
     * Tests the linear azimuth (flat-earth approximate) using a well known right triangle.
     */
    @Test
    fun testLinearAzimuth() {
        // Create a 30-60-90 right triangle with a ratio of 1:2:sqrt(3)
        val begin = fromDegrees(0.0, 0.0)
        val end = fromDegrees(1.0, sqrt(3.0))
        val azimuth = begin.linearAzimuth(end)
        assertEquals(60.0, azimuth.degrees, TOLERANCE, "linear azimuth")
    }

    /**
     * Tests the linear azimuth across the +/- 180 meridian.
     */
    @Test
    fun testLinearAzimuth_AcrossDateline() {
        // Create a 30-60-90 right triangle with a ratio of 1:2:sqrt(3)
        val begin = fromDegrees(0.0, 179.5)
        val end = fromDegrees(sqrt(3.0), -179.5)
        val azimuth = begin.linearAzimuth(end)
        assertEquals(30.0, azimuth.degrees, TOLERANCE, "linear azimuth")
    }

    @Test
    fun testLinearDistance() {
        // Create a 30-60-90 right triangle with a ratio of 1:2:sqrt(3)
        val begin = fromDegrees(0.0, 0.0)
        val end = fromDegrees(1.0, sqrt(3.0))
        val distance = begin.linearDistance(end)
        assertEquals(2.0, toDegrees(distance), TOLERANCE, "linear distance")
    }

    @Test
    fun testLinearLocation() {
        // Create a 30-60-90 right triangle with a ratio of 1:2:sqrt(3)
        val begin = fromDegrees(LAT, LON)
        val height = begin.latitude.degrees + 1.0
        val base = begin.longitude.degrees + sqrt(3.0)
        val distance = toRadians(2.0)
        val azimuth = 60.0.degrees
        val end = Location()
        begin.linearLocation(azimuth, distance, end)
        assertEquals(base, end.longitude.degrees, TOLERANCE, "longitude")
        assertEquals(height, end.latitude.degrees, TOLERANCE, "latitude")
    }

    // ---------------------------------------------------
    // The following tests were copied from WorldWind Java
    // ---------------------------------------------------
    //////////////////////////////////////////////////////////
    // Test equivalent points. Distance should always be 0.
    //////////////////////////////////////////////////////////
    @Test
    fun testGreatCircleDistance_TrivialEquivalentPointsA() {
        val begin = fromDegrees(0.0, 0.0)
        val end = fromDegrees(0.0, 0.0)
        val distance = toDegrees(begin.greatCircleDistance(end))
        assertEquals(0.0, distance, TOLERANCE, "Trivial equivalent points A")
    }

    @Test
    fun testGreatCircleDistance_TrivialEquivalentPointsB() {
        val begin = fromDegrees(0.0, -180.0)
        val end = fromDegrees(0.0, 180.0)
        val distance = toDegrees(begin.greatCircleDistance(end))
        assertEquals(0.0, distance, TOLERANCE, "Trivial equivalent points B")
    }

    @Test
    fun testGreatCircleDistance_TrivialEquivalentPointsC() {
        val begin = fromDegrees(0.0, 0.0)
        val end = fromDegrees(0.0, 360.0)
        val distance = toDegrees(begin.greatCircleDistance(end))
        assertEquals(0.0, distance, TOLERANCE, "Trivial equivalent points C")
    }

    @Test
    fun testGreatCircleDistance_EquivalentPoints() {
        val begin = fromDegrees(53.0902505, 112.8935442)
        val end = fromDegrees(53.0902505, 112.8935442)
        val distance = toDegrees(begin.greatCircleDistance(end))
        assertEquals(0.0, distance, TOLERANCE, "Equivalent points")
    }

    //////////////////////////////////////////////////////////
    // Test antipodal points. Distance should always be 180.
    //////////////////////////////////////////////////////////
    @Test
    fun testGreatCircleDistance_TrivialAntipodalPointsA() {
        val begin = fromDegrees(0.0, 0.0)
        val end = fromDegrees(0.0, 180.0)
        val distance = toDegrees(begin.greatCircleDistance(end))
        assertEquals(180.0, distance, TOLERANCE, "Trivial antipodal points A")
    }

    @Test
    fun testGreatCircleDistance_TrivialAntipodalPointsB() {
        val begin = fromDegrees(-90.0, 0.0)
        val end = fromDegrees(90.0, 0.0)
        val distance = toDegrees(begin.greatCircleDistance(end))
        assertEquals(180.0, distance, TOLERANCE, "Trivial antipodal points B")
    }

    @Test
    fun testGreatCircleDistance_TrivialAntipodalPointsC() {
        val begin = fromDegrees(-90.0, -180.0)
        val end = fromDegrees(90.0, 180.0)
        val distance = toDegrees(begin.greatCircleDistance(end))
        assertEquals(180.0, distance, TOLERANCE, "Trivial antipodal points C")
    }

    @Test
    fun testGreatCircleDistance_AntipodalPointsA() {
        val begin = fromDegrees(53.0902505, 112.8935442)
        val end = fromDegrees(-53.0902505, -67.1064558)
        val distance = toDegrees(begin.greatCircleDistance(end))
        assertEquals(180.0, distance, TOLERANCE, "Antipodal points A")
    }

    @Test
    fun testGreatCircleDistance_AntipodalPointsB() {
        val begin = fromDegrees(-12.0, 87.0)
        val end = fromDegrees(12.0, -93.0)
        val distance = toDegrees(begin.greatCircleDistance(end))
        assertEquals(180.0, distance, TOLERANCE, "Antipodal points B")
    }

    //////////////////////////////////////////////////////////
    // Test points known to be a certain angular distance apart.
    //////////////////////////////////////////////////////////
    @Test
    fun testGreatCircleDistance_KnownDistance() {
        val begin = fromDegrees(90.0, 45.0)
        val end = fromDegrees(36.0, 180.0)
        val distance = toDegrees(begin.greatCircleDistance(end))
        assertEquals(54.0, distance, TOLERANCE, "Known spherical distance")
    }

    @Test
    fun testGreatCircleDistance_KnownDistanceCloseToZero() {
        val begin = fromDegrees(-12.0, 87.0)
        val end = fromDegrees(-12.0000001, 86.9999999)
        val distance = toDegrees(begin.greatCircleDistance(end))
        assertEquals(1.3988468832247915e-7, distance, TOLERANCE, "Known spherical distance (close to zero)")
    }

    @Test
    fun testGreatCircleDistance_KnownDistanceCloseTo180() {
        val begin = fromDegrees(-12.0, 87.0)
        val end = fromDegrees(11.9999999, -93.0000001)
        val distance = toDegrees(begin.greatCircleDistance(end))
        assertEquals(180.0, distance, TOLERANCE, "Known spherical distance (close to 180)")
    }

    //////////////////////////////////////////////////////////
    // Test points that have caused problems.
    //////////////////////////////////////////////////////////
    @Test
    fun testGreatCircleDistance_ProblemPointsA() {
        val begin = fromDegrees(36.0, -118.0)
        val end = fromDegrees(36.0, -117.0)
        val distance = toDegrees(begin.greatCircleDistance(end))
        assertEquals(0.8090134466773318, distance, TOLERANCE, "Problem points A")
    }

    @Test
    fun testRhumbLocation_ProblemPointsA() {
        // Compute location along/near equator
        val azimuth = POS90
        val distance = 0.08472006153859046
        val begin = fromDegrees(2.892251645338908, -100.43740218868658)
        val end = begin.rhumbLocation(azimuth, distance, Location())

        // delta longitude
        val result = end.longitude.degrees - begin.longitude.degrees
        val expected = 4.86029305637848
        assertEquals(expected, result, 1e-15, "Delta Longitude")
    }

    @Test
    fun testRhumbDistance_ProblemPointsA() {
        // Compute location along/near equator
        val begin = fromDegrees(2.892251645338908, -100.43740218868658)
        val end = fromDegrees(2.892251645338908 + 1e-15, -95.57710913230811)
        val result = begin.rhumbDistance(end)
        val expected = 0.08472006153859046
        assertEquals(expected, result, 1e-15, "Rhumb distance")
    }
}