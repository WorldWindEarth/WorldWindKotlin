package earth.worldwind.geom

import kotlin.test.*

class LineTest {
    @Test
    fun testConstructor_Default() {
        val line = Line()
        assertNotNull(line)
    }

    @Test
    fun testConstructor_Copy() {
        val line = Line()
        val copy = Line(line)
        assertNotNull(copy, "copy")
        assertEquals(line, copy, "copy equal to original")
    }

    @Test
    fun testConstructor_FromVectors() {
        val origin = Vec3(1.0, 2.0, 3.0)
        val direction = Vec3(0.0, 0.0, 1.0)
        val line = Line(origin, direction)
        assertNotNull(line, "new line")
        assertEquals(origin, line.origin, "origin")
        assertEquals(direction, line.direction, "direction")
    }

    @Test
    fun testEquals() {
        val origin = Vec3(1.0, 2.0, 3.0)
        val direction = Vec3(0.0, 0.0, 1.0)
        val line1 = Line(origin, direction)
        val line2 = Line(origin, direction)
        assertEquals(line1.origin, line2.origin, "origin")
        assertEquals(line1.direction, line2.direction, "direction")
        assertEquals(line1, line2, "equals")
    }

    @Test
    fun testEquals_Null() {
        val origin = Vec3(1.0, 2.0, 3.0)
        val direction = Vec3(0.0, 0.0, 1.0)
        val line1 = Line(origin, direction)
        assertNotNull(line1, "inequality with null")
    }

    @Test
    fun testEquals_Inequality() {
        val origin = Vec3(1.0, 2.0, 3.0)
        val direction = Vec3(0.0, 0.0, 1.0)
        val line1 = Line(origin, direction)
        val line2 = Line(direction, origin) // reversed vectors
        assertNotEquals(line1, line2, "not equals")
    }

    @Test
    fun testHashCode() {
        val origin = Vec3(1.0, 2.0, 3.0)
        val direction1 = Vec3(0.0, 0.0, 1.0)
        val direction2 = Vec3(0.0, 1.0, 0.0)
        val line1 = Line(origin, direction1)
        val line2 = Line(line1)
        val line3 = Line(origin, direction2)
        val hashCode1 = line1.hashCode()
        val hashCode2 = line2.hashCode()
        val hashCode3 = line3.hashCode()
        assertEquals(hashCode1, hashCode2)
        assertNotEquals(hashCode1, hashCode3)
    }

    @Test
    fun testToString() {
        val origin = Vec3(1.0, 2.0, 3.0)
        val direction = Vec3(4.0, 5.0, 6.0)
        val line = Line(origin, direction)
        val string = line.toString()
        assertTrue(string.contains(line.origin.x.toString()), "origin x")
        assertTrue(string.contains(line.origin.y.toString()), "origin y")
        assertTrue(string.contains(line.origin.z.toString()), "origin z")
        assertTrue(string.contains(line.direction.x.toString()), "direction x")
        assertTrue(string.contains(line.direction.y.toString()), "direction y")
        assertTrue(string.contains(line.direction.z.toString()), "direction z")
    }

    @Test
    fun testSet() {
        val origin = Vec3(1.0, 2.0, 3.0)
        val direction = Vec3(0.0, 0.0, 1.0)
        val line = Line()
        line.set(origin, direction)
        assertEquals(origin, line.origin, "origin")
        assertEquals(direction, line.direction, "direction")
    }


    @Test
    fun testSetToSegment() {
        val pointA = Vec3(1.0, 2.0, 3.0)
        val pointB = Vec3(4.0, 5.0, 6.0)
        val origin = Vec3(pointA)
        val direction = pointB - pointA
        val line = Line()
        line.setToSegment(pointA, pointB)
        assertEquals(origin, line.origin, "origin")
        assertEquals(direction, line.direction, "direction")
    }

    @Test
    fun testPointAt() {
        val origin = Vec3(1.0, 2.0, 3.0)
        val direction = Vec3(4.0, 5.0, 6.0)
        val line = Line(origin, direction)
        val distance = -2.0
        val expected = origin + direction * distance
        val point = line.pointAt(distance, Vec3())
        assertEquals(expected, point, "point at")
    }


    @Test
    fun testPointAt_NaN() {
        val line = Line()
        val point = line.pointAt(Double.NaN, Vec3())
        assertTrue(point.x.isNaN())
        assertTrue(point.y.isNaN())
        assertTrue(point.z.isNaN())
    }
}
