package earth.worldwind.geom

import earth.worldwind.geom.Angle.Companion.degrees
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

/**
 * Unit tests for Matrix3, a 3x3 square matrix in row, column order.
 */
class Matrix3Test {
    companion object {
        //////////////////////
        // Helper methods
        //////////////////////
        private fun computeDeterminant(matrix: Matrix3): Double {
            // |m11  m12  m13|
            // |m21  m22  m23| = m11(m22*m33 - m23*m32) + m12(m23*m31 - m21*m33) + m13(m21*m32 - m22*m31)
            // |m31  m32  m33|
            val m = matrix.m
            return (m[0] * (m[4] * m[8] - m[5] * m[7])
                    + m[1] * (m[5] * m[6] - m[3] * m[8])
                    + m[2] * (m[3] * m[7] - m[4] * m[6]))
        }
    }

    @Test
    fun testConstructor_Default() {
        val m1 = Matrix3()
        assertNotNull(m1)
        assertContentEquals(Matrix3.identity, m1.m, "identity matrix")
    }

    @Test
    fun testConstructor_Doubles() {
        val m1 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0)
        val elements = doubleArrayOf(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0) // identical
        assertNotNull(m1)
        assertContentEquals(elements, m1.m, "matrix components")
    }

    @Test
    fun testEquals() {
        val m1 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0)
        val m2 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0) // identical
        assertEquals(m1, m1, "self")
        assertEquals(m2, m1, "identical matrix")
    }

    @Test
    fun testEquals_Inequality() {
        val m1 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0)
        val m2 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 0.0) // last element is different
        assertNotEquals(m2, m1, "different matrix")
    }

    @Test
    fun testEquals_WithNull() {
        val m1 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0)
        assertNotNull(m1, "null matrix")
    }

    @Test
    fun testHashCode() {
        val m1 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0)
        val m2 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 0.0)
        val hashCode1 = m1.hashCode()
        val hashCode2 = m2.hashCode()
        assertNotEquals(hashCode1, hashCode2, "hash codes")
    }

    @Test
    fun testToString() {
        val string = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0).toString()
        assertTrue(string.contains("[11.0, 12.0, 13.0], [21.0, 22.0, 23.0], [31.0, 32.0, 33.0]"), "all elements in proper order")
    }

    @Test
    fun testSet() {
        val m1 = Matrix3() // matrix under test
        val m2 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0)
        val m3 = m1.copy(m2)
        assertEquals(m2, m1, "set method argument")
        assertSame(m3, m1, "fluent api result")
    }


    @Test
    fun testSet_Doubles() {
        val m11 = 11.0
        val m12 = 12.0
        val m13 = 13.0
        val m21 = 21.0
        val m22 = 22.0
        val m23 = 23.0
        val m31 = 31.0
        val m32 = 32.0
        val m33 = 33.0
        val m1 = Matrix3() // matrix under test
        val m2 = m1.set(m11, m12, m13, m21, m22, m23, m31, m32, m33)
        assertEquals(m11, m1.m[0], 0.0, "m11")
        assertEquals(m12, m1.m[1], 0.0, "m12")
        assertEquals(m13, m1.m[2], 0.0, "m13")
        assertEquals(m21, m1.m[3], 0.0, "m21")
        assertEquals(m22, m1.m[4], 0.0, "m22")
        assertEquals(m23, m1.m[5], 0.0, "m23")
        assertEquals(m31, m1.m[6], 0.0, "m31")
        assertEquals(m32, m1.m[7], 0.0, "m32")
        assertEquals(m33, m1.m[8], 0.0, "m33")
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testSetTranslation() {
        val m1 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0)
        val m2 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0) // identical
        val dx = 5.0
        val dy = 7.0
        val m3 = m1.setTranslation(dx, dy)

        // Test for translation matrix form
        // [m11  m12  dx ]
        // [m21  m22  dy ]
        // [m31  m32  m33]
        assertEquals(m2.m[0], m1.m[0], 0.0, "m11")
        assertEquals(m2.m[1], m1.m[1], 0.0, "m12")
        assertEquals(dx, m1.m[2], 0.0, "m13")
        assertEquals(m2.m[3], m1.m[3], 0.0, "m21")
        assertEquals(m2.m[4], m1.m[4], 0.0, "m22")
        assertEquals(dy, m1.m[5], 0.0, "m23")
        assertEquals(m2.m[6], m1.m[6], 0.0, "m31")
        assertEquals(m2.m[7], m1.m[7], 0.0, "m32")
        assertEquals(m2.m[8], m1.m[8], 0.0, "m33")
        assertSame(m3, m1, "fluent api result")
    }

    @Test
    fun testSetRotation() {
        val m1 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0)
        val m2 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0) // identical
        val theta = 30.0.degrees // rotation angle
        val c = cos(theta.inRadians)
        val s = sin(theta.inRadians)
        val m3 = m1.setRotation(theta)

        // Test for Euler rotation matrix
        // [cos(a) -sin(a)  m13]
        // [sin(a)  cos(a)  m23]
        // [  m31    m32    m33]
        assertEquals(c, m1.m[0], 0.0, "m11")
        assertEquals(-s, m1.m[1], 0.0, "m12")
        assertEquals(m2.m[2], m1.m[2], 0.0, "m13")
        assertEquals(s, m1.m[3], 0.0, "m21")
        assertEquals(c, m1.m[4], 0.0, "m22")
        assertEquals(m2.m[5], m1.m[5], 0.0, "m23")
        assertEquals(m2.m[6], m1.m[6], 0.0, "m31")
        assertEquals(m2.m[7], m1.m[7], 0.0, "m32")
        assertEquals(m2.m[8], m1.m[8], 0.0, "m33")
        assertSame(m3, m1, "fluent api result")
    }

    @Test
    fun testSetScale() {
        val m1 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0)
        val m2 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0) // identical
        val sx = 5.0
        val sy = 7.0
        val m3 = m1.setScale(sx, sy)

        // Test for scaling matrix form
        // [sx   m12  m13]
        // [m21  sy   m23]
        // [m31  m32  m33]
        assertEquals(sx, m1.m[0], 0.0, "m11")
        assertEquals(m2.m[1], m1.m[1], 0.0, "m12")
        assertEquals(m2.m[2], m1.m[2], 0.0, "m13")
        assertEquals(m2.m[3], m1.m[3], 0.0, "m21")
        assertEquals(sy, m1.m[4], 0.0, "m22")
        assertEquals(m2.m[5], m1.m[5], 0.0, "m23")
        assertEquals(m2.m[6], m1.m[6], 0.0, "m31")
        assertEquals(m2.m[7], m1.m[7], 0.0, "m32")
        assertEquals(m2.m[8], m1.m[8], 0.0, "m33")
        assertSame(m3, m1, "fluent api result")
    }

    @Test
    fun testSetToIdentity() {
        val m1 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0)
        val m2 = m1.setToIdentity()
        assertContentEquals(Matrix3.identity, m1.m, "identity matrix")
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testSetToTranslation() {
        val m1 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0)
        val dx = 5.0
        val dy = 7.0
        val m2 = m1.setToTranslation(dx, dy)

        // Test for translation matrix form
        // [1 0 x]
        // [0 1 y]
        // [0 0 1]
        assertEquals(1.0, m1.m[0], 0.0, "m11")
        assertEquals(0.0, m1.m[1], 0.0, "m12")
        assertEquals(dx, m1.m[2], 0.0, "m13")
        assertEquals(0.0, m1.m[3], 0.0, "m21")
        assertEquals(1.0, m1.m[4], 0.0, "m22")
        assertEquals(dy, m1.m[5], 0.0, "m23")
        assertEquals(0.0, m1.m[6], 0.0, "m31")
        assertEquals(0.0, m1.m[7], 0.0, "m32")
        assertEquals(1.0, m1.m[8], 0.0, "m33")
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testSetToRotation() {
        val m1 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0)
        val theta = 30.0.degrees // rotation angle
        val c = cos(theta.inRadians)
        val s = sin(theta.inRadians)
        val m2 = m1.setToRotation(theta)

        // Test for Euler (pronounced "oiler") rotation matrix
        // [cos(a) -sin(a)  0]
        // [sin(a)  cos(a)  0]
        // [  0       0     1]
        assertEquals(c, m1.m[0], 0.0, "m11")
        assertEquals(-s, m1.m[1], 0.0, "m12")
        assertEquals(0.0, m1.m[2], 0.0, "m13")
        assertEquals(s, m1.m[3], 0.0, "m21")
        assertEquals(c, m1.m[4], 0.0, "m22")
        assertEquals(0.0, m1.m[5], 0.0, "m23")
        assertEquals(0.0, m1.m[6], 0.0, "m31")
        assertEquals(0.0, m1.m[7], 0.0, "m32")
        assertEquals(1.0, m1.m[8], 0.0, "m33")
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testSetToScale() {
        val m1 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0)
        val sx = 5.0
        val sy = 7.0
        val m2 = m1.setToScale(sx, sy)

        // Test for scaling matrix form
        // [sx  0  0]
        // [0  sy  0]
        // [0   0  1]
        assertEquals(sx, m1.m[0], 0.0, "m11")
        assertEquals(0.0, m1.m[1], 0.0, "m12")
        assertEquals(0.0, m1.m[2], 0.0, "m13")
        assertEquals(0.0, m1.m[3], 0.0, "m21")
        assertEquals(sy, m1.m[4], 0.0, "m22")
        assertEquals(0.0, m1.m[5], 0.0, "m23")
        assertEquals(0.0, m1.m[6], 0.0, "m31")
        assertEquals(0.0, m1.m[7], 0.0, "m32")
        assertEquals(1.0, m1.m[8], 0.0, "m33")
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testSetToVerticalFlip() {
        val m1 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0)
        val m2 = m1.setToVerticalFlip()

        // Sets this matrix to one that reflects about the x-axis and translates the y-axis origin.
        // [1  0  0]
        // [0 -1  1] <-- *
        // [0  0  1]
        assertEquals(1.0, m1.m[0], 0.0, "m11")
        assertEquals(0.0, m1.m[1], 0.0, "m12")
        assertEquals(0.0, m1.m[2], 0.0, "m13")
        assertEquals(0.0, m1.m[3], 0.0, "m21")
        assertEquals(-1.0, m1.m[4], 0.0, "m22")
        assertEquals(1.0, m1.m[5], 0.0, "m23")
        assertEquals(0.0, m1.m[6], 0.0, "m31")
        assertEquals(0.0, m1.m[7], 0.0, "m32")
        assertEquals(1.0, m1.m[8], 0.0, "m33")
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testSetToMultiply() {
        val m1 = Matrix3()
        val a = Matrix3(
                11.0, 12.0, 13.0,
                21.0, 22.0, 23.0,
                31.0, 32.0, 33.0)
        val b = Matrix3(
                11.0, 12.0, 13.0,
                21.0, 22.0, 23.0,
                31.0, 32.0, 33.0)
        val m2 = m1.setToMultiply(a, b)

        // Test for result of a x b:
        //            1st Column                     2nd Column                     3rd Column
        // [ (a11*b11 + a12*b21 + a13*b31)  (a11*b12 + a12*b22 + a13*b32)  (a11*b13 + a12*b23 + a13*b33) ]
        // [ (a21*b11 + a22*b21 + a23*b31)  (a21*b12 + a22*b22 + a23*b32)  (a21*b13 + a22*b23 + a23*b33) ]
        // [ (a31*b11 + a32*b21 + a33*b31)  (a31*b12 + a32*b22 + a33*b32)  (a31*b13 + a32*b23 + a33*b33) ]
        //
        // 1st Column:
        assertEquals(a.m[0] * b.m[0] + a.m[1] * b.m[3] + a.m[2] * b.m[6], m1.m[0], 0.0, "m11")
        assertEquals(a.m[3] * b.m[0] + a.m[4] * b.m[3] + a.m[5] * b.m[6], m1.m[3], 0.0, "m21")
        assertEquals(a.m[6] * b.m[0] + a.m[7] * b.m[3] + a.m[8] * b.m[6], m1.m[6], 0.0, "m31")
        // 2nd Column:
        assertEquals(a.m[0] * b.m[1] + a.m[1] * b.m[4] + a.m[2] * b.m[7], m1.m[1], 0.0, "m12")
        assertEquals(a.m[3] * b.m[1] + a.m[4] * b.m[4] + a.m[5] * b.m[7], m1.m[4], 0.0, "m22")
        assertEquals(a.m[6] * b.m[1] + a.m[7] * b.m[4] + a.m[8] * b.m[7], m1.m[7], 0.0, "m23")
        // 3rd Column:
        assertEquals(a.m[0] * b.m[2] + a.m[1] * b.m[5] + a.m[2] * b.m[8], m1.m[2], 0.0, "m13")
        assertEquals(a.m[3] * b.m[2] + a.m[4] * b.m[5] + a.m[5] * b.m[8], m1.m[5], 0.0, "m32")
        assertEquals(a.m[6] * b.m[2] + a.m[7] * b.m[5] + a.m[8] * b.m[8], m1.m[8], 0.0, "m33")
        //
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testMultiplyByTranslation() {
        val m1 = Matrix3() // identity matrix
        val dx = 2.0
        val dy = 3.0
        val m2 = m1.multiplyByTranslation(dx, dy)

        // Test for translation matrix form
        // [1 0 x]
        // [0 1 y]
        // [0 0 1]
        assertEquals(1.0, m1.m[0], 0.0, "m11")
        assertEquals(0.0, m1.m[1], 0.0, "m12")
        assertEquals(dx, m1.m[2], 0.0, "m13")
        assertEquals(0.0, m1.m[3], 0.0, "m21")
        assertEquals(1.0, m1.m[4], 0.0, "m22")
        assertEquals(dy, m1.m[5], 0.0, "m23")
        assertEquals(0.0, m1.m[6], 0.0, "m31")
        assertEquals(0.0, m1.m[7], 0.0, "m32")
        assertEquals(1.0, m1.m[8], 0.0, "m33")
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testMultiplyByRotation() {
        val m1 = Matrix3() // identity matrix
        val theta = 30.0.degrees // rotation angle
        val c = cos(theta.inRadians)
        val s = sin(theta.inRadians)
        val m2 = m1.multiplyByRotation(theta)

        // Test for Euler rotation matrix
        // [cos(a) -sin(a)  0]
        // [sin(a)  cos(a)  0]
        // [  0       0     1]
        assertEquals(c, m1.m[0], 0.0, "m11")
        assertEquals(-s, m1.m[1], 0.0, "m12")
        assertEquals(0.0, m1.m[2], 0.0, "m13")
        assertEquals(s, m1.m[3], 0.0, "m21")
        assertEquals(c, m1.m[4], 0.0, "m22")
        assertEquals(0.0, m1.m[5], 0.0, "m23")
        assertEquals(0.0, m1.m[6], 0.0, "m31")
        assertEquals(0.0, m1.m[7], 0.0, "m32")
        assertEquals(1.0, m1.m[8], 0.0, "m33")
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testMultiplyByScale() {
        val m1 = Matrix3()
        val sx = 5.0
        val sy = 7.0
        val m2 = m1.multiplyByScale(sx, sy)

        // Test for scaling matrix form
        // [sx  0  0]
        // [0  sy  0]
        // [0   0  1]
        assertEquals(sx, m1.m[0], 0.0, "m11")
        assertEquals(0.0, m1.m[1], 0.0, "m12")
        assertEquals(0.0, m1.m[2], 0.0, "m13")
        assertEquals(0.0, m1.m[3], 0.0, "m21")
        assertEquals(sy, m1.m[4], 0.0, "m22")
        assertEquals(0.0, m1.m[5], 0.0, "m23")
        assertEquals(0.0, m1.m[6], 0.0, "m31")
        assertEquals(0.0, m1.m[7], 0.0, "m32")
        assertEquals(1.0, m1.m[8], 0.0, "m33")
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testMultiplyByVerticalFlip() {
        val m1 = Matrix3() // identity matrix
        val m2 = m1.multiplyByVerticalFlip()

        // Sets this matrix to one that reflects about the x-axis and translates the y-axis origin.
        // [1  0  0]
        // [0 -1  1] <-- *
        // [0  0  1]
        assertEquals(1.0, m1.m[0], 0.0, "m11")
        assertEquals(0.0, m1.m[1], 0.0, "m12")
        assertEquals(0.0, m1.m[2], 0.0, "m13")
        assertEquals(0.0, m1.m[3], 0.0, "m21")
        assertEquals(-1.0, m1.m[4], 0.0, "m22")
        assertEquals(1.0, m1.m[5], 0.0, "m23")
        assertEquals(0.0, m1.m[6], 0.0, "m31")
        assertEquals(0.0, m1.m[7], 0.0, "m32")
        assertEquals(1.0, m1.m[8], 0.0, "m33")
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testMultiplyByMatrix() {
        val m1 = Matrix3( // matrix under test
                11.0, 12.0, 13.0,
                21.0, 22.0, 23.0,
                31.0, 32.0, 33.0)
        val m2 = Matrix3( // multiplier
                11.0, 12.0, 13.0,
                21.0, 22.0, 23.0,
                31.0, 32.0, 33.0)
        val copy = Matrix3()
        copy.copy(m1) // copy of m1 before its mutated
        val m3 = m1.multiplyByMatrix(m2)

        // Test for result of a x b:
        //            1st Column                     2nd Column                     3rd Column
        // [ (a11*b11 + a12*b21 + a13*b31)  (a11*b12 + a12*b22 + a13*b32)  (a11*b13 + a12*b23 + a13*b33) ]
        // [ (a21*b11 + a22*b21 + a23*b31)  (a21*b12 + a22*b22 + a23*b32)  (a21*b13 + a22*b23 + a23*b33) ]
        // [ (a31*b11 + a32*b21 + a33*b31)  (a31*b12 + a32*b22 + a33*b32)  (a31*b13 + a32*b23 + a33*b33) ]
        //
        // 1st Column:
        assertEquals(copy.m[0] * m2.m[0] + copy.m[1] * m2.m[3] + copy.m[2] * m2.m[6], m1.m[0], 0.0, "m11")
        assertEquals(copy.m[3] * m2.m[0] + copy.m[4] * m2.m[3] + copy.m[5] * m2.m[6], m1.m[3], 0.0, "m21")
        assertEquals(copy.m[6] * m2.m[0] + copy.m[7] * m2.m[3] + copy.m[8] * m2.m[6], m1.m[6], 0.0, "m31")
        // 2nd Column:
        assertEquals(copy.m[0] * m2.m[1] + copy.m[1] * m2.m[4] + copy.m[2] * m2.m[7], m1.m[1], 0.0, "m12")
        assertEquals(copy.m[3] * m2.m[1] + copy.m[4] * m2.m[4] + copy.m[5] * m2.m[7], m1.m[4], 0.0, "m22")
        assertEquals(copy.m[6] * m2.m[1] + copy.m[7] * m2.m[4] + copy.m[8] * m2.m[7], m1.m[7], 0.0, "m23")
        // 3rd Column:
        assertEquals(copy.m[0] * m2.m[2] + copy.m[1] * m2.m[5] + copy.m[2] * m2.m[8], m1.m[2], 0.0, "m13")
        assertEquals(copy.m[3] * m2.m[2] + copy.m[4] * m2.m[5] + copy.m[5] * m2.m[8], m1.m[5], 0.0, "m32")
        assertEquals(copy.m[6] * m2.m[2] + copy.m[7] * m2.m[5] + copy.m[8] * m2.m[8], m1.m[8], 0.0, "m33")
        //
        assertSame(m3, m1, "fluent api result")
    }

    @Test
    fun testMultiplyByMatrix_Doubles() {
        // multipliers
        val m11 = 11.0
        val m12 = 12.0
        val m13 = 13.0
        val m21 = 21.0
        val m22 = 22.0
        val m23 = 23.0
        val m31 = 31.0
        val m32 = 32.0
        val m33 = 33.0
        // matrix under test
        val m1 = Matrix3( // matrix under test
                11.0, 12.0, 13.0,
                21.0, 22.0, 23.0,
                31.0, 32.0, 33.0)
        val copy = Matrix3()
        copy.copy(m1) // copy of m1 before its mutated
        val m3 = m1.multiplyByMatrix(
                m11, m12, m13,
                m21, m22, m23,
                m31, m32, m33)

        // Test for result of a x b:
        //            1st Column                     2nd Column                     3rd Column
        // [ (a11*b11 + a12*b21 + a13*b31)  (a11*b12 + a12*b22 + a13*b32)  (a11*b13 + a12*b23 + a13*b33) ]
        // [ (a21*b11 + a22*b21 + a23*b31)  (a21*b12 + a22*b22 + a23*b32)  (a21*b13 + a22*b23 + a23*b33) ]
        // [ (a31*b11 + a32*b21 + a33*b31)  (a31*b12 + a32*b22 + a33*b32)  (a31*b13 + a32*b23 + a33*b33) ]
        //
        // 1st Column:
        assertEquals(copy.m[0] * m11 + copy.m[1] * m21 + copy.m[2] * m31, m1.m[0], 0.0, "m11")
        assertEquals(copy.m[3] * m11 + copy.m[4] * m21 + copy.m[5] * m31, m1.m[3], 0.0, "m21")
        assertEquals(copy.m[6] * m11 + copy.m[7] * m21 + copy.m[8] * m31, m1.m[6], 0.0, "m31")
        // 2nd Column:
        assertEquals(copy.m[0] * m12 + copy.m[1] * m22 + copy.m[2] * m32, m1.m[1], 0.0, "m12")
        assertEquals(copy.m[3] * m12 + copy.m[4] * m22 + copy.m[5] * m32, m1.m[4], 0.0, "m22")
        assertEquals(copy.m[6] * m12 + copy.m[7] * m22 + copy.m[8] * m32, m1.m[7], 0.0, "m23")
        // 3rd Column:
        assertEquals(copy.m[0] * m13 + copy.m[1] * m23 + copy.m[2] * m33, m1.m[2], 0.0, "m13")
        assertEquals(copy.m[3] * m13 + copy.m[4] * m23 + copy.m[5] * m33, m1.m[5], 0.0, "m32")
        assertEquals(copy.m[6] * m13 + copy.m[7] * m23 + copy.m[8] * m33, m1.m[8], 0.0, "m33")
        //
        assertSame(m3, m1, "fluent api result")
    }

    @Test
    fun testTranspose() {
        val m11 = 11.0
        val m12 = 12.0
        val m13 = 13.0
        val m21 = 21.0
        val m22 = 22.0
        val m23 = 23.0
        val m31 = 31.0
        val m32 = 32.0
        val m33 = 33.0
        val m1 = Matrix3(m11, m12, m13, m21, m22, m23, m31, m32, m33) // matrix to be tested/transposed
        val m2 = m1.transpose()
        assertEquals(m11, m1.m[0], 0.0, "m11")
        assertEquals(m21, m1.m[1], 0.0, "m12")
        assertEquals(m31, m1.m[2], 0.0, "m13")
        assertEquals(m12, m1.m[3], 0.0, "m21")
        assertEquals(m22, m1.m[4], 0.0, "m22")
        assertEquals(m32, m1.m[5], 0.0, "m23")
        assertEquals(m13, m1.m[6], 0.0, "m31")
        assertEquals(m23, m1.m[7], 0.0, "m32")
        assertEquals(m33, m1.m[8], 0.0, "m33")
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testTransposeMatrix() {
        val m11 = 11.0
        val m12 = 12.0
        val m13 = 13.0
        val m21 = 21.0
        val m22 = 22.0
        val m23 = 23.0
        val m31 = 31.0
        val m32 = 32.0
        val m33 = 33.0
        val m1 = Matrix3() // matrix under test
        val m2 = Matrix3(m11, m12, m13, m21, m22, m23, m31, m32, m33) // matrix to be transposed
        val m3 = m1.transposeMatrix(m2)
        assertEquals(m11, m1.m[0], 0.0, "m11")
        assertEquals(m21, m1.m[1], 0.0, "m12")
        assertEquals(m31, m1.m[2], 0.0, "m13")
        assertEquals(m12, m1.m[3], 0.0, "m21")
        assertEquals(m22, m1.m[4], 0.0, "m22")
        assertEquals(m32, m1.m[5], 0.0, "m23")
        assertEquals(m13, m1.m[6], 0.0, "m31")
        assertEquals(m23, m1.m[7], 0.0, "m32")
        assertEquals(m33, m1.m[8], 0.0, "m33")
        assertSame(m3, m1, "fluent api result")
    }

    @Ignore // Invert is not implemented at time of test
    @Test
    fun testInvert() {
        val m1 = Matrix3( // matrix to be tested/inverted
                -4.0, -3.0, 3.0,
                0.0, 2.0, -2.0,
                1.0, 4.0, -1.0)
        val mOriginal = Matrix3(m1)
        val m2 = m1.invert()
        val mIdentity = Matrix3(m1).multiplyByMatrix(mOriginal)
        assertContentEquals(Matrix3.identity, mIdentity.m, "identity matrix array")
        assertSame(m2, m1, "fluent api result")
    }

    @Ignore // InvertMatrix was not implemented at time of test
    @Test
    fun testInvertMatrix() {
        val m1 = Matrix3()
        val m2 = Matrix3( // matrix to be inverted
                -4.0, -3.0, 3.0,
                0.0, 2.0, -2.0,
                1.0, 4.0, -1.0)
        //val det = computeDeterminant(m2)
        val mInv = m1.invertMatrix(m2)
        val mIdentity = mInv.multiplyByMatrix(m2)
        assertContentEquals(Matrix3.identity, mIdentity.m, "identity matrix array")
        assertSame(mInv, m1, "fluent api result")
    }

    @Test
    fun testTransposeToArray() {
        val m1 = Matrix3(11.0, 12.0, 13.0, 21.0, 22.0, 23.0, 31.0, 32.0, 33.0)
        val result = m1.transposeToArray(FloatArray(9), 0)
        val expected = m1.transpose().m
        for (i in 0..8) {
            assertEquals(expected[i], result[i].toDouble(), 0.0, i.toString())
        }
    }
}