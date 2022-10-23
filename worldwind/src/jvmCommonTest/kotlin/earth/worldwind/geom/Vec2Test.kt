package earth.worldwind.geom

import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.util.Logger
import io.mockk.every
import io.mockk.mockkStatic
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.test.*

/**
 * Unit tests for Vec2: a two-component vector.
 */
class Vec2Test {
    companion object {
        const val X = PI
        const val Y = E

        ////////////////////////////////////////////
        //            Helper Methods
        ////////////////////////////////////////////
        private fun hasNaN(v: Vec2): Boolean {
            return v.x.isNaN() || v.y.isNaN()
        }

        private fun hasInfinite(v: Vec2): Boolean {
            return v.x.isInfinite() || v.y.isInfinite()
        }
    }

    @BeforeTest
    fun setup() {
        mockkStatic(Logger::class)
        every { Logger.logMessage(any(), any(), any(), any()) } returns ""
    }

    /**
     * Tests default constructor member initialization.
     */
    @Test
    fun testConstructor_Default() {
        val u = Vec2()
        assertNotNull(u)
        assertEquals(0.0, u.x, 0.0, "x")
        assertEquals(0.0, u.y, 0.0, "y")
    }

    /**
     * Tests constructor member initialization from doubles.
     */
    @Test
    fun testConstructor_Doubles() {
        val u = Vec2(X, Y)
        assertNotNull(u)
        assertEquals(X, u.x, 0.0, "x")
        assertEquals(Y, u.y, 0.0, "y")
    }

    /**
     * Tests constructor member initialization from doubles.
     */
    @Test
    fun testConstructor_Copy() {
        val u = Vec2(X, Y)
        val copy = Vec2(u)
        assertNotNull(copy)
        assertEquals(X, copy.x, 0.0, "x")
        assertEquals(Y, copy.y, 0.0, "y")
    }


    /**
     * Ensures equality of object and its members.
     */
    @Test
    fun testEquals() {
        val u = Vec2(X, Y)
        val v = Vec2(X, Y)
        assertEquals(u, u, "equality") // equality with self
        assertEquals(u, v, "equality") // equality with other
        assertEquals(X, u.x, 0.0, "equality: x")
        assertEquals(Y, u.y, 0.0, "equality: y")
    }

    /**
     * Ensures inequality of object and members.
     */
    @Test
    fun testEquals_Inequality() {
        val u = Vec2(X, Y)
        val v = Vec2(X, X)
        val w = Vec2(Y, Y)
        assertNotNull(u, "inequality")
        assertNotEquals(u, v, "inequality")
        assertNotEquals(u, v, "inequality: y")
        assertNotEquals(u, w, "inequality: x")
    }

    /**
     * Ensures string output contains member representations.
     */
    @Test
    fun testToString() {
        val u = Vec2(X, Y)
        val string = u.toString()
        assertTrue(string.contains(X.toString()), "x")
        assertTrue(string.contains(Y.toString()), "y")
    }

    /**
     * Ensures array elements match converted vector components.
     */
    @Test
    fun testToArray() {
        val u = Vec2(X, Y)
        val a = u.toArray(FloatArray(2), 0)
        assertEquals(u.x.toFloat(), a[0], 0f, "u.x")
        assertEquals(u.y.toFloat(), a[1], 0f, "u.y")
    }

    /**
     * Ensures the correct offset is written to the array and that the other elements are not altered.
     */
    @Test
    fun testToArray_Offset() {
        val u = Vec2(X, Y)
        val offset = 2
        val array = FloatArray(6)
        u.toArray(array, offset)
        for (i in 0 until offset) {
            assertEquals(0f, array[i], 0f, "element = 0")
        }
        assertEquals(u.x.toFloat(), array[offset], 0f, "u.x")
        assertEquals(u.y.toFloat(), array[offset + 1], 0f, "u.y")
        for (i in offset + 2 until array.size) {
            assertEquals(0f, array[i], 0f, "element = 0")
        }
    }

    @Test
    fun testToArray_ArrayTooSmall() {
        try {
            val u = Vec2(X, Y)
            u.toArray(FloatArray(2), 1)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {

        }
    }

    /**
     * Tests the length of the vector with well known right-triangle hypotenuse.
     */
    @Test
    fun testMagnitude() {
        val u = Vec2(3.0, 4.0)
        val magnitude = u.magnitude
        assertEquals(5.0, magnitude, Double.MIN_VALUE, "hypotenuse")
    }

    /**
     * Ensures positive length with negative members.
     */
    @Test
    fun testMagnitude_NegativeValues() {
        val u = Vec2(-3.0, 4.0)
        val v = Vec2(-3.0, -4.0)
        val uMagnitude = u.magnitude
        val vMagnitude = v.magnitude
        assertEquals(5.0, uMagnitude, Double.MIN_VALUE, "negative x")
        assertEquals(5.0, vMagnitude, Double.MIN_VALUE, "negative x and y")
    }

    /**
     * Ensures zero length from default constructor.
     */
    @Test
    fun testMagnitude_ZeroLength() {
        val u = Vec2()
        val magnitude = u.magnitude
        assertEquals(0.0, magnitude, Double.MIN_VALUE, "zero length")
    }

    /**
     * Tests the limits of a really small vector.
     */
    @Test
    fun testMagnitude_ReallySmall() {
        // Between 1e-154 and 1e-162, the accuracy of the magnitude drops off
        val small = Vec2(1e-154, 0.0)
        val tooSmall = Vec2(1e-155, 0.0)
        val wayTooSmall = Vec2(1e-162, 0.0)
        assertEquals(small.x, small.magnitude, 0.0, "small: magnitude = x")
        assertNotEquals(tooSmall.x, tooSmall.magnitude, 0.0, "too small: magnitude <> x")
        assertEquals(0.0, wayTooSmall.magnitude, 0.0, "way too small: magnitude = 0")
    }

    /**
     * Tests the limits of a really big vector.
     */
    @Test
    fun testMagnitude_ReallyBig() {
        val big = Vec2(1e-154, 0.0)
        val tooBig = Vec2(1e-155, 0.0)
        assertEquals(big.x, big.magnitude, 0.0, "big: magnitude = x")
        assertNotEquals(Double.POSITIVE_INFINITY, tooBig.magnitude, 0.0, "too big: magnitude = Infinity")
    }

    /**
     * Ensures length is NaN when a member is NaN.
     */
    @Test
    fun testMagnitude_NaN() {
        val u = Vec2(Double.NaN, 0.0)
        val magnitude = u.magnitude
        assertTrue(magnitude.isNaN(), "Nan")
    }

    /**
     * Tests the squared length of a vector to a well known right-triangle.
     */
    @Test
    fun testMagnitudeSquared() {
        val u = Vec2(3.0, 4.0)
        val magnitudeSquared = u.magnitudeSquared
        assertEquals(25.0, magnitudeSquared, Double.MIN_VALUE, "3,4,5 hypotenuse squared")
    }

    @Test
    fun testMagnitudeSquared_NaN() {
        val u = Vec2(3.0, Double.NaN)
        val v = Vec2(Double.NaN, 4.0)
        val uMagnitudeSquared = u.magnitudeSquared
        val vMagnitudeSquared = v.magnitudeSquared
        assertTrue(uMagnitudeSquared.isNaN(), "u NaN")
        assertTrue(vMagnitudeSquared.isNaN(), "v NaN")
    }

    /**
     * Tests the distance (or displacement) between two opposing position-vectors using a well known right triangle.
     */
    @Test
    fun testDistanceTo() {
        val u = Vec2(3.0, 4.0)
        val v = Vec2(-3.0, -4.0)
        val distanceTo = u.distanceTo(v)
        assertEquals(10.0, distanceTo, Double.MIN_VALUE, "3,4,5 hypotenuse length doubled")
    }

    /**
     * Tests the squared distance (or displacement) between two opposing position-vectors using a well known right
     * triangle.
     */
    @Test
    fun testDistanceToSquared() {
        val u = Vec2(3.0, 4.0)
        val v = Vec2(-3.0, -4.0)
        val distanceTo = u.distanceToSquared(v)
        assertEquals(100.0, distanceTo, Double.MIN_VALUE, "3,4,5 hypotenuse length doubled and squared")
    }

    /**
     * Ensures propagation of NaN values.
     */
    @Test
    fun testDistanceToSquared_NaN() {
        val u = Vec2(3.0, 4.0)
        assertTrue(u.distanceToSquared(Vec2(Double.NaN, 4.0)).isNaN(), "1st NaN")
        assertTrue(u.distanceToSquared(Vec2(3.0, Double.NaN)).isNaN(), "2nd NaN")
    }

    /**
     * Ensures the members are equal to the set method arguments.
     */
    @Test
    fun testSet() {
        val u = Vec2()
        val v = u.copy(Vec2(X, Y))
        assertEquals(X, u.x, 0.0, "x")
        assertEquals(Y, u.y, 0.0, "y")
        assertEquals(u, v, "v == u")
    }

    @Test
    fun testSet_Doubles() {
        val u = Vec2()
        val v = u.set(X, Y)
        assertEquals(X, u.x, 0.0, "x")
        assertEquals(Y, u.y, 0.0, "y")
        assertEquals(u, v, "v == u")
    }

    /**
     * Ensures the components of the two vectors are swapped and the fluent API is maintained.
     */
    @Test
    fun testSwap() {
        val ux = 3.0
        val uy = 4.0
        val vx = 5.0
        val vy = 6.0
        val u = Vec2(ux, uy)
        val v = Vec2(vx, vy)
        val w = u.swap(v)
        assertEquals(vx, u.x, 0.0, "u.x")
        assertEquals(vy, u.y, 0.0, "u.y")
        assertEquals(ux, v.x, 0.0, "v.x")
        assertEquals(uy, v.y, 0.0, "v.y")
        // Assert fluent API returns u
        assertEquals(u, w, "w == u")
    }


    /**
     * Ensures the correct addition of two vectors, arguments are not mutated, and the proper fluent API result.
     */
    @Test
    fun testAdd() {
        val ux = 3.0
        val uy = 4.0
        val vx = 5.0
        val vy = 6.0
        val u = Vec2(ux, uy)
        val v = Vec2(vx, vy)
        val w = u.add(v)
        assertEquals(ux + vx, u.x, 0.0, "u.x")
        assertEquals(uy + vy, u.y, 0.0, "u.y")
        // Assert v is not altered
        assertEquals(vx, v.x, 0.0, "v.x")
        assertEquals(vy, v.y, 0.0, "v.y")
        // Assert fluent API returns u
        assertEquals(u, w, "w == u")
    }

    @Test
    fun testAdd_NaN() {
        val u = Vec2()
        val v = Vec2()
        assertTrue(hasNaN(u.add(Vec2(Double.NaN, 4.0))), "1st Nan")
        assertTrue(hasNaN(v.add(Vec2(3.0, Double.NaN))), "2nd Nan")
    }


    /**
     * Ensures the correct subtraction of two vectors, arguments are not mutated, and the proper fluent API result.
     */
    @Test
    fun testSubtract() {
        val x1 = 3.0
        val y1 = 4.0
        val x2 = 5.0
        val y2 = 6.0
        val u = Vec2(x1, y1)
        val v = Vec2(x2, y2)
        val w = u.subtract(v)
        assertEquals(x1 - x2, u.x, 0.0, "u.x")
        assertEquals(y1 - y2, u.y, 0.0, "u.y")
        // Assert v is not altered
        assertEquals(x2, v.x, 0.0, "v.x")
        assertEquals(y2, v.y, 0.0, "v.y")
        // Assert fluent API returns u
        assertEquals(u, w, "w == u")
    }

    @Test
    fun testSubtract_NaN() {
        val u = Vec2()
        val v = Vec2()
        assertTrue(hasNaN(u.subtract(Vec2(Double.NaN, 4.0))), "1st Nan")
        assertTrue(hasNaN(v.subtract(Vec2(3.0, Double.NaN))), "2nd Nan")
    }


    /**
     * Ensures the correct multiplication of a vector and a scalar, and the proper fluent API result.
     */
    @Test
    fun testMultiply() {
        val x1 = 3.0
        val y1 = 4.0
        val scalar = 5.0
        val u = Vec2(x1, y1)
        val v = u.multiply(scalar)
        assertEquals(x1 * scalar, u.x, 0.0, "u.x")
        assertEquals(y1 * scalar, u.y, 0.0, "u.y")
        // Assert fluent API returns u
        assertEquals(u, v, "v == u")
    }

    @Test
    fun testMultiply_NaN() {
        assertTrue(hasNaN(Vec2(3.0, 4.0).multiply(Double.NaN)), "1st Nan")
        assertTrue(hasNaN(Vec2(Double.NaN, 4.0).multiply(5.0)), "2nd Nan")
        assertTrue(hasNaN(Vec2(3.0, Double.NaN).multiply(5.0)), "2nd Nan")
    }

    /**
     * Ensures the correct vector component values after it is by a rotation and translation matrix
     */
    @Test
    fun testMultiplyByMatrix() {
        val theta = 30.0.degrees
        val x = 2.0
        val y = 3.0
        val m = Matrix3().multiplyByRotation(theta).setTranslation(x, y)

        // Rotate and translate a unit vector
        val u = Vec2(1.0, 0.0).multiplyByMatrix(m)
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
        val divisor = 5.0
        val u = Vec2(x1, y1)
        val v = u.divide(divisor)
        assertEquals(x1 / divisor, u.x, 0.0, "u.x")
        assertEquals(y1 / divisor, u.y, 0.0, "u.y")
        assertEquals(u, v, "v == u")
    }

    @Test
    fun testDivide_ByZero() {
        assertTrue(hasInfinite(Vec2(3.0, 4.0).divide(0.0)), "Infinity")
    }

    @Test
    fun testDivide_NaN() {
        assertTrue(hasNaN(Vec2(3.0, 4.0).divide(Double.NaN)), "1st Nan")
        assertTrue(hasNaN(Vec2(Double.NaN, 4.0).divide(5.0)), "2nd Nan")
        assertTrue(hasNaN(Vec2(3.0, Double.NaN).divide(5.0)), "2nd Nan")
    }

    /**
     * Ensures the correct unit vector components and length and the proper fluent API result.
     */
    @Test
    fun testNormalize() {
        val x1 = 3.0
        val y1 = 4.0
        val length = 5.0
        val u = Vec2(x1, y1)
        val v = u.normalize()
        val magnitude = u.magnitude
        assertEquals(1 / length * x1, u.x, 1e-15, "u.x")
        assertEquals(1 / length * y1, u.y, 1e-15, "u.y")
        assertEquals(1.0, magnitude, 1e-15, "magnitude")
        assertEquals(u, v, "v == u")
    }

    /**
     * Tests the limits of normalizing a really small vector along the x axis. The length limit is 1e-154.
     */
    @Test
    fun testNormalize_ReallySmall() {
        val small = Vec2(1e-154, 0.0).normalize()
        val tooSmall = Vec2(1e-155, 0.0).normalize()
        assertEquals(1.0, small.x, 0.0, "small: normal = 1.0")
        assertNotEquals(1.0, tooSmall.x, 0.0, "too small: normal <> 1.0")
    }

    /**
     * Tests the limits of normalizing a really big vector long the x axis. The length limit is 1e154.
     */
    @Test
    fun testNormalize_ReallyBig() {
        val big = Vec2(1e154, 0.0).normalize()
        val tooBig = Vec2(1e155, 0.0).normalize()
        assertEquals(1.0, big.x, 0.0, "big: normal = 1.0")
        assertEquals(0.0, tooBig.x, 0.0, "too big: normal = 0.0")
    }

    @Test
    fun testNormalize_BigAndSmallComponents() {
        val extreme = Vec2(1e154, 1e-154).normalize()
        val tooExtreme = Vec2(1e155, 1e-155).normalize()
        assertEquals(1.0, extreme.x, 0.0, "extreme: normal = 1.0")
        assertEquals(0.0, tooExtreme.x, 0.0, "too extreme: normal = 0.0")
    }

    /**
     * Ensures the correct negation of the components and the proper fluent API result.
     */
    @Test
    fun testNegate() {
        val x1 = 3.0
        val y1 = -4.0
        val u = Vec2(x1, y1)
        val v = u.negate()
        assertEquals(-x1, u.x, 0.0, "u.x")
        assertEquals(-y1, u.y, 0.0, "u.y")
        assertEquals(u, v, "v == u")
    }

    @Test
    fun testNegate_NaN() {
        assertTrue(hasNaN(Vec2(Double.NaN, 4.0).negate()), "2nd Nan")
        assertTrue(hasNaN(Vec2(3.0, Double.NaN).negate()), "2nd Nan")
    }

    /**
     * Ensures the correct dot product of two vectors and vectors are not mutated.
     */
    @Test
    fun testDot() {
        val x1 = 3.0
        val y1 = 4.0
        val x2 = 5.0
        val y2 = 6.0
        val u = Vec2(x1, y1)
        val v = Vec2(x2, y2)
        val dot = u.dot(v)
        assertEquals(x1 * x2 + y1 * y2, dot, 0.0, "dot")
        // Assert u is not altered
        assertEquals(x1, u.x, 0.0, "u.x")
        assertEquals(y1, u.y, 0.0, "u.y")
        // Assert v is not altered
        assertEquals(x2, v.x, 0.0, "v.x")
        assertEquals(y2, v.y, 0.0, "v.y")
    }

    /**
     * Ensures the correct interpolation between two vectors, arguments are not mutated, and the proper fluent API
     * result.
     */
    @Test
    fun testMix() {
        val x1 = 3.0
        val y1 = 4.0
        val x2 = 5.0
        val y2 = 6.0
        val weight = 0.75
        val u = Vec2(x1, y1)
        val v = Vec2(x2, y2)
        val w = u.mix(v, weight)
        assertEquals(x1 + (x2 - x1) * weight, u.x, 0.0, "u.x")
        assertEquals(y1 + (y2 - y1) * weight, u.y, 0.0, "u.y")
        // Assert v is not altered
        assertEquals(x2, v.x, 0.0, "v.x")
        assertEquals(y2, v.y, 0.0, "v.y")
        // Assert fluent API returns u
        assertEquals(u, w, "w == u")
    }
}