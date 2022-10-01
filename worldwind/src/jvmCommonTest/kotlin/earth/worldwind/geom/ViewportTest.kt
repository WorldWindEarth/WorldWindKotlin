package earth.worldwind.geom

import kotlin.test.*

class ViewportTest {
    @Test
    fun testConstructor_Default() {
        val viewport = Viewport()
        assertNotNull(viewport)
        assertEquals(0, viewport.x, "x")
        assertEquals(0, viewport.y, "y")
        assertEquals(0, viewport.width, "width")
        assertEquals(0, viewport.height, "height")
    }

    @Test
    fun testConstructor_Copy() {
        val original = Viewport(1, 2, 3, 4)
        val viewport = Viewport(original)
        assertNotNull(viewport)
        assertEquals(1, viewport.x, "x")
        assertEquals(2, viewport.y, "y")
        assertEquals(3, viewport.width, "width")
        assertEquals(4, viewport.height, "height")
    }

    @Test
    fun testConstructor_Parameters() {
        val viewport = Viewport(1, 2, 3, 4)
        assertNotNull(viewport)
        assertEquals(1, viewport.x, "x")
        assertEquals(2, viewport.y, "y")
        assertEquals(3, viewport.width, "width")
        assertEquals(4, viewport.height, "height")
    }

    @Test
    fun testEquals() {
        val viewport1 = Viewport(1, 2, 3, 4)
        val viewport2 = Viewport(1, 2, 3, 4)
        assertEquals(viewport1, viewport2, "equals")
    }

    @Test
    fun testEquals_Null() {
        val viewport = Viewport(1, 2, 3, 4)
        assertNotNull(viewport, "inequality with null")
    }

    @Test
    fun testEquals_Inequality() {
        val viewport1 = Viewport(1, 2, 3, 4)
        val viewport2 = Viewport(4, 3, 2, 1)
        assertNotEquals(viewport1, viewport2, "not equals")
    }

    @Test
    fun testHashCode() {
        val viewport1 = Viewport(1, 2, 3, 4)
        val viewport2 = Viewport(viewport1)
        val viewport3 = Viewport(4, 3, 2, 1)
        val hashCode1 = viewport1.hashCode()
        val hashCode2 = viewport2.hashCode()
        val hashCode3 = viewport3.hashCode()
        assertEquals(hashCode1, hashCode2)
        assertNotEquals(hashCode1, hashCode3)
    }

    @Test
    fun testToString() {
        val viewport = Viewport(1, 2, 3, 4)
        val string = viewport.toString()
        assertTrue(string.contains(viewport.x.toString()), "x")
        assertTrue(string.contains(viewport.y.toString()), "y")
        assertTrue(string.contains(viewport.width.toString()), "width")
        assertTrue(string.contains(viewport.height.toString()), "height")
    }

    @Test
    fun testSet_Parameters() {
        val viewport = Viewport()
        viewport.set(1, 2, 3, 4)
        assertEquals(1, viewport.x, "x")
        assertEquals(2, viewport.y, "y")
        assertEquals(3, viewport.width, "width")
        assertEquals(4, viewport.height, "height")
    }

    @Test
    fun testSet_Copy() {
        val original = Viewport(1, 2, 3, 4)
        val viewport = Viewport()
        viewport.copy(original)
        assertNotSame(original, viewport, "not the same reference")
        assertEquals(1, viewport.x, "x")
        assertEquals(2, viewport.y, "y")
        assertEquals(3, viewport.width, "width")
        assertEquals(4, viewport.height, "height")
    }

    @Test
    fun testSetEmpty() {
        val viewport = Viewport(1, 2, 3, 4)
        viewport.setEmpty()
        assertEquals(1, viewport.x, "x")
        assertEquals(2, viewport.y, "y")
        assertEquals(0, viewport.width, "width")
        assertEquals(0, viewport.height, "height")
    }

    @Test
    fun testIsEmpty() {
        val viewport1 = Viewport()
        val viewport2 = Viewport(1, 2, 3, 4)
        assertTrue(viewport1.isEmpty, "viewport is empty")
        assertFalse(viewport2.isEmpty, "viewport is not empty")
    }

    @Test
    fun testContains() {
        val viewport = Viewport(1, 2, 3, 4)
        assertTrue(viewport.contains(viewport.x, viewport.y), "contains x, y")
        assertTrue(viewport.contains(viewport.x + viewport.width - 1, viewport.y + viewport.height - 1), "contains x+width-1, y+height-1")
        assertFalse(viewport.contains(viewport.x + viewport.width, viewport.y + viewport.height), "does not contain x+width, y+height")
        assertFalse(viewport.contains(viewport.x - 1, viewport.y), "does not contain x-1, y")
        assertFalse(viewport.contains(viewport.x, viewport.y - 1), "does not contain x, y-1")
    }

    @Test
    fun testContains_Empty() {
        val empty = Viewport(1, 2, 0, 0)
        val emptyWidth = Viewport(1, 2, 3, 0)
        val emptyHeight = Viewport(1, 2, 0, 3)
        assertFalse(empty.contains(empty.x, empty.y), "empty does not contain x, y")
        assertFalse(emptyWidth.contains(emptyWidth.x, emptyWidth.y), "empty width not contain x, y")
        assertFalse(emptyHeight.contains(emptyHeight.x, emptyHeight.y), "empty width not contain x, y")
    }

    @Test
    fun testIntersect() {
        val a = Viewport(30, 100, 2, 2)
        val b = Viewport(31, 101, 2, 2)
        val northeast = Viewport(31, 101, 1, 1)
        val intersected = a.intersect(b)
        assertTrue(intersected, "intersecting")
        assertEquals(northeast, a, "intersection")
    }

    @Test
    fun testIntersect_Empty() {
        val a = Viewport(30, 100, 2, 2)
        val b = Viewport(31, 101, 0, 0)
        val aIntersectedB = a.intersect(b)
        val bIntersectedA = b.intersect(a)
        assertFalse(aIntersectedB, "a intersecting b")
        assertFalse(bIntersectedA, "b intersecting a")
    }

    @Test
    fun testIntersect_Inside() {
        val a = Viewport(30, 100, 3, 3)
        val inside = Viewport(31, 101, 1, 1)
        val intersected = a.intersect(inside)
        assertTrue(intersected, "intersecting")
        assertEquals(inside, a, "inside, intersection is interior sector")
    }

    @Test
    fun testIntersect_East() {
        val a = Viewport(30, 100, 3, 3)
        val east = Viewport(31, 102, 1, 2)
        val expected = Viewport(31, 102, 1, 1)
        val intersected = a.intersect(east)
        assertTrue(intersected, "overlapping")
        assertEquals(a, expected, "intersection")
    }

    @Test
    fun testIntersect_West() {
        val a = Viewport(30, 100, 3, 3)
        val west = Viewport(31, 99, 1, 2)
        val expected = Viewport(31, 100, 1, 1)
        val intersected = a.intersect(west)
        assertTrue(intersected, "overlapping")
        assertEquals(a, expected, "intersection")
    }

    @Test
    fun testIntersect_North() {
        val a = Viewport(30, 100, 3, 3)
        val north = Viewport(32, 101, 2, 1)
        val expected = Viewport(32, 101, 1, 1)
        val intersected = a.intersect(north)
        assertTrue(intersected, "overlapping")
        assertEquals(a, expected, "intersection")
    }

    @Test
    fun testIntersect_South() {
        val a = Viewport(30, 100, 3, 3)
        val south = Viewport(29, 101, 2, 1)
        val expected = Viewport(30, 101, 1, 1)
        val intersected = a.intersect(south)
        assertTrue(intersected, "overlapping")
        assertEquals(a, expected, "intersection")
    }

    @Test
    fun testIntersect_AdjacentEast() {
        val a = Viewport(30, 100, 3, 3)
        val adjacentEast = Viewport(31, 103, 1, 1)
        val copy = Viewport(a)
        val intersected = a.intersect(adjacentEast)
        assertFalse(intersected, "adjacent, no intersection")
        assertEquals(a, copy, "adjacent, no changed")
    }

    @Test
    fun testIntersect_AdjacentWest() {
        val a = Viewport(30, 100, 3, 3)
        val adjacentWest = Viewport(31, 99, 1, 1)
        val copy = Viewport(a)
        val intersected = a.intersect(adjacentWest)
        assertFalse(intersected, "adjacent, no intersection")
        assertEquals(a, copy, "adjacent, no changed")
    }

    @Test
    fun testIntersect_AdjacentNorth() {
        val a = Viewport(30, 100, 3, 3)
        val adjacentNorth = Viewport(33, 101, 1, 1)
        val copy = Viewport(a)
        val intersected = a.intersect(adjacentNorth)
        assertFalse(intersected, "adjacent, no intersection")
        assertEquals(a, copy, "adjacent, no changed")
    }

    @Test
    fun testIntersect_AdjacentSouth() {
        val a = Viewport(30, 100, 3, 3)
        val adjacentSouth = Viewport(29, 101, 1, 1)
        val copy = Viewport(a)
        val intersected = a.intersect(adjacentSouth)
        assertFalse(intersected, "adjacent, no intersection")
        assertEquals(a, copy, "adjacent, no changed")
    }

    @Test
    fun testIntersect_Doubles() {
        val a = Viewport(30, 100, 2, 2)
        val b = Viewport(31, 101, 2, 2)
        val northeast = Viewport(31, 101, 1, 1)
        val intersected = a.intersect(b.x, b.y, b.width, b.height)
        assertTrue(intersected, "intersecting")
        assertEquals(northeast, a, "intersection")
    }

    @Test
    fun testIntersect_DoublesEmpty() {
        val a = Viewport(30, 100, 2, 2)
        val b = Viewport(31, 101, 0, 0)
        val aIntersectedB = a.intersect(b.x, b.y, b.width, b.height)
        val bIntersectedA = b.intersect(a.x, a.y, a.width, a.height)
        assertFalse(aIntersectedB, "a intersecting b")
        assertFalse(bIntersectedA, "b intersecting a")
    }

    @Test
    fun testIntersect_DoublesInside() {
        val a = Viewport(30, 100, 3, 3)
        val inside = Viewport(31, 101, 1, 1)
        val intersected = a.intersect(inside.x, inside.y, inside.width, inside.height)
        assertTrue(intersected, "intersecting")
        assertEquals(inside, a, "inside, intersection is interior sector")
    }

    @Test
    fun testIntersect_DoublesEast() {
        val a = Viewport(30, 100, 3, 3)
        val east = Viewport(31, 102, 1, 2)
        val expected = Viewport(31, 102, 1, 1)
        val intersected = a.intersect(east.x, east.y, east.width, east.height)
        assertTrue(intersected, "overlapping")
        assertEquals(a, expected, "intersection")
    }

    @Test
    fun testIntersect_DoublesWest() {
        val a = Viewport(30, 100, 3, 3)
        val west = Viewport(31, 99, 1, 2)
        val expected = Viewport(31, 100, 1, 1)
        val intersected = a.intersect(west.x, west.y, west.width, west.height)
        assertTrue(intersected, "overlapping")
        assertEquals(a, expected, "intersection")
    }

    @Test
    fun testIntersect_DoublesNorth() {
        val a = Viewport(30, 100, 3, 3)
        val north = Viewport(32, 101, 2, 1)
        val expected = Viewport(32, 101, 1, 1)
        val intersected = a.intersect(north.x, north.y, north.width, north.height)
        assertTrue(intersected, "overlapping")
        assertEquals(a, expected, "intersection")
    }

    @Test
    fun testIntersect_DoublesSouth() {
        val a = Viewport(30, 100, 3, 3)
        val south = Viewport(29, 101, 2, 1)
        val expected = Viewport(30, 101, 1, 1)
        val intersected = a.intersect(south.x, south.y, south.width, south.height)
        assertTrue(intersected, "overlapping")
        assertEquals(a, expected, "intersection")
    }

    @Test
    fun testIntersect_DoublesAdjacentEast() {
        val a = Viewport(30, 100, 3, 3)
        val adjacentEast = Viewport(31, 103, 1, 1)
        val copy = Viewport(a)
        val intersected = a.intersect(adjacentEast.x, adjacentEast.y, adjacentEast.width, adjacentEast.height)
        assertFalse(intersected, "adjacent, no intersection")
        assertEquals(a, copy, "adjacent, no changed")
    }

    @Test
    fun testIntersect_DoublesAdjacentWest() {
        val a = Viewport(30, 100, 3, 3)
        val adjacentWest = Viewport(31, 99, 1, 1)
        val copy = Viewport(a)
        val intersected = a.intersect(adjacentWest.x, adjacentWest.y, adjacentWest.width, adjacentWest.height)
        assertFalse(intersected, "adjacent, no intersection")
        assertEquals(a, copy, "adjacent, no changed")
    }

    @Test
    fun testIntersect_DoublesAdjacentNorth() {
        val a = Viewport(30, 100, 3, 3)
        val adjacentNorth = Viewport(33, 101, 1, 1)
        val copy = Viewport(a)
        val intersected = a.intersect(adjacentNorth.x, adjacentNorth.y, adjacentNorth.width, adjacentNorth.height)
        assertFalse(intersected, "adjacent, no intersection")
        assertEquals(a, copy, "adjacent, no changed")
    }

    @Test
    fun testIntersect_DoublesAdjacentSouth() {
        val a = Viewport(30, 100, 3, 3)
        val adjacentSouth = Viewport(29, 101, 1, 1)
        val copy = Viewport(a)
        val intersected = a.intersect(adjacentSouth.x, adjacentSouth.y, adjacentSouth.width, adjacentSouth.height)
        assertFalse(intersected, "adjacent, no intersection")
        assertEquals(a, copy, "adjacent, no changed")
    }

    @Test
    fun testIntersects() {
        val a = Viewport(30, 100, 3, 3)
        val copy = Viewport(a)
        assertTrue(a.intersects(Viewport(31, 101, 1, 1)), "inside")
        assertTrue(a.intersects(Viewport(31, 102, 1, 2)), "overlap east")
        assertTrue(a.intersects(Viewport(31, 99, 1, 2)), "overlap west")
        assertTrue(a.intersects(Viewport(32, 101, 2, 1)), "overlap north")
        assertTrue(a.intersects(Viewport(29, 101, 2, 1)), "overlap south")
        assertEquals(copy, a, "no mutation")
    }

    @Test
    fun testIntersects_Empty() {
        val a = Viewport(30, 100, 3, 3)
        assertFalse(a.intersects(Viewport()), "empty")
        assertFalse(a.intersects(Viewport(31, 101, 0, 0)), "no dimension")
        assertFalse(a.intersects(Viewport(31, 101, 5, 0)), "no width")
        assertFalse(a.intersects(Viewport(31, 101, 0, 5)), "no height")
    }

    @Test
    fun testIntersects_Coincident() {
        val a = Viewport(30, 100, 1, 1)
        assertTrue(a.intersects(Viewport(30, 100, 1, 1)), "coincident")
        assertFalse(a.intersects(Viewport(30, 101, 1, 1)), "coincident east edge")
        assertFalse(a.intersects(Viewport(30, 99, 1, 1)), "coincident west edge")
        assertFalse(a.intersects(Viewport(31, 100, 1, 1)), "coincident north edge")
        assertFalse(a.intersects(Viewport(29, 100, 1, 1)), "coincident south edge")
        assertFalse(a.intersects(Viewport(31, 101, 1, 1)), "coincident ne point")
        assertFalse(a.intersects(Viewport(29, 101, 1, 1)), "coincident se point")
        assertFalse(a.intersects(Viewport(31, 99, 1, 1)), "coincident nw point")
        assertFalse(a.intersects(Viewport(29, 99, 1, 1)), "coincident sw point")
    }

    @Test
    fun testIntersects_Doubles() {
        val a = Viewport(30, 100, 3, 3)
        val copy = Viewport(a)
        assertTrue(a.intersects(31, 101, 1, 1), "inside")
        assertTrue(a.intersects(31, 102, 1, 2), "overlap east")
        assertTrue(a.intersects(31, 99, 1, 2), "overlap west")
        assertTrue(a.intersects(32, 101, 2, 1), "overlap north")
        assertTrue(a.intersects(29, 101, 2, 1), "overlap south")
        assertEquals(copy, a, "no mutation")
    }

    @Test
    fun testIntersects_DoublesEmpty() {
        val a = Viewport(30, 100, 3, 3)
        assertFalse(a.intersects(0, 0, 0, 0), "empty")
        assertFalse(a.intersects(31, 101, 0, 0), "no dimension")
        assertFalse(a.intersects(31, 101, 5, 0), "no width")
        assertFalse(a.intersects(31, 101, 0, 5), "no height")
    }

    @Test
    fun testIntersects_DoublesCoincident() {
        val a = Viewport(30, 100, 1, 1)
        assertTrue(a.intersects(30, 100, 1, 1), "coincident")
        assertFalse(a.intersects(30, 101, 1, 1), "coincident east edge")
        assertFalse(a.intersects(30, 99, 1, 1), "coincident west edge")
        assertFalse(a.intersects(31, 100, 1, 1), "coincident north edge")
        assertFalse(a.intersects(29, 100, 1, 1), "coincident south edge")
        assertFalse(a.intersects(31, 101, 1, 1), "coincident ne point")
        assertFalse(a.intersects(29, 101, 1, 1), "coincident se point")
        assertFalse(a.intersects(31, 99, 1, 1), "coincident nw point")
        assertFalse(a.intersects(29, 99, 1, 1), "coincident sw point")
    }
}