package earth.worldwind.geom

import earth.worldwind.geom.Angle.Companion.degrees
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

class PlaneTest {
    @Test
    fun testConstructor_Default() {
        val plane = Plane()
        assertNotNull(plane)
        assertEquals(plane.normal.x, 0.0, 0.0, "normal x")
        assertEquals(plane.normal.y, 0.0, 0.0, "normal y")
        assertEquals(plane.normal.z, 1.0, 0.0, "normal z")
        assertEquals(plane.distance, 0.0, 0.0, "distance")
    }

    @Test
    fun testConstructor_Values() {
        val n = Vec3(3.0, 4.0, 5.0).normalize()
        val distance = 6.0
        val plane = Plane(n.x, n.y, n.z, distance)
        assertNotNull(plane)
        assertEquals(plane.normal.x, n.x, 0.0, "normal x")
        assertEquals(plane.normal.y, n.y, 0.0, "normal y")
        assertEquals(plane.normal.z, n.z, 0.0, "normal z")
        assertEquals(plane.distance, distance, 0.0, "distance")
    }

    @Test
    fun testConstructor_NotNormalizedValues() {
        val n = Vec3(3.0, 4.0, 5.0)
        val nExpected = Vec3(n).normalize()
        val distance = 6.0
        val distanceExpected = distance / n.magnitude
        val plane = Plane(n.x, n.y, n.z, distance)
        assertEquals(plane.normal.x, nExpected.x, 0.0, "normal x")
        assertEquals(plane.normal.y, nExpected.y, 0.0, "normal y")
        assertEquals(plane.normal.z, nExpected.z, 0.0, "normal z")
        assertEquals(plane.distance, distanceExpected, 0.0, "distance")
    }

    @Test
    fun testConstructor_ZeroValues() {
        val plane = Plane(0.0, 0.0, 0.0, 0.0)
        assertEquals(plane.normal.x, 0.0, 0.0, "normal x")
        assertEquals(plane.normal.y, 0.0, 0.0, "normal y")
        assertEquals(plane.normal.z, 0.0, 0.0, "normal z")
        assertEquals(plane.distance, 0.0, 0.0, "distance")
    }

    @Test
    fun testConstructor_Copy() {
        val plane = Plane(0.0, 0.0, 1.0, 10.0)
        val copy = Plane(plane)
        assertNotNull(copy, "copy")
        assertEquals(plane, copy, "copy equal to original")
    }

    @Test
    fun testEquals() {
        val n = Vec3(3.0, 4.0, 5.0).normalize()
        val distance = 6.0
        val plane1 = Plane(n.x, n.y, n.z, distance)
        val plane2 = Plane(n.x, n.y, n.z, distance)
        assertEquals(plane1.normal, plane2.normal, "normal")
        assertEquals(plane1.distance, plane2.distance, 0.0, "distance")
        assertEquals(plane1, plane2, "equals")
    }

    @Test
    fun testEquals_Inequality() {
        val n = Vec3(3.0, 4.0, 5.0).normalize()
        val distance1 = 6.0
        val distance2 = 7.0
        val plane1 = Plane(n.x, n.y, n.z, distance1)
        val plane2 = Plane(n.x, n.y, n.z, distance2)
        val plane3 = Plane(0.0, 1.0, 0.0, distance1)
        assertNotEquals(plane1, plane2, "not equals")
        assertNotEquals(plane1, plane3, "not equals")
    }

    @Test
    fun testHashCode() {
        val n = Vec3(3.0, 4.0, 5.0).normalize()
        val distance1 = 6.0
        val distance2 = 7.0
        val plane1 = Plane(n.x, n.y, n.z, distance1)
        val plane2 = Plane(n.x, n.y, n.z, distance1)
        val plane3 = Plane(n.x, n.y, n.z, distance2)
        val hashCode1 = plane1.hashCode()
        val hashCode2 = plane2.hashCode()
        val hashCode3 = plane3.hashCode()
        assertEquals(hashCode1, hashCode2)
        assertNotEquals(hashCode1, hashCode3)
    }

    @Test
    fun testToString() {
        val n = Vec3(3.0, 4.0, 5.0).normalize()
        val distance = 6.0
        val plane = Plane(n.x, n.y, n.z, distance)
        val string = plane.toString()
        assertTrue(string.contains(plane.normal.x.toString()), "normal x")
        assertTrue(string.contains(plane.normal.y.toString()), "normal y")
        assertTrue(string.contains(plane.normal.z.toString()), "normal z")
        assertTrue(string.contains(plane.distance.toString()), "distance")
    }

    @Test
    fun testDistanceToPoint() {
        val normal = Vec3(3.0, 4.0, 5.0).normalize() // arbitrary orientation
        val distance = 10.0 // arbitrary distance
        val plane = Plane(normal.x, normal.y, normal.z, distance)
        // The plane's normal points towards the origin, so use the normal's
        // reversed direction to create a point on the plane
        val point = Vec3(normal).negate().multiply(distance)
        val origin = Vec3(0.0, 0.0, 0.0)
        val distanceToOrigin = plane.distanceToPoint(origin)
        val distanceToPoint = plane.distanceToPoint(point)
        assertEquals(distance, distanceToOrigin, 0.0, "distance to origin")
        assertEquals(0.0, distanceToPoint, 0.0, "distance to point on plane")
    }

    @Test
    fun testSet() {
        val n = Vec3(3.0, 4.0, 5.0).normalize()
        val distance = 6.0
        val plane = Plane(0.0, 0.0, 1.0, 10.0)
        plane.set(n.x, n.y, n.z, distance)
        assertEquals(n, plane.normal, "normal")
        assertEquals(distance, plane.distance, 0.0, "distance")
    }

    @Test
    fun testSet_NotNormalizedValues() {
        val n = Vec3(3.0, 4.0, 5.0)
        val nExpected = Vec3(n).normalize()
        val distance = 6.0
        val distanceExpected = distance / n.magnitude
        val plane = Plane(0.0, 0.0, 1.0, 10.0)
        plane.set(n.x, n.y, n.z, distance)
        assertEquals(plane.normal.x, nExpected.x, 0.0, "normal x")
        assertEquals(plane.normal.y, nExpected.y, 0.0, "normal y")
        assertEquals(plane.normal.z, nExpected.z, 0.0, "normal z")
        assertEquals(plane.distance, distanceExpected, 0.0, "distance")
    }

    @Test
    fun testSet_ZeroValues() {
        val plane = Plane(0.0, 0.0, 1.0, 10.0)
        plane.set(0.0, 0.0, 0.0, 0.0)
        assertEquals(plane.normal.x, 0.0, 0.0, "normal x")
        assertEquals(plane.normal.y, 0.0, 0.0, "normal y")
        assertEquals(plane.normal.z, 0.0, 0.0, "normal z")
        assertEquals(plane.distance, 0.0, 0.0, "distance")
    }

    @Test
    fun testSet_Plane() {
        val n = Vec3(3.0, 4.0, 5.0).normalize()
        val distance = 6.0
        val plane1 = Plane(0.0, 0.0, 1.0, 10.0)
        val plane2 = Plane(n.x, n.y, n.z, distance)
        plane1.copy(plane2)
        assertEquals(n, plane1.normal, "normal")
        assertEquals(distance, plane1.distance, 0.0, "distance")
    }

    @Test
    fun testTransformByMatrix() {
        val p = Plane(0.0, 0.0, -1.0, 10.0)
        // An arbitrary transformation matrix. Note that planes are transformed by the inverse transpose 4x4 matrix.
        val theta = 30.0.degrees
        val c = cos(theta.inRadians)
        val s = sin(theta.inRadians)
        val x = 0.0
        val y = 0.0
        val z = 3.0
        val m = Matrix4()
        m.multiplyByRotation(1.0, 0.0, 0.0, theta)
        m.multiplyByTranslation(x, y, z)
        m.invertOrthonormal().transpose()
        p.transformByMatrix(m)
        assertEquals(p.normal.x, 0.0, 0.0, "normal x")
        assertEquals(p.normal.y, s, 0.0, "normal y")
        assertEquals(p.normal.z, -c, 0.0, "normal z")
        assertEquals(p.distance, 13.0, 0.0, "distance")
    }

    @Test
    fun testDot() {
        val distance = 6.0
        val n = Vec3(3.0, 4.0, 5.0).normalize()
        val u = Vec3(7.0, 8.0, 9.0)
        val plane = Plane(n.x, n.y, n.z, distance)
        val expected = n.dot(u) + distance
        val result = plane.dot(u)
        assertEquals(expected, result, 0.0, "plane dot product")
    }

    @Test
    fun testIntersectsSegment() {
        val p = Plane(0.0, 0.0, -1.0, 0.0)

        // These tests were adapted from WorldWindJava PlaneTest
        var result = p.intersectsSegment(Vec3(), Vec3(0.0, 0.0, -1.0))
        assertTrue(result, "Perpendicular, 0 at origin, should produce intersection at origin")
        result = p.intersectsSegment(Vec3(1.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))
        assertTrue(result, "Line segment is in fact a point, located on the plane, should produce intersection at (1, 0, 0)")
        result = p.intersectsSegment(Vec3(0.0, 0.0, -1.0), Vec3(0.0, 0.0, -1.0))
        assertFalse(result, "Line segment is in fact a point not on the plane, should produce no intersection")
        result = p.intersectsSegment(Vec3(0.0, 0.0, 1.0), Vec3(0.0, 0.0, -1.0))
        assertTrue(result, "Perpendicular, integer end points off origin, should produce intersection at origin")
        result = p.intersectsSegment(Vec3(0.0, 0.0, 0.5), Vec3(0.0, 0.0, -0.5))
        assertTrue(result, "Perpendicular, non-integer end points off origin, should produce intersection at origin")
        result = p.intersectsSegment(Vec3(0.5, 0.5, 0.5), Vec3(-0.5, -0.5, -0.5))
        assertTrue(result, "Not perpendicular, non-integer end points off origin, should produce intersection at origin")
        result = p.intersectsSegment(Vec3(1.0, 0.0, 0.0), Vec3(2.0, 0.0, 0.0))
        assertTrue(result, "Parallel, in plane, should produce intersection at origin")
        result = p.intersectsSegment(Vec3(1.0, 0.0, 1.0), Vec3(2.0, 0.0, 1.0))
        assertFalse(result, "Parallel, integer end points off origin, should produce no intersection")
    }

    @Test
    fun testOnSameSide() {
        val p = Plane(0.0, 0.0, -1.0, 0.0) // a plane at the origin
        var result = p.onSameSide(Vec3(1.0, 2.0, -1.0), Vec3(3.0, 4.0, -1.0))
        assertEquals(1, result, "Different points on positive side of the plane (with respect to normal vector)")
        result = p.onSameSide(Vec3(1.0, 2.0, 1.0), Vec3(3.0, 4.0, 1.0))
        assertEquals(-1, result, "Different points on negative side of the plane (with respect to normal vector)")
        result = p.onSameSide(Vec3(1.0, 2.0, 0.0), Vec3(3.0, 4.0, -1.0))
        assertEquals(0, result, "One point located on the plane, the other on the positive side the plane")
        result = p.onSameSide(Vec3(1.0, 2.0, 0.0), Vec3(3.0, 4.0, 1.0))
        assertEquals(0, result, "One point located on the plane, the other on the negative side the plane")
        result = p.onSameSide(Vec3(1.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))
        assertEquals(0, result, "Coincident points, located on the plane")
        result = p.onSameSide(Vec3(1.0, 2.0, 0.0), Vec3(3.0, 4.0, 0.0))
        assertEquals(0, result, "Different points located on the plane")
        result = p.onSameSide(Vec3(1.0, 2.0, 1.0), Vec3(3.0, 4.0, -1.0))
        assertEquals(0, result, "Different points on opposite sides of the plane")
    }

    @Test
    fun testClip() {
        val p = Plane(0.0, 0.0, -1.0, 0.0) // a plane at the origin
        val a = Vec3(1.0, 2.0, 0.0)
        val b = Vec3(3.0, 4.0, 0.0)

        // If the segment is coincident with the plane, the input points are returned, in their input order.
        val result = p.clip(a, b)
        assertNotNull(result, "Segment coincident with plane")
        assertEquals(result[0], a, "Coincident segment, start point unchanged")
        assertEquals(result[1], b, "Coincident segment, end point unchanged")
    }

    @Test
    fun testClip_NonIntersecting() {
        val p = Plane(0.0, 0.0, -1.0, 0.0) // a plane at the origin
        val a = Vec3(1.0, 2.0, -1.0)
        val b = Vec3(3.0, 4.0, -1.0)

        // If the segment does not intersect the plane, null is returned.
        val result = p.clip(a, b)
        assertNull(result, "Non-intersecting points")
    }

    @Test
    fun testClip_PositiveDirection() {
        // If the direction of the line formed by the two points is positive with respect to this plane's normal vector,
        // the first point in the array will be the intersection point on the plane, and the second point will be the
        // original segment end point.
        val p = Plane(0.0, 0.0, -1.0, 0.0) // a plane at the origin
        val a = Vec3(1.0, 2.0, 1.0)
        val b = Vec3(3.0, 4.0, -1.0)
        val expected0 = Vec3(2.0, 3.0, 0.0)
        val expected1 = Vec3(b)
        val result = p.clip(a, b)
        assertNotNull(result, "Positive direction with respect normal, intersecting the plane")
        assertEquals(expected0, result[0], "Positive direction, the start point is the segment's original begin point")
        assertEquals(expected1, result[1], "Positive direction, the end point is the segment's intersection with the plane")
    }

    @Test
    fun testClip_NegativeDirection() {
        // If the direction of the line is negative with respect to this plane's normal vector, the first point in the
        // array will be the original segment's begin point, and the second point will be the intersection point on the
        // plane.
        val p = Plane(0.0, 0.0, -1.0, 0.0) // a plane at the origin
        val a = Vec3(1.0, 2.0, -1.0)
        val b = Vec3(3.0, 4.0, 1.0)
        val expected0 = Vec3(a)
        val expected1 = Vec3(2.0, 3.0, 0.0)
        val result = p.clip(a, b)
        assertNotNull(result, "Negative direction with respect normal, intersecting the plane")
        assertEquals(expected0, result[0], "Negative direction, the start point is the segment's original begin point")
        assertEquals(expected1, result[1], "Negative direction, the end point is the segment's intersection with the plane")
    }
}