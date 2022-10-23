package earth.worldwind.geom

import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.toRadians
import earth.worldwind.geom.Location.Companion.fromDegrees
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.geom.Sector.Companion.fromRadians
import kotlin.test.*

/**
 * Unit tests for the Sector class.
 */
class SectorTest {
    companion object {
        const val LAT = 34.2 // arbitrary latitude
        const val LON = -119.2 // arbitrary longitude
        const val DLAT = 1.0 // arbitrary delta latitude
        const val DLON = 2.0 // arbitrary delta longitude
        const val TOLERANCE = 1e-10
    }

    @Test
    fun testConstructor_Default() {
        val sector = Sector()
        assertNotNull(sector)
// Empty sector now contains zeroes instead of NaN
//        assertTrue(sector.minLatitude.degrees.isNaN(), "NaN minLatitude")
//        assertTrue(sector.minLongitude.degrees.isNaN(), "NaN minLongitude")
//        assertTrue(sector.maxLatitude.degrees.isNaN(), "NaN maxLatitude")
//        assertTrue(sector.maxLongitude.degrees.isNaN(), "NaN maxLongitude")
//        assertTrue(sector.deltaLatitude.degrees.isNaN(), "NaN deltaLatitude")
//        assertTrue(sector.deltaLongitude.degrees.isNaN(), "NaN deltaLongitude")
        assertEquals(0.0, sector.minLatitude.inDegrees, 0.0, "Zero minLatitude")
        assertEquals(0.0, sector.minLongitude.inDegrees, 0.0, "Zero minLongitude")
        assertEquals(0.0, sector.maxLatitude.inDegrees, 0.0, "Zero maxLatitude")
        assertEquals(0.0, sector.maxLongitude.inDegrees, 0.0, "Zero maxLongitude")
        assertEquals(0.0, sector.deltaLatitude.inDegrees, 0.0, "Zero deltaLatitude")
        assertEquals(0.0, sector.deltaLongitude.inDegrees, 0.0, "Zero deltaLongitude")
    }

    @Test
    fun testConstructor_Typical() {
        val sector = fromDegrees(LAT, LON, DLAT, DLON)
        assertNotNull(sector)
        assertEquals(LAT, sector.minLatitude.inDegrees, 0.0, "minLatitude")
        assertEquals(LON, sector.minLongitude.inDegrees, 0.0, "minLongitude")
        assertEquals(LAT + DLAT, sector.maxLatitude.inDegrees, 0.0, "maxLatitude")
        assertEquals(LON + DLON, sector.maxLongitude.inDegrees, 0.0, "maxLongitude")
        assertEquals(DLAT, sector.deltaLatitude.inDegrees, 0.0, "deltaLatitude")
        assertEquals(DLON, sector.deltaLongitude.inDegrees, 0.0, "deltaLongitude")
    }

    @Test
    fun testConstructor_Copy() {
        val sector = fromDegrees(LAT, LON, DLAT, DLON)
        val copy = Sector(sector)
        assertNotNull(copy)
        assertEquals(sector, copy)
    }

    @Test
    fun testFromDegrees() {
        val sector = fromDegrees(LAT, LON, DLAT, DLON)
        assertNotNull(sector)
        assertEquals(LAT, sector.minLatitude.inDegrees, 0.0, "minLatitude")
        assertEquals(LON, sector.minLongitude.inDegrees, 0.0, "minLongitude")
        assertEquals(LAT + DLAT, sector.maxLatitude.inDegrees, 0.0, "maxLatitude")
        assertEquals(LON + DLON, sector.maxLongitude.inDegrees, 0.0, "maxLongitude")
        assertEquals(DLAT, sector.deltaLatitude.inDegrees, 0.0, "deltaLatitude")
        assertEquals(DLON, sector.deltaLongitude.inDegrees, 0.0, "deltaLongitude")
    }

    @Test
    fun testFromRadians() {
        val sector = fromRadians(toRadians(LAT), toRadians(LON), toRadians(DLAT), toRadians(DLON))
        assertNotNull(sector)
        assertEquals(LAT, sector.minLatitude.inDegrees, TOLERANCE, "minLatitude")
        assertEquals(LON, sector.minLongitude.inDegrees, TOLERANCE, "minLongitude")
        assertEquals(LAT + DLAT, sector.maxLatitude.inDegrees, TOLERANCE, "maxLatitude")
        assertEquals(LON + DLON, sector.maxLongitude.inDegrees, TOLERANCE, "maxLongitude")
        assertEquals(DLAT, sector.deltaLatitude.inDegrees, TOLERANCE, "deltaLatitude")
        assertEquals(DLON, sector.deltaLongitude.inDegrees, TOLERANCE, "deltaLongitude")
    }

    @Test
    fun testEquals() {
        val sector1 = fromDegrees(LAT, LON, DLAT, DLON)
        val sector2 = fromDegrees(LAT, LON, DLAT, DLON)
        assertEquals(sector2.minLatitude.inDegrees, sector1.minLatitude.inDegrees, 0.0, "minLatitude")
        assertEquals(sector2.minLongitude.inDegrees, sector1.minLongitude.inDegrees, 0.0, "minLongitude")
        assertEquals(sector2.maxLatitude.inDegrees, sector1.maxLatitude.inDegrees, 0.0, "maxLatitude")
        assertEquals(sector2.maxLongitude.inDegrees, sector1.maxLongitude.inDegrees, 0.0, "maxLongitude")
        assertEquals(sector2.deltaLatitude.inDegrees, sector1.deltaLatitude.inDegrees, 0.0, "deltaLatitude")
        assertEquals(sector2.deltaLongitude.inDegrees, sector1.deltaLongitude.inDegrees, 0.0, "deltaLongitude")
        assertEquals(sector1, sector1)
        assertEquals(sector1, sector2)
    }

    @Test
    fun testEquals_Inequality() {
        val empty = Sector()
        val other = Sector()
        val typical = fromDegrees(34.2, -119.2, 1.0, 2.0)
        val another = fromDegrees(33.94, -118.4, 1.0, 2.0)
        assertNotEquals(empty, empty)
        assertNotEquals(empty, other)
        assertNotEquals(empty, typical)
        assertNotNull(empty)
        assertNotEquals(typical, another)
        assertNotNull(typical)
    }

    @Test
    fun testHashCode() {
        val a = Sector()
        val b = fromDegrees(34.2, -119.2, 1.0, 2.0)
        val c = fromDegrees(33.94, -118.4, 1.0, 2.0)
        val aHash = a.hashCode()
        val bHash = b.hashCode()
        val cHash = c.hashCode()
        assertNotEquals(bHash, aHash, "a hash vs b hash")
        assertNotEquals(cHash, bHash, "b hash vs c hash")
    }

    @Test
    fun testIsEmpty() {
        val empty = Sector()
//        val noDim = fromDegrees(34.2, -119.2, 0.0, 0.0)
//        val noWidth = fromDegrees(34.2, -119.2, 1.0, 0.0)
//        val noHeight = fromDegrees(34.2, -119.2, 0.0, 1.0)
//        val noLat = fromDegrees(Double.NaN, -119.2, 1.0, 1.0)
//        val noLon = fromDegrees(34.2, Double.NaN, 1.0, 1.0)
        val typical = fromDegrees(34.2, -119.2, 1.0, 1.0)
        assertTrue(empty.isEmpty, "default is empty")
// Sector without dimensions is not considered empty anymore
//        assertTrue(noDim.isEmpty, "no dimension is empty")
//        assertTrue(noWidth.isEmpty, "no width is empty")
//        assertTrue(noHeight.isEmpty, "no height is empty")
// NaN Angle is not supported anymore
//        assertTrue(noLat.isEmpty, "no latitude is empty")
//        assertTrue(noLon.isEmpty, "no longitude is empty")
        assertFalse(typical.isEmpty, "typical is not empty")
    }

    @Test
    fun testMinLatitude() {
        val sector = fromDegrees(LAT, LON, DLAT, DLON)
        assertEquals(LAT, sector.minLatitude.inDegrees, 0.0, "minLatitude")
    }

    @Test
    fun testMaxLatitude() {
        val sector = fromDegrees(LAT, LON, DLAT, DLON)
        assertEquals(LAT + DLAT, sector.maxLatitude.inDegrees, 0.0, "maxLatitude")
    }

    @Test
    fun testMinLongitude() {
        val sector = fromDegrees(LAT, LON, DLAT, DLON)
        assertEquals(LON, sector.minLongitude.inDegrees, 0.0, "minLongitude")
    }

    @Test
    fun testMaxLongitude() {
        val sector = fromDegrees(LAT, LON, DLAT, DLON)
        assertEquals(LON + DLON, sector.maxLongitude.inDegrees, 0.0, "maxLongitude")
    }

    @Test
    fun testDeltaLatitude() {
        val sector = fromDegrees(LAT, LON, DLAT, DLON)
        assertEquals(DLAT, sector.deltaLatitude.inDegrees, 0.0, "deltaLatitude")
    }

    @Test
    fun testDeltaLongitude() {
        val sector = fromDegrees(LAT, LON, DLAT, DLON)
        assertEquals(DLON, sector.deltaLongitude.inDegrees, 0.0, "deltaLongitude")
    }

    @Test
    fun testCentroidLatitude() {
        val sector = fromDegrees(LAT, LON, DLAT, DLON)
        val latitude = sector.centroidLatitude
        assertEquals(LAT + DLAT * 0.5, latitude.inDegrees, TOLERANCE, "centroid latitude")
    }

    @Test
    fun testCentroidLatitude_NoDimension() {
        val sector = fromDegrees(LAT, LON, 0.0, DLON)
        val latitude = sector.centroidLatitude
//        assertTrue("NaN centroid latitude", latitude.degrees.isNaN())
        assertEquals(LAT, latitude.inDegrees, 0.0, "Zero centroid latitude")
    }

    @Test
    fun testCentroidLongitude() {
        val sector = fromDegrees(LAT, LON, DLAT, DLON)
        val longitude = sector.centroidLongitude
        assertEquals(LON + DLON * 0.5, longitude.inDegrees, TOLERANCE, "centroid longitude")
    }

    @Test
    fun testCentroidLongitude_NoDimension() {
        val sector = fromDegrees(LAT, LON, DLAT, 0.0)
        val longitude = sector.centroidLongitude
//        assertTrue("NaN centroid longitude", longitude.degrees.isNaN())
        assertEquals(LON, longitude.inDegrees, 0.0, "Zero centroid longitude")
    }

    @Test
    fun testCentroid() {
        val sector = fromDegrees(LAT, LON, DLAT, DLON)
        val centroid = sector.centroid(Location())
        assertEquals(LAT + DLAT * 0.5, centroid.latitude.inDegrees, TOLERANCE, "centroid longitude")
        assertEquals(LON + DLON * 0.5, centroid.longitude.inDegrees, TOLERANCE, "centroid longitude")
    }

    @Test
    fun testCentroid_NoDimension() {
        val sector = fromDegrees(LAT, LON, DLAT, 0.0)
        val centroid = sector.centroid(Location())
//        assertTrue("NaN centroid longitude", centroid.longitude.degrees.isNaN())
        assertEquals(LON, centroid.longitude.inDegrees, 0.0, "Zero centroid longitude")
    }

    @Test
    fun testSet_Doubles() {
        val a = Sector()
        val b = a.set(LAT.degrees, LON.degrees, DLAT.degrees, DLON.degrees)
        assertEquals(LAT, a.minLatitude.inDegrees, 0.0, "minLatitude")
        assertEquals(LON, a.minLongitude.inDegrees, 0.0, "minLongitude")
        assertEquals(DLAT, a.deltaLatitude.inDegrees, 0.0, "deltaLatitude")
        assertEquals(DLON, a.deltaLongitude.inDegrees, 0.0, "deltaLongitude")
        assertSame(a, b)
    }

    @Test
    fun testSet() {
        val a = Sector()
        val b = a.copy(fromDegrees(LAT, LON, DLAT, DLON))
        assertEquals(LAT, a.minLatitude.inDegrees, 0.0, "minLatitude")
        assertEquals(LON, a.minLongitude.inDegrees, 0.0, "minLongitude")
        assertEquals(DLAT, a.deltaLatitude.inDegrees, 0.0, "deltaLatitude")
        assertEquals(DLON, a.deltaLongitude.inDegrees, 0.0, "deltaLongitude")
        assertSame(a, b)
    }

    @Test
    fun testSetEmpty() {
        val a = fromDegrees(LAT, LON, DLAT, DLON)
        a.setEmpty()
        assertTrue(a.isEmpty, "empty")
    }

    @Test
    fun testSetFullSphere() {
        val a = fromDegrees(LAT, LON, DLAT, DLON)
        a.setFullSphere()
        assertEquals(-90.0, a.minLatitude.inDegrees, 0.0, "minLatitude")
        assertEquals(90.0, a.maxLatitude.inDegrees, 0.0, "maxLatitude")
        assertEquals(-180.0, a.minLongitude.inDegrees, 0.0, "minLongitude")
        assertEquals(180.0, a.maxLongitude.inDegrees, 0.0, "maxLongitude")
        assertEquals(180.0, a.deltaLatitude.inDegrees, 0.0, "deltaLatitude")
        assertEquals(360.0, a.deltaLongitude.inDegrees, 0.0, "deltaLongitude")
    }

    @Test
    fun testIntersects() {
        val a = fromDegrees(30.0, 100.0, 3.0, 3.0)
        val copy = Sector(a)
        assertTrue(a.intersects(fromDegrees(31.0, 101.0, 1.0, 1.0)), "inside")
        assertTrue(a.intersects(fromDegrees(31.0, 102.0, 1.0, 2.0)), "overlap east")
        assertTrue(a.intersects(fromDegrees(31.0, 99.0, 1.0, 2.0)), "overlap west")
        assertTrue(a.intersects(fromDegrees(32.0, 101.0, 2.0, 1.0)), "overlap north")
        assertTrue(a.intersects(fromDegrees(29.0, 101.0, 2.0, 1.0)), "overlap south")
        assertEquals(copy, a, "no mutation")
    }

    @Test
    fun testIntersects_Empty() {
        val a = fromDegrees(30.0, 100.0, 3.0, 3.0)
        assertFalse(a.intersects(Sector()), "empty")
// Sector without dimensions is not considered empty anymore
//        assertFalse(a.intersects(fromDegrees(31.0, 101.0, 0.0, 0.0)), "no dimension")
//        assertFalse(a.intersects(Sector().union(fromDegrees(31.0, 101.0))), "no dimension from union")
//        assertFalse(a.intersects(fromDegrees(31.0, 101.0, 5.0, 0.0)), "no width")
//        assertFalse(a.intersects(fromDegrees(31.0, 101.0, 0.0, 5.0)), "no height")
// NaN Angle is not supported anymore
//        assertFalse(a.intersects(fromDegrees(Double.NaN, 101.0, 5.0, 5.0)), "no lat")
//        assertFalse(a.intersects(fromDegrees(31.0, Double.NaN, 5.0, 5.0)), "no lon")
    }

    @Test
    fun testIntersects_Coincident() {
        val a = fromDegrees(30.0, 100.0, 1.0, 1.0)
        assertTrue(a.intersects(fromDegrees(30.0, 100.0, 1.0, 1.0)), "coincident")
        assertFalse(a.intersects(fromDegrees(30.0, 101.0, 1.0, 1.0)), "coincident east edge")
        assertFalse(a.intersects(fromDegrees(30.0, 99.0, 1.0, 1.0)), "coincident west edge")
        assertFalse(a.intersects(fromDegrees(31.0, 100.0, 1.0, 1.0)), "coincident north edge")
        assertFalse(a.intersects(fromDegrees(29.0, 100.0, 1.0, 1.0)), "coincident south edge")
        assertFalse(a.intersects(fromDegrees(31.0, 101.0, 1.0, 1.0)), "coincident ne point")
        assertFalse(a.intersects(fromDegrees(29.0, 101.0, 1.0, 1.0)), "coincident se point")
        assertFalse(a.intersects(fromDegrees(31.0, 99.0, 1.0, 1.0)), "coincident nw point")
        assertFalse(a.intersects(fromDegrees(29.0, 99.0, 1.0, 1.0)), "coincident sw point")
    }

    @Test
    fun testIntersect() {
        val a = fromDegrees(30.0, 100.0, 2.0, 2.0)
        val b = fromDegrees(31.0, 101.0, 2.0, 2.0)
        val northeast = fromDegrees(31.0, 101.0, 1.0, 1.0)
        val intersected = a.intersect(b)
        assertTrue(intersected, "intersecting")
        assertEquals(northeast, a, "intersection")
    }

    @Test
    fun testIntersect_Inside() {
        val a = fromDegrees(30.0, 100.0, 3.0, 3.0)
        val inside = fromDegrees(31.0, 101.0, 1.0, 1.0)
        val intersected = a.intersect(inside)
        assertTrue(intersected, "intersecting")
        assertEquals(inside, a, "inside, intersection is interior sector")
    }

    @Test
    fun testIntersect_East() {
        val a = fromDegrees(30.0, 100.0, 3.0, 3.0)
        val east = fromDegrees(31.0, 102.0, 1.0, 2.0)
        val expected = fromDegrees(31.0, 102.0, 1.0, 1.0)
        val intersected = a.intersect(east)
        assertTrue(intersected, "overlapping")
        assertEquals(a, expected, "intersection")
    }

    @Test
    fun testIntersect_West() {
        val a = fromDegrees(30.0, 100.0, 3.0, 3.0)
        val west = fromDegrees(31.0, 99.0, 1.0, 2.0)
        val expected = fromDegrees(31.0, 100.0, 1.0, 1.0)
        val intersected = a.intersect(west)
        assertTrue(intersected, "overlapping")
        assertEquals(a, expected, "intersection")
    }

    @Test
    fun testIntersect_North() {
        val a = fromDegrees(30.0, 100.0, 3.0, 3.0)
        val north = fromDegrees(32.0, 101.0, 2.0, 1.0)
        val expected = fromDegrees(32.0, 101.0, 1.0, 1.0)
        val intersected = a.intersect(north)
        assertTrue(intersected, "overlapping")
        assertEquals(a, expected, "intersection")
    }

    @Test
    fun testIntersect_South() {
        val a = fromDegrees(30.0, 100.0, 3.0, 3.0)
        val south = fromDegrees(29.0, 101.0, 2.0, 1.0)
        val expected = fromDegrees(30.0, 101.0, 1.0, 1.0)
        val intersected = a.intersect(south)
        assertTrue(intersected, "overlapping")
        assertEquals(a, expected, "intersection")
    }

    @Test
    fun testIntersect_AdjacentEast() {
        val a = fromDegrees(30.0, 100.0, 3.0, 3.0)
        val adjacentEast = fromDegrees(31.0, 103.0, 1.0, 1.0)
        val copy = Sector(a)
        val intersected = a.intersect(adjacentEast)
        assertFalse(intersected, "adjacent, no intersection")
        assertEquals(a, copy, "adjacent, no changed")
    }

    @Test
    fun testIntersect_AdjacentWest() {
        val a = fromDegrees(30.0, 100.0, 3.0, 3.0)
        val adjacentWest = fromDegrees(31.0, 99.0, 1.0, 1.0)
        val copy = Sector(a)
        val intersected = a.intersect(adjacentWest)
        assertFalse(intersected, "adjacent, no intersection")
        assertEquals(a, copy, "adjacent, no changed")
    }

    @Test
    fun testIntersect_AdjacentNorth() {
        val a = fromDegrees(30.0, 100.0, 3.0, 3.0)
        val adjacentNorth = fromDegrees(33.0, 101.0, 1.0, 1.0)
        val copy = Sector(a)
        val intersected = a.intersect(adjacentNorth)
        assertFalse(intersected, "adjacent, no intersection")
        assertEquals(a, copy, "adjacent, no changed")
    }

    @Test
    fun testIntersect_AdjacentSouth() {
        val a = fromDegrees(30.0, 100.0, 3.0, 3.0)
        val adjacentSouth = fromDegrees(29.0, 101.0, 1.0, 1.0)
        val copy = Sector(a)
        val intersected = a.intersect(adjacentSouth)
        assertFalse(intersected, "adjacent, no intersection")
        assertEquals(a, copy, "adjacent, no changed")
    }

    @Test
    fun testContains() {
        val a = fromDegrees(30.0, 100.0, 1.0, 1.0)
        assertTrue(a.contains(fromDegrees(30.5, 100.5)), "inside")
        assertTrue(a.contains(fromDegrees(31.0, 101.0)), "northeast point")
        assertTrue(a.contains(fromDegrees(31.0, 100.0)), "northwest point")
        assertTrue(a.contains(fromDegrees(30.0, 100.0)), "southwest point")
        assertTrue(a.contains(fromDegrees(30.0, 101.0)), "southeast point")
        assertFalse(a.contains(fromDegrees(-30.0, -100.0)), "outside")
    }

    @Test
    fun testContains_Empty() {
        val a = Sector()
        assertFalse(a.contains(fromDegrees(31.0, 101.0)), "empty doesn't contain")
    }

    @Test
    fun testContains_Sector() {
        val a = fromDegrees(30.0, 100.0, 3.0, 3.0)
        assertTrue(a.contains(fromDegrees(30.0, 100.0, 3.0, 3.0)), "coincident")
        assertTrue(a.contains(fromDegrees(31.0, 101.0, 1.0, 1.0)), "inside")
    }

    @Test
    fun testUnion() {
        val latOxr = 34.2.degrees
        val lonOxr = (-119.2).degrees
        val latLax = 33.94.degrees
        val lonLax = (-118.4).degrees
        val a = Sector()
        val b = a.union(latOxr, lonOxr)
//        assertTrue(a.isEmpty) // Sector with specified lat and lon in not considered empty enymore
        a.union(latLax, lonLax)
        assertFalse(a.isEmpty)
        assertEquals(latLax.inDegrees, a.minLatitude.inDegrees, 0.0, "min lat")
        assertEquals(lonOxr.inDegrees, a.minLongitude.inDegrees, 0.0, "min lon")
        assertEquals(latOxr.inDegrees, a.maxLatitude.inDegrees, 0.0, "max lat")
        assertEquals(lonLax.inDegrees, a.maxLongitude.inDegrees, 0.0, "max lon")
        assertSame(a, b)
    }

    @Test
    fun testUnion_ArrayOfLocations() {
        val array = floatArrayOf(
                -119.2f, 34.2f,  // OXR airport
                -118.4f, 33.94f, // LAX airport
                -118.45f, 34.02f // SMO airport
        )
        val a = Sector()
        val b = a.union(array, array.size, 2 /*stride*/)
        assertFalse(a.isEmpty)
        // Delta 1e-5 is required due to double to float conversion
        assertEquals(33.94 /*LAX lat*/, a.minLatitude.inDegrees, 1e-5, "min lat")
        assertEquals(-119.2 /*OXR lon*/, a.minLongitude.inDegrees, 1e-5, "min lon")
        assertEquals(34.2 /*OXR lat*/, a.maxLatitude.inDegrees, 1e-5, "max lat")
        assertEquals(-118.4 /*LAX lon*/, a.maxLongitude.inDegrees, 1e-5, "max lon")
        assertSame(a, b)
    }

    @Test
    fun testUnion_Sector() {
        val a = fromDegrees(-30.0, -100.0, 1.0, 1.0)
        val b = a.union(fromDegrees(40.0, 110.0, 1.0, 1.0))
        assertFalse(a.isEmpty)
        assertEquals(-30.0, a.minLatitude.inDegrees, 0.0, "min lat")
        assertEquals(-100.0, a.minLongitude.inDegrees, 0.0, "min lon")
        assertEquals(41.0, a.maxLatitude.inDegrees, 0.0, "max lat")
        assertEquals(111.0, a.maxLongitude.inDegrees, 0.0, "max lon")
        assertSame(a, b)
    }
}