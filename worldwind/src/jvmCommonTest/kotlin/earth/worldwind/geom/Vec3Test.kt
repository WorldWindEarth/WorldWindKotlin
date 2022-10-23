package earth.worldwind.geom

import earth.worldwind.geom.Angle.Companion.degrees
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.*

/**
 * Unit tests for Vec3, a three-component vector.
 */
class Vec3Test {
    companion object {
        const val TOLERANCE = 1e-10
    }

    /**
     * Tests default constructor member initialization.
     */
    @Test
    fun testConstructor_Default() {
        val u = Vec3()
        assertNotNull(u)
        assertEquals(0.0, u.x, 0.0, "x")
        assertEquals(0.0, u.y, 0.0, "y")
        assertEquals(0.0, u.z, 0.0, "z")
    }

    /**
     * Tests constructor member initialization from doubles.
     */
    @Test
    fun testConstructor_Doubles() {
        val x1 = 3.1
        val y1 = 4.2
        val z1 = 5.3
        val u = Vec3(x1, y1, z1)
        assertNotNull(u)
        assertEquals(x1, u.x, 0.0, "x")
        assertEquals(y1, u.y, 0.0, "y")
        assertEquals(z1, u.z, 0.0, "y")
    }

    /**
     * Ensures equality of object and its members.
     */
    @Test
    fun testEquals() {
        val x1 = 3.1
        val y1 = 4.2
        val z1 = 5.3
        val u = Vec3(x1, y1, z1)
        val v = Vec3(x1, y1, z1)
        assertEquals(x1, u.x, 0.0, "equality: x")
        assertEquals(y1, u.y, 0.0, "equality: y")
        assertEquals(z1, u.z, 0.0, "equality: z")
        assertEquals(u, u, "equality") // equality with self
        assertEquals(u, v, "equality") // equality with other
    }

    /**
     * Ensures inequality of object and members.
     */
    @Test
    fun testEquals_Inequality() {
        val x1 = 3.1
        val y1 = 4.2
        val z1 = 5.3
        val value = 17.0
        val u = Vec3(x1, y1, z1)
        // Vary a each component to assert equals() tests all components
        val vx = Vec3(value, y1, z1)
        val vy = Vec3(x1, value, z1)
        val vz = Vec3(x1, y1, value)
        assertNotEquals(u, vx, "inequality: x component")
        assertNotEquals(u, vy, "inequality: y component")
        assertNotEquals(u, vz, "inequality: z component")
    }

    /**
     * Ensures string output contains member representations.
     */
    @Test
    fun testToString() {
        val x1 = 3.1
        val y1 = 4.2
        val z1 = 5.3
        val u = Vec3(x1, y1, z1)
        val string = u.toString()
        assertTrue(string.contains(x1.toString()), "x")
        assertTrue(string.contains(y1.toString()), "y")
        assertTrue(string.contains(z1.toString()), "z")
    }

    /**
     * Ensures the correct computation of vector's magnitude, or length..
     */
    @Test
    fun testMagnitude() {
        val x1 = 3.1
        val y1 = 4.2
        val z1 = -5.3
        val u = Vec3(x1, y1, z1)
        val magnitude = u.magnitude
        assertEquals(sqrt(x1 * x1 + y1 * y1 + z1 * z1), magnitude, Double.MIN_VALUE, "magnitude")
    }

    /**
     * Ensures a zero length vector from default constructor.
     */
    @Test
    fun testMagnitude_ZeroLength() {
        val u = Vec3()
        val magnitude = u.magnitude
        assertEquals(0.0, magnitude, 0.0, "zero length")
    }

    /**
     * Ensures length is NaN when a member is NaN.
     */
    @Test
    fun testMagnitude_NaN() {
        val x1 = 3.1
        val y1 = 4.2
        val z1 = Double.NaN
        val u = Vec3(x1, y1, z1)
        val magnitude = u.magnitude
        assertTrue(magnitude.isNaN(), "Nan")
    }

    /**
     * Tests the squared length of a vector with a well known right-triangle.
     */
    @Test
    fun testMagnitudeSquared() {
        val x1 = 3.1
        val y1 = 4.2
        val z1 = -5.3
        val u = Vec3(x1, y1, z1)
        val magnitudeSquared = u.magnitudeSquared
        assertEquals(x1 * x1 + y1 * y1 + z1 * z1, magnitudeSquared, Double.MIN_VALUE, "magnitude squared")
    }

    /**
     * Tests the distance (or displacement) between two opposing position-vectors.
     */
    @Test
    fun testDistanceTo() {
        val x1 = 3.1
        val y1 = 4.2
        val z1 = -5.3
        val x2 = -x1
        val y2 = -y1
        val z2 = -z1
        val u = Vec3(x1, y1, z1)
        val v = Vec3(x2, y2, z2)
        val magnitude = u.magnitude
        val distanceTo = u.distanceTo(v)
        assertEquals(magnitude * 2, distanceTo, Double.MIN_VALUE, "distance")
    }

    /**
     * Tests the squared distance (or displacement) between two opposing position-vectors.
     */
    @Test
    fun testDistanceToSquared() {
        val x1 = 3.1
        val y1 = 4.2
        val z1 = -5.3
        val x2 = -x1
        val y2 = -y1
        val z2 = -z1
        val u = Vec3(x1, y1, z1)
        val v = Vec3(x2, y2, z2)
        val magnitude = u.magnitude
        val distanceToSquared = u.distanceToSquared(v)
        assertEquals((magnitude * 2).pow(2.0), distanceToSquared, Double.MIN_VALUE, "distance squared")
    }

    /**
     * Ensures the members are equal to the set method arguments.
     */
    @Test
    fun testSet() {
        val x1 = 3.1
        val y1 = 4.2
        val z1 = -5.3
        val u = Vec3(x1, y1, z1)
        val v = u.set(x1, y1, z1)
        assertEquals(x1, u.x, 0.0, "x")
        assertEquals(y1, u.y, 0.0, "y")
        assertEquals(z1, u.z, 0.0, "z")
        // Assert fluent API returns u
        assertEquals(u, v, "v == u")
    }

    /**
     * Ensures the components of the two vectors are swapped and the fluent API is maintained.
     */
    @Test
    fun testSwap() {
        val x1 = 3.1
        val y1 = 4.2
        val z1 = -5.3
        val x2 = -6.4
        val y2 = -7.5
        val z2 = -8.6
        val u = Vec3(x1, y1, z1)
        val v = Vec3(x2, y2, z2)
        val w = u.swap(v)
        assertEquals(x2, u.x, 0.0, "u.x")
        assertEquals(y2, u.y, 0.0, "u.y")
        assertEquals(z2, u.z, 0.0, "u.z")
        assertEquals(x1, v.x, 0.0, "v.x")
        assertEquals(y1, v.y, 0.0, "v.y")
        assertEquals(z1, v.z, 0.0, "v.z")
        // Assert fluent API returns u
        assertSame(u, w, "w == u")
    }


    /**
     * Ensures the correct addition of two vectors, arguments are not mutated, and the proper fluent API result.
     */
    @Test
    fun testAdd() {
        val x1 = 3.1
        val y1 = 4.3
        val z1 = 5.5
        val x2 = 6.2
        val y2 = 7.4
        val z2 = 8.6
        val u = Vec3(x1, y1, z1)
        val v = Vec3(x2, y2, z2)
        val w = u.add(v)
        assertEquals(x1 + x2, u.x, 0.0, "u.x")
        assertEquals(y1 + y2, u.y, 0.0, "u.y")
        assertEquals(z1 + z2, u.z, 0.0, "u.z")
        // Assert v is not altered
        assertEquals(x2, v.x, 0.0, "v.x")
        assertEquals(y2, v.y, 0.0, "v.y")
        assertEquals(z2, v.z, 0.0, "v.z")
        // Assert fluent API returns u
        assertSame(u, w, "w == u")
    }

    /**
     * Ensures the correct subtraction of two vectors, arguments are not mutated, and the proper fluent API result.
     */
    @Test
    fun testSubtract() {
        val x1 = 3.1
        val y1 = 4.3
        val z1 = 5.5
        val x2 = 6.2
        val y2 = 7.4
        val z2 = 8.6
        val u = Vec3(x1, y1, z1)
        val v = Vec3(x2, y2, z2)
        val w = u.subtract(v)
        assertEquals(x1 - x2, u.x, 0.0, "u.x")
        assertEquals(y1 - y2, u.y, 0.0, "u.y")
        assertEquals(z1 - z2, u.z, 0.0, "u.z")
        // Assert v is not altered
        assertEquals(x2, v.x, 0.0, "v.x")
        assertEquals(y2, v.y, 0.0, "v.y")
        assertEquals(z2, v.z, 0.0, "v.z")
        // Assert fluent API returns u
        assertSame(u, w, "w == u")
    }

    /**
     * Ensures the correct multiplication of a vector and a scalar, and the proper fluent API result.
     */
    @Test
    fun testMultiply() {
        val x1 = 3.0
        val y1 = 4.0
        val z1 = 5.0
        val scalar = 6.0
        val u = Vec3(x1, y1, z1)
        val v = u.multiply(scalar)
        assertEquals(x1 * scalar, u.x, 0.0, "u.x")
        assertEquals(y1 * scalar, u.y, 0.0, "u.y")
        assertEquals(z1 * scalar, u.z, 0.0, "u.z")
        // Assert fluent API returns u
        assertSame(u, v, "v == u")
    }

    @Test
    fun testMultiplyByMatrix() {
        val theta = 30.0.degrees
        val x = 2.0
        val y = 3.0
        val z = 0.0
        // Rotate and translate a unit vector
        val m = Matrix4().multiplyByRotation(0.0, 0.0, 1.0, theta).setTranslation(x, y, z)
        val u = Vec3(1.0, 0.0, 0.0).multiplyByMatrix(m)
        assertEquals(theta.radians, acos(u.x - x), 1e-10, "acos u.x")
        assertEquals(theta.radians, asin(u.y - y), 1e-10, "asin u.y")
    }

    /**
     * Ensures the correct division of a vector by a divisor, and the proper fluent API result.
     */
    @Test
    fun testDivide() {
        val x1 = 3.0
        val y1 = 4.0
        val z1 = 5.0
        val divisor = 6.0
        val u = Vec3(x1, y1, z1)
        val v = u.divide(divisor)
        assertEquals(x1 / divisor, u.x, 0.0, "u.x")
        assertEquals(y1 / divisor, u.y, 0.0, "u.y")
        assertEquals(z1 / divisor, u.z, 0.0, "u.z")
        // Assert fluent API returns u
        assertSame(u, v, "v == u")
    }

    /**
     * Ensures the correct negation of the components and the proper fluent API result.
     */
    @Test
    fun testNegate() {
        val x1 = 3.0
        val y1 = -4.0
        val z1 = 5.0
        val u = Vec3(x1, y1, z1)
        val v = u.negate()
        assertEquals(-x1, u.x, 0.0, "u.x")
        assertEquals(-y1, u.y, 0.0, "u.y")
        assertEquals(-z1, u.z, 0.0, "u.z")
        // Assert fluent API returns u
        assertSame(u, v, "v == u")
    }

    /**
     * Ensures the correct unit vector components and length and the proper fluent API result.
     */
    @Test
    fun testNormalize() {
        val x1 = 3.0
        val y1 = 4.0
        val z1 = 5.0
        val length = sqrt(50.0)
        val u = Vec3(x1, y1, z1)
        val v = u.normalize()
        val magnitude = u.magnitude
        assertEquals(1 / length * x1, u.x, 0.0, "u.x")
        assertEquals(1 / length * y1, u.y, 0.0, "u.y")
        assertEquals(1 / length * z1, u.z, 0.0, "u.z")
        assertEquals(1.0, magnitude, TOLERANCE, "unit length")
        // Assert fluent API returns u
        assertSame(u, v, "v == u")
    }

    /**
     * Ensures the correct dot product (or inner product) of two vectors and vectors are not mutated.
     */
    @Test
    fun testDot() {
        val x1 = 3.1
        val y1 = 4.3
        val z1 = 5.5
        val x2 = 6.2
        val y2 = 7.4
        val z2 = 8.6
        val u = Vec3(x1, y1, z1)
        val v = Vec3(x2, y2, z2)
        val dot = u.dot(v)
        assertEquals(x1 * x2 + y1 * y2 + z1 * z2, dot, 0.0, "dot")
        // Assert u is not altered
        assertEquals(x1, u.x, 0.0, "u.x")
        assertEquals(y1, u.y, 0.0, "u.y")
        assertEquals(z1, u.z, 0.0, "u.z")
        // Assert v is not altered
        assertEquals(x2, v.x, 0.0, "v.x")
        assertEquals(y2, v.y, 0.0, "v.y")
        assertEquals(z2, v.z, 0.0, "v.z")
    }

    /**
     * Ensures the correct cross product (or outer product), arguments are not mutated, and the fluent API is
     * maintained.
     */
    @Test
    fun testCross() {
        val x1 = 1.0
        val y1 = 3.0
        val z1 = -4.0
        val x2 = 2.0
        val y2 = -5.0
        val z2 = 8.0
        // expected result
        val x3 = 4.0
        val y3 = -16.0
        val z3 = -11.0
        val u = Vec3(x1, y1, z1)
        val v = Vec3(x2, y2, z2)
        val r = Vec3(x3, y3, z3)
        val w = u.cross(v)
        assertEquals(y1 * z2 - z1 * y2, u.x, 0.0, "u.x")
        assertEquals(z1 * x2 - x1 * z2, u.y, 0.0, "u.y")
        assertEquals(x1 * y2 - y1 * x2, u.z, 0.0, "u.z")
        assertEquals(r, u, "u == r")
        // Assert v is not altered
        assertEquals(x2, v.x, 0.0, "v.x")
        assertEquals(y2, v.y, 0.0, "v.y")
        assertEquals(z2, v.z, 0.0, "v.z")
        // Assert fluent API returns u
        assertSame(u, w, "w == u")
    }

    /**
     * Ensures the correct interpolation between two vectors, arguments are not mutated, and the proper fluent API
     * result.
     */
    @Test
    fun testMix() {
        val weight = 0.75
        val x1 = 3.1
        val y1 = 4.3
        val z1 = 5.5
        val x2 = 6.2
        val y2 = 7.4
        val z2 = 8.6
        val u = Vec3(x1, y1, z1)
        val v = Vec3(x2, y2, z2)
        val w = u.mix(v, weight)
        assertEquals(x1 + (x2 - x1) * weight, u.x, TOLERANCE, "u.x")
        assertEquals(y1 + (y2 - y1) * weight, u.y, TOLERANCE, "u.y")
        assertEquals(z1 + (z2 - z1) * weight, u.z, TOLERANCE, "u.z")
        // Assert v is not altered
        assertEquals(x2, v.x, 0.0, "v.x")
        assertEquals(y2, v.y, 0.0, "v.y")
        assertEquals(z2, v.z, 0.0, "v.z")
        // Assert fluent API returns u
        assertSame(u, w, "w == u")
    }
}