package earth.worldwind.geom

import earth.worldwind.geom.Angle.Companion.degrees
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

/**
 * Unit tests for Matrix4, a 4x4 square matrix in row, column order.
 */
class Matrix4Test {
    companion object {
        const val M_11 = 11.0
        const val M_12 = 12.0
        const val M_13 = 13.0
        const val M_14 = 14.0
        const val M_21 = 21.0
        const val M_22 = 22.0
        const val M_23 = 23.0
        const val M_24 = 24.0
        const val M_31 = 31.0
        const val M_32 = 32.0
        const val M_33 = 33.0
        const val M_34 = 34.0
        const val M_41 = 41.0
        const val M_42 = 42.0
        const val M_43 = 43.0
        const val M_44 = 44.0
        const val TOLERANCE = 1e-10

        /////////////////////
        //  Helper methods
        /////////////////////
        private fun compute3x3Determinant(matrix: Matrix4): Double {
            val m11 = matrix.m[0]
            val m12 = matrix.m[1]
            val m13 = matrix.m[2]
            val m21 = matrix.m[4]
            val m22 = matrix.m[5]
            val m23 = matrix.m[6]
            val m31 = matrix.m[8]
            val m32 = matrix.m[9]
            val m33 = matrix.m[10]
            // |m11  m12  m13| m14
            // |m21  m22  m23| m24
            // |m31  m32  m33| m34
            //  m41  m42  m43  m44
            return (m11 * (m22 * m33 - m23 * m32)
                    + m12 * (m23 * m31 - m21 * m33)
                    + m13 * (m21 * m32 - m22 * m31))
        }

        private fun extract3x3Matrix(matrix: Matrix4): Matrix3 {
            val m11 = matrix.m[0]
            val m12 = matrix.m[1]
            val m13 = matrix.m[2]
            val m21 = matrix.m[4]
            val m22 = matrix.m[5]
            val m23 = matrix.m[6]
            val m31 = matrix.m[8]
            val m32 = matrix.m[9]
            val m33 = matrix.m[10]
            return Matrix3(m11, m12, m13, m21, m22, m23, m31, m32, m33)
        }

        private fun computeDeterminant(matrix: Matrix4): Double {
            val m11 = matrix.m[0]
            val m12 = matrix.m[1]
            val m13 = matrix.m[2]
            val m14 = matrix.m[3]
            val m21 = matrix.m[4]
            val m22 = matrix.m[5]
            val m23 = matrix.m[6]
            val m24 = matrix.m[7]
            val m31 = matrix.m[8]
            val m32 = matrix.m[9]
            val m33 = matrix.m[10]
            val m34 = matrix.m[11]
            val m41 = matrix.m[12]
            val m42 = matrix.m[13]
            val m43 = matrix.m[14]
            val m44 = matrix.m[15]
            // |m11  m12  m13  m14|
            // |m21  m22  m23  m24|
            // |m31  m32  m33  m34| =
            // |m41  m42  m43  m44|
            //
            //        |m22  m23  m24|         |m21  m23  m24|          |m21  m22  m24|         |m21  m22  m23|
            //  m11 * |m32  m33  m34| - m12 * |m31  m33  m34| +  m13 * |m31  m32  m34| - m14 * |m31  m32  m33|
            //        |m42  m43  m44|         |m41  m43  m44|          |m41  m42  m44|         |m41  m42  m43|
            //
            return (m11 * (m22 * (m33 * m44 - m34 * m43) + m23 * (m34 * m42 - m32 * m44) + m24 * (m32 * m43 - m33 * m42))
                    - m12 * (m21 * (m33 * m44 - m34 * m43) + m23 * (m34 * m41 - m31 * m44) + m24 * (m31 * m43 - m33 * m41))
                    + m13 * (m21 * (m32 * m44 - m34 * m42) + m22 * (m34 * m41 - m31 * m44) + m24 * (m31 * m42 - m32 * m41))
                    - m14 * (m21 * (m32 * m43 - m33 * m42) + m22 * (m33 * m41 - m31 * m43) + m23 * (m31 * m42 - m32 * m41)))
        }
    }

    @Test
    fun testConstructor_Default() {
        val m1 = Matrix4()
        assertNotNull(m1, "matrix not null")
        assertContentEquals(Matrix4.identity, m1.m, "identity matrix")
    }

    @Test
    fun testConstructor_Copy() {
        val original = Matrix4(
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, M_44)
        val copy = Matrix4(original) // matrix under test
        assertNotNull(copy, "matrix not null")
        assertContentEquals(original.m, copy.m, "copy array equals original")
        assertEquals(original, copy, "copy equals original")
    }

    @Test
    fun testConstructor_Doubles() {
        val m1 = Matrix4(
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, M_44)
        assertNotNull(m1, "matrix not null")
        assertEquals(M_11, m1.m[0], 0.0, "m11")
        assertEquals(M_12, m1.m[1], 0.0, "m12")
        assertEquals(M_13, m1.m[2], 0.0, "m13")
        assertEquals(M_14, m1.m[3], 0.0, "m14")
        assertEquals(M_21, m1.m[4], 0.0, "m21")
        assertEquals(M_22, m1.m[5], 0.0, "m22")
        assertEquals(M_23, m1.m[6], 0.0, "m23")
        assertEquals(M_24, m1.m[7], 0.0, "m24")
        assertEquals(M_31, m1.m[8], 0.0, "m31")
        assertEquals(M_32, m1.m[9], 0.0, "m32")
        assertEquals(M_33, m1.m[10], 0.0, "m33")
        assertEquals(M_34, m1.m[11], 0.0, "m34")
        assertEquals(M_41, m1.m[12], 0.0, "m41")
        assertEquals(M_42, m1.m[13], 0.0, "m42")
        assertEquals(M_43, m1.m[14], 0.0, "m43")
        assertEquals(M_44, m1.m[15], 0.0, "m44")
    }

    @Test
    fun testEquals() {
        val m1 = Matrix4(
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, M_44)
        val m2 = Matrix4( // identical
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, M_44)
        assertEquals(m1, m1, "self")
        assertContentEquals(m2.m, m1.m, "identical array")
        assertEquals(m2, m1, "identical matrix")
    }

    @Test
    fun testHashCode() {
        val x44 = -44.0 // different value
        val m1 = Matrix4(
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, M_44)
        val m2 = Matrix4(
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, x44)
        val hashCode1 = m1.hashCode()
        val hashCode2 = m2.hashCode()
        assertNotEquals(hashCode1, hashCode2, "hash codes")
    }

    @Test
    fun testSet() {
        val m1 = Matrix4() // matrix under test
        val m2 = m1.set(
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, M_44)
        assertEquals(M_11, m1.m[0], 0.0, "m11")
        assertEquals(M_12, m1.m[1], 0.0, "m12")
        assertEquals(M_13, m1.m[2], 0.0, "m13")
        assertEquals(M_14, m1.m[3], 0.0, "m14")
        assertEquals(M_21, m1.m[4], 0.0, "m21")
        assertEquals(M_22, m1.m[5], 0.0, "m22")
        assertEquals(M_23, m1.m[6], 0.0, "m23")
        assertEquals(M_24, m1.m[7], 0.0, "m24")
        assertEquals(M_31, m1.m[8], 0.0, "m31")
        assertEquals(M_32, m1.m[9], 0.0, "m32")
        assertEquals(M_33, m1.m[10], 0.0, "m33")
        assertEquals(M_34, m1.m[11], 0.0, "m34")
        assertEquals(M_41, m1.m[12], 0.0, "m41")
        assertEquals(M_42, m1.m[13], 0.0, "m42")
        assertEquals(M_43, m1.m[14], 0.0, "m43")
        assertEquals(M_44, m1.m[15], 0.0, "m44")
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testSet_FromMatrix() {
        val m1 = Matrix4() // matrix under test
        val m2 = Matrix4(
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, M_44)
        val m2Copy = Matrix4(m2)
        val m3 = m1.copy(m2)
        assertContentEquals(m2.m, m1.m, "identical array")
        assertEquals(m2, m1, "identical matrix")
        assertEquals(m2, m2Copy, "matrix not mutated")
        assertSame(m3, m1, "fluent api result")
    }

    @Test
    fun testSetTranslation() {
        val dx = 3.0
        val dy = 5.0
        val dz = 7.0
        val m1 = Matrix4( // matrix under test
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, M_44)
        val m2 = m1.setTranslation(dx, dy, dz)

        // Test for proper translation matrix
        // [m11  m12  m13  dx ]
        // [m21  m22  m23  dy ]
        // [m31  m32  m33  dz ]
        // [m41  m42  m43  m44]
        //
        // row 1
        assertEquals(M_11, m1.m[0], 0.0, "m11")
        assertEquals(M_12, m1.m[1], 0.0, "m12")
        assertEquals(M_13, m1.m[2], 0.0, "m13")
        assertEquals(dx, m1.m[3], 0.0, "m14")
        // row 2
        assertEquals(M_21, m1.m[4], 0.0, "m21")
        assertEquals(M_22, m1.m[5], 0.0, "m22")
        assertEquals(M_23, m1.m[6], 0.0, "m23")
        assertEquals(dy, m1.m[7], 0.0, "m24")
        // row 3
        assertEquals(M_31, m1.m[8], 0.0, "m31")
        assertEquals(M_32, m1.m[9], 0.0, "m32")
        assertEquals(M_33, m1.m[10], 0.0, "m33")
        assertEquals(dz, m1.m[11], 0.0, "m34")
        // row 4
        assertEquals(M_41, m1.m[12], 0.0, "m41")
        assertEquals(M_42, m1.m[13], 0.0, "m42")
        assertEquals(M_43, m1.m[14], 0.0, "m43")
        assertEquals(M_44, m1.m[15], 0.0, "m44")
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testSetRotation() {
        val m1 = Matrix4()
        val u = Vec3(0.0, 1.0, 0.0) // unit vector on y axis

        // Set the rotation matrix three times,
        // each time we'll rotate a unit vector
        // used to validate the values in the matrix.
        // Cumulatively, we'll end up rotating the
        // unit vector -90deg cw around z.
        m1.setRotation(1.0, 0.0, 0.0, 30.0.degrees) // rotate 30deg ccw around x
        u.multiplyByMatrix(m1)
        m1.setRotation(0.0, 1.0, 0.0, 90.0.degrees) // rotate 90deg ccw around y
        u.multiplyByMatrix(m1)
        m1.setRotation(0.0, 0.0, 1.0, (-60.0).degrees) // rotate -60deg cw around z
        u.multiplyByMatrix(m1)

        // We should have a unit vector on the x axis
        assertEquals(1.0, u.x, TOLERANCE, "u.x")
        assertEquals(0.0, u.y, TOLERANCE, "u.y")
        assertEquals(0.0, u.z, TOLERANCE, "u.z")
    }

    @Test
    fun testSetScale() {
        val sx = 3.0
        val sy = 5.0
        val sz = 7.0
        val m1 = Matrix4( // matrix under test
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, M_44)
        val m2 = m1.setScale(sx, sy, sz)

        // Test for proper scale matrix
        // [sx   m12  m13  m14]
        // [m21  sy   m23  m24]
        // [m31  m32  sz   m34]
        // [m41  m42  m43  m44]
        //
        // row 1
        assertEquals(sx, m1.m[0], 0.0, "m11")
        assertEquals(M_12, m1.m[1], 0.0, "m12")
        assertEquals(M_13, m1.m[2], 0.0, "m13")
        assertEquals(M_14, m1.m[3], 0.0, "m14")
        // row 2
        assertEquals(M_21, m1.m[4], 0.0, "m21")
        assertEquals(sy, m1.m[5], 0.0, "m22")
        assertEquals(M_23, m1.m[6], 0.0, "m23")
        assertEquals(M_24, m1.m[7], 0.0, "m24")
        // row 3
        assertEquals(M_31, m1.m[8], 0.0, "m31")
        assertEquals(M_32, m1.m[9], 0.0, "m32")
        assertEquals(sz, m1.m[10], 0.0, "m33")
        assertEquals(M_34, m1.m[11], 0.0, "m34")
        // row 4
        assertEquals(M_41, m1.m[12], 0.0, "m41")
        assertEquals(M_42, m1.m[13], 0.0, "m42")
        assertEquals(M_43, m1.m[14], 0.0, "m43")
        assertEquals(M_44, m1.m[15], 0.0, "m44")
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testSetToIdentity() {
        val m1 = Matrix4( // matrix under test
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, M_44)
        val m2 = m1.setToIdentity()
        assertContentEquals(Matrix4.identity, m1.m, "identity matrix array")
        assertSame(m2, m1)
    }

    @Test
    fun testSetToTranslation() {
        val dx = 3.0
        val dy = 5.0
        val dz = 7.0
        val m1 = Matrix4( // matrix under test
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, M_44)
        val m2 = m1.setToTranslation(dx, dy, dz)

        // Test for translation matrix form
        // [1  0  0  dx]
        // [0  1  0  dy]
        // [0  0  1  dz]
        // [0  0  0  1 ]
        //
        // row 1
        assertEquals(1.0, m1.m[0], 0.0, "m11")
        assertEquals(0.0, m1.m[1], 0.0, "m12")
        assertEquals(0.0, m1.m[2], 0.0, "m13")
        assertEquals(dx, m1.m[3], 0.0, "m14")
        // row 2
        assertEquals(0.0, m1.m[4], 0.0, "m21")
        assertEquals(1.0, m1.m[5], 0.0, "m22")
        assertEquals(0.0, m1.m[6], 0.0, "m23")
        assertEquals(dy, m1.m[7], 0.0, "m24")
        // row 3
        assertEquals(0.0, m1.m[8], 0.0, "m31")
        assertEquals(0.0, m1.m[9], 0.0, "m32")
        assertEquals(1.0, m1.m[10], 0.0, "m33")
        assertEquals(dz, m1.m[11], 0.0, "m34")
        // row 4
        assertEquals(0.0, m1.m[12], 0.0, "m41")
        assertEquals(0.0, m1.m[13], 0.0, "m42")
        assertEquals(0.0, m1.m[14], 0.0, "m43")
        assertEquals(1.0, m1.m[15], 0.0, "m44")
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testSetToRotation() {
        val mx = Matrix4()
        val my = Matrix4()
        val mz = Matrix4()
        mx.setToRotation(1.0, 0.0, 0.0, 30.0.degrees) // rotate ccw around x
        my.setToRotation(0.0, 1.0, 0.0, 90.0.degrees) // rotate ccw around y
        mz.setToRotation(0.0, 0.0, 1.0, (-60.0).degrees) // rotate cw around z

        // Rotate a unit vector from 0,1,0 to 1,0,0
        val u = Vec3(0.0, 1.0, 0.0)
        u.multiplyByMatrix(mx)
        u.multiplyByMatrix(my)
        u.multiplyByMatrix(mz)
        assertEquals(1.0, u.x, TOLERANCE, "u.x")
        assertEquals(0.0, u.y, TOLERANCE, "u.y")
        assertEquals(0.0, u.z, TOLERANCE, "u.z")
    }

    @Test
    fun testSetToScale() {
        val sx = 3.0
        val sy = 5.0
        val sz = 7.0
        val m1 = Matrix4( // matrix under test
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, M_44
        )
        val m2 = m1.setToScale(sx, sy, sz)

        // Test for scale matrix form
        // [sx 0  0  0]
        // [0  sy 0  0]
        // [0  0  sz 0]
        // [0  0  0  1]
        // row 1
        assertEquals(sx, m1.m[0], 0.0, "m11")
        assertEquals(0.0, m1.m[1], 0.0, "m12")
        assertEquals(0.0, m1.m[2], 0.0, "m13")
        assertEquals(0.0, m1.m[3], 0.0, "m14")
        // row 2
        assertEquals(0.0, m1.m[4], 0.0, "m21")
        assertEquals(sy, m1.m[5], 0.0, "m22")
        assertEquals(0.0, m1.m[6], 0.0, "m23")
        assertEquals(0.0, m1.m[7], 0.0, "m24")
        // row 3
        assertEquals(0.0, m1.m[8], 0.0, "m31")
        assertEquals(0.0, m1.m[9], 0.0, "m32")
        assertEquals(sz, m1.m[10], 0.0, "m33")
        assertEquals(0.0, m1.m[11], 0.0, "m34")
        // row 4
        assertEquals(0.0, m1.m[12], 0.0, "m41")
        assertEquals(0.0, m1.m[13], 0.0, "m42")
        assertEquals(0.0, m1.m[14], 0.0, "m43")
        assertEquals(1.0, m1.m[15], 0.0, "m44")
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testSetToMultiply() {
        val m1 = Matrix4() // matrix under test
        val a = Matrix4(
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, M_44)
        val b = Matrix4(
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, M_44)
        val aCopy = Matrix4(a)
        val bCopy = Matrix4(b)
        val m2 = m1.setToMultiply(a, b)

        // Test for result of a x b:
        //                 1st Column                                2nd Column                               3rd Column                               4th Column
        // [ (a11*b11 + a12*b21 + a13*b31 + a14*b41)  (a11*b12 + a12*b22 + a13*b32 + a14*b42)  (a11*b13 + a12*b23 + a13*b33 + a14*b43)  (a11*b14 + a12*b24 + a13*b34 + a14*b44) ]
        // [ (a21*b11 + a22*b21 + a23*b31 + a24*b41)  (a21*b12 + a22*b22 + a23*b32 + a24*b42)  (a21*b13 + a22*b23 + a23*b33 + a24*b43)  (a21*b14 + a22*b24 + a23*b34 + a24*b44) ]
        // [ (a31*b11 + a32*b21 + a33*b31 + a34*b41)  (a31*b12 + a32*b22 + a33*b32 + a34*b42)  (a31*b13 + a32*b23 + a33*b33 + a34*b43)  (a31*b14 + a32*b24 + a33*b34 + a34*b44) ]
        // [ (a41*b11 + a42*b21 + a43*b31 + a44*b41)  (a41*b12 + a42*b22 + a43*b32 + a44*b42)  (a41*b13 + a42*b23 + a43*b33 + a44*b43)  (a41*b14 + a42*b24 + a43*b34 + a44*b44) ]
        //
        // 1st Column:
        assertEquals(a.m[0] * b.m[0] + a.m[1] * b.m[4] + a.m[2] * b.m[8] + a.m[3] * b.m[12], m1.m[0], 0.0, "m11")
        assertEquals(a.m[4] * b.m[0] + a.m[5] * b.m[4] + a.m[6] * b.m[8] + a.m[7] * b.m[12], m1.m[4], 0.0, "m21")
        assertEquals(a.m[8] * b.m[0] + a.m[9] * b.m[4] + a.m[10] * b.m[8] + a.m[11] * b.m[12], m1.m[8], 0.0, "m31")
        assertEquals(a.m[12] * b.m[0] + a.m[13] * b.m[4] + a.m[14] * b.m[8] + a.m[15] * b.m[12], m1.m[12], 0.0, "m41")
        // 2nd Column:
        assertEquals(a.m[0] * b.m[1] + a.m[1] * b.m[5] + a.m[2] * b.m[9] + a.m[3] * b.m[13], m1.m[1], 0.0, "m12")
        assertEquals(a.m[4] * b.m[1] + a.m[5] * b.m[5] + a.m[6] * b.m[9] + a.m[7] * b.m[13], m1.m[5], 0.0, "m22")
        assertEquals(a.m[8] * b.m[1] + a.m[9] * b.m[5] + a.m[10] * b.m[9] + a.m[11] * b.m[13], m1.m[9], 0.0, "m32")
        assertEquals(a.m[12] * b.m[1] + a.m[13] * b.m[5] + a.m[14] * b.m[9] + a.m[15] * b.m[13], m1.m[13], 0.0, "m42")
        // 3rd Column:
        assertEquals(a.m[0] * b.m[2] + a.m[1] * b.m[6] + a.m[2] * b.m[10] + a.m[3] * b.m[14], m1.m[2], 0.0, "m13")
        assertEquals(a.m[4] * b.m[2] + a.m[5] * b.m[6] + a.m[6] * b.m[10] + a.m[7] * b.m[14], m1.m[6], 0.0, "m23")
        assertEquals(a.m[8] * b.m[2] + a.m[9] * b.m[6] + a.m[10] * b.m[10] + a.m[11] * b.m[14], m1.m[10], 0.0, "m33")
        assertEquals(a.m[12] * b.m[2] + a.m[13] * b.m[6] + a.m[14] * b.m[10] + a.m[15] * b.m[14], m1.m[14], 0.0, "m43")
        // 4th Column:
        assertEquals(a.m[0] * b.m[3] + a.m[1] * b.m[7] + a.m[2] * b.m[11] + a.m[3] * b.m[15], m1.m[3], 0.0, "m14")
        assertEquals(a.m[4] * b.m[3] + a.m[5] * b.m[7] + a.m[6] * b.m[11] + a.m[7] * b.m[15], m1.m[7], 0.0, "m24")
        assertEquals(a.m[8] * b.m[3] + a.m[9] * b.m[7] + a.m[10] * b.m[11] + a.m[11] * b.m[15], m1.m[11], 0.0, "m34")
        assertEquals(a.m[12] * b.m[3] + a.m[13] * b.m[7] + a.m[14] * b.m[11] + a.m[15] * b.m[15], m1.m[15], 0.0, "m44")
        //
        assertSame(m2, m1, "fluent api result")
        assertEquals(aCopy, a, "a not mutated")
        assertEquals(bCopy, b, "b not mutated")
    }

    @Test
    fun testMultiplyByTranslation() {
        val m1 = Matrix4() // identity matrix
        val dx = 3.0
        val dy = 5.0
        val dz = 7.0
        val m2 = m1.multiplyByTranslation(dx, dy, dz)

        // Test for translation matrix form
        // [1 0 0 x]
        // [0 1 0 y]
        // [0 0 1 z]
        // [0 0 0 1]
        assertEquals(1.0, m1.m[0], 0.0, "m11")
        assertEquals(0.0, m1.m[1], 0.0, "m12")
        assertEquals(0.0, m1.m[2], 0.0, "m13")
        assertEquals(dx, m1.m[3], 0.0, "m14")
        assertEquals(0.0, m1.m[4], 0.0, "m21")
        assertEquals(1.0, m1.m[5], 0.0, "m22")
        assertEquals(0.0, m1.m[6], 0.0, "m23")
        assertEquals(dy, m1.m[7], 0.0, "m24")
        assertEquals(0.0, m1.m[8], 0.0, "m31")
        assertEquals(0.0, m1.m[9], 0.0, "m32")
        assertEquals(1.0, m1.m[10], 0.0, "m33")
        assertEquals(dz, m1.m[11], 0.0, "m34")
        assertEquals(0.0, m1.m[12], 0.0, "m41")
        assertEquals(0.0, m1.m[13], 0.0, "m42")
        assertEquals(0.0, m1.m[14], 0.0, "m43")
        assertEquals(1.0, m1.m[15], 0.0, "m44")
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testMultiplyByRotation() {
        // Rotate a unit vectors
        val r = Vec3(0.0, 0.0, 1.0)
        val m = Matrix4()
        m.multiplyByRotation(1.0, 0.0, 0.0, 30.0.degrees) // rotate ccw around x
        m.multiplyByRotation(0.0, 1.0, 0.0, 90.0.degrees) // rotate ccw around y
        m.multiplyByRotation(0.0, 0.0, 1.0, (-60.0).degrees) // rotate cw around z
        r.multiplyByMatrix(m)
        assertEquals(1.0, r.x, TOLERANCE, "u.x")
        assertEquals(0.0, r.y, TOLERANCE, "u.y")
        assertEquals(0.0, r.z, TOLERANCE, "u.z")
        val theta = 30.0.degrees // rotation angle
        val c = cos(theta.radians)
        val s = sin(theta.radians)
        val m1 = Matrix4().multiplyByRotation(1.0, 0.0, 0.0, theta)
        val m2 = Matrix4().multiplyByRotation(0.0, 1.0, 0.0, theta)
        val m3 = Matrix4().multiplyByRotation(0.0, 0.0, 1.0, theta)
        // X
        // [  1       0       0     0]
        // [  0     cos(a) -sin(a)  0]
        // [  0     sin(a)  cos(a)  0]
        // [  0       0     0       1]
        assertEquals(1.0, m1.m[0], TOLERANCE, "m11")
        assertEquals(0.0, m1.m[1], TOLERANCE, "m12")
        assertEquals(0.0, m1.m[2], TOLERANCE, "m13")
        assertEquals(0.0, m1.m[3], TOLERANCE, "m14")
        assertEquals(0.0, m1.m[4], TOLERANCE, "m21")
        assertEquals(c, m1.m[5], TOLERANCE, "m22")
        assertEquals(-s, m1.m[6], TOLERANCE, "m23")
        assertEquals(0.0, m1.m[7], TOLERANCE, "m24")
        assertEquals(0.0, m1.m[8], TOLERANCE, "m31")
        assertEquals(s, m1.m[9], TOLERANCE, "m32")
        assertEquals(c, m1.m[10], TOLERANCE, "m33")
        assertEquals(0.0, m1.m[11], TOLERANCE, "m44")
        assertEquals(0.0, m1.m[12], TOLERANCE, "m41")
        assertEquals(0.0, m1.m[13], TOLERANCE, "m42")
        assertEquals(0.0, m1.m[14], TOLERANCE, "m43")
        assertEquals(1.0, m1.m[15], TOLERANCE, "m44")

        // Y
        // [ cos(a)   0   sin(a) 0]
        // [  0       1     0    0]
        // [-sin(a)   0  cos(a)  0]
        // [  0       0     0    1]
        assertEquals(c, m2.m[0], TOLERANCE, "m11")
        assertEquals(0.0, m2.m[1], TOLERANCE, "m12")
        assertEquals(s, m2.m[2], TOLERANCE, "m13")
        assertEquals(0.0, m2.m[3], TOLERANCE, "m14")
        assertEquals(0.0, m2.m[4], TOLERANCE, "m21")
        assertEquals(1.0, m2.m[5], TOLERANCE, "m22")
        assertEquals(0.0, m2.m[6], TOLERANCE, "m23")
        assertEquals(0.0, m2.m[7], TOLERANCE, "m24")
        assertEquals(-s, m2.m[8], TOLERANCE, "m31")
        assertEquals(0.0, m2.m[9], TOLERANCE, "m32")
        assertEquals(c, m2.m[10], TOLERANCE, "m33")
        assertEquals(0.0, m2.m[11], TOLERANCE, "m44")
        assertEquals(0.0, m2.m[12], TOLERANCE, "m41")
        assertEquals(0.0, m2.m[13], TOLERANCE, "m42")
        assertEquals(0.0, m2.m[14], TOLERANCE, "m43")
        assertEquals(1.0, m2.m[15], TOLERANCE, "m44")

        // Z
        // [cos(a) -sin(a)  0    0]
        // [sin(a)  cos(a)  0    0]
        // [  0       0     1    0]
        // [  0       0     0    1]
        assertEquals(c, m3.m[0], TOLERANCE, "m11")
        assertEquals(-s, m3.m[1], TOLERANCE, "m12")
        assertEquals(0.0, m3.m[2], TOLERANCE, "m13")
        assertEquals(0.0, m3.m[3], TOLERANCE, "m14")
        assertEquals(s, m3.m[4], TOLERANCE, "m21")
        assertEquals(c, m3.m[5], TOLERANCE, "m22")
        assertEquals(0.0, m3.m[6], TOLERANCE, "m23")
        assertEquals(0.0, m3.m[7], TOLERANCE, "m24")
        assertEquals(0.0, m3.m[8], TOLERANCE, "m31")
        assertEquals(0.0, m3.m[9], TOLERANCE, "m32")
        assertEquals(1.0, m3.m[10], TOLERANCE, "m33")
        assertEquals(0.0, m3.m[11], TOLERANCE, "m44")
        assertEquals(0.0, m3.m[12], TOLERANCE, "m41")
        assertEquals(0.0, m3.m[13], TOLERANCE, "m42")
        assertEquals(0.0, m3.m[14], TOLERANCE, "m43")
        assertEquals(1.0, m3.m[15], TOLERANCE, "m44")
    }

    @Test
    fun testMultiplyByScale() {
        val m1 = Matrix4() // identity matrix
        val sx = 3.0
        val sy = 5.0
        val sz = 7.0
        val m2 = m1.multiplyByScale(sx, sy, sz)

        // Test for scale matrix form
        // [sx 0  0  0]
        // [0  sy 0  0]
        // [0  0  sz 0]
        // [0  0  0  1]
        // row 1
        assertEquals(sx, m1.m[0], 0.0, "m11")
        assertEquals(0.0, m1.m[1], 0.0, "m12")
        assertEquals(0.0, m1.m[2], 0.0, "m13")
        assertEquals(0.0, m1.m[3], 0.0, "m14")
        // row 2
        assertEquals(0.0, m1.m[4], 0.0, "m21")
        assertEquals(sy, m1.m[5], 0.0, "m22")
        assertEquals(0.0, m1.m[6], 0.0, "m23")
        assertEquals(0.0, m1.m[7], 0.0, "m24")
        // row 3
        assertEquals(0.0, m1.m[8], 0.0, "m31")
        assertEquals(0.0, m1.m[9], 0.0, "m32")
        assertEquals(sz, m1.m[10], 0.0, "m33")
        assertEquals(0.0, m1.m[11], 0.0, "m34")
        // row 4
        assertEquals(0.0, m1.m[12], 0.0, "m41")
        assertEquals(0.0, m1.m[13], 0.0, "m42")
        assertEquals(0.0, m1.m[14], 0.0, "m43")
        assertEquals(1.0, m1.m[15], 0.0, "m44")
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testMultiplyByMatrix() {
        val m1 = Matrix4( // matrix under test
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, M_44)
        val m2 = Matrix4(
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, M_44)
        val m1Copy = Matrix4(m1)
        val m2Copy = Matrix4(m2)
        val a = m1Copy.m
        val b = m2.m
        val m3 = m1.multiplyByMatrix(m2)

        // Test for result of a x b:
        //                 1st Column                                2nd Column                               3rd Column                               4th Column
        // [ (a11*b11 + a12*b21 + a13*b31 + a14*b41)  (a11*b12 + a12*b22 + a13*b32 + a14*b42)  (a11*b13 + a12*b23 + a13*b33 + a14*b43)  (a11*b14 + a12*b24 + a13*b34 + a14*b44) ]
        // [ (a21*b11 + a22*b21 + a23*b31 + a24*b41)  (a21*b12 + a22*b22 + a23*b32 + a24*b42)  (a21*b13 + a22*b23 + a23*b33 + a24*b43)  (a21*b14 + a22*b24 + a23*b34 + a24*b44) ]
        // [ (a31*b11 + a32*b21 + a33*b31 + a34*b41)  (a31*b12 + a32*b22 + a33*b32 + a34*b42)  (a31*b13 + a32*b23 + a33*b33 + a34*b43)  (a31*b14 + a32*b24 + a33*b34 + a34*b44) ]
        // [ (a41*b11 + a42*b21 + a43*b31 + a44*b41)  (a41*b12 + a42*b22 + a43*b32 + a44*b42)  (a41*b13 + a42*b23 + a43*b33 + a44*b43)  (a41*b14 + a42*b24 + a43*b34 + a44*b44) ]
        //
        // 1st Column:
        assertEquals(a[0] * b[0] + a[1] * b[4] + a[2] * b[8] + a[3] * b[12], m1.m[0], 0.0, "m11")
        assertEquals(a[4] * b[0] + a[5] * b[4] + a[6] * b[8] + a[7] * b[12], m1.m[4], 0.0, "m21")
        assertEquals(a[8] * b[0] + a[9] * b[4] + a[10] * b[8] + a[11] * b[12], m1.m[8], 0.0, "m31")
        assertEquals(a[12] * b[0] + a[13] * b[4] + a[14] * b[8] + a[15] * b[12], m1.m[12], 0.0, "m41")
        // 2nd Column:
        assertEquals(a[0] * b[1] + a[1] * b[5] + a[2] * b[9] + a[3] * b[13], m1.m[1], 0.0, "m12")
        assertEquals(a[4] * b[1] + a[5] * b[5] + a[6] * b[9] + a[7] * b[13], m1.m[5], 0.0, "m22")
        assertEquals(a[8] * b[1] + a[9] * b[5] + a[10] * b[9] + a[11] * b[13], m1.m[9], 0.0, "m32")
        assertEquals(a[12] * b[1] + a[13] * b[5] + a[14] * b[9] + a[15] * b[13], m1.m[13], 0.0, "m42")
        // 3rd Column:
        assertEquals(a[0] * b[2] + a[1] * b[6] + a[2] * b[10] + a[3] * b[14], m1.m[2], 0.0, "m13")
        assertEquals(a[4] * b[2] + a[5] * b[6] + a[6] * b[10] + a[7] * b[14], m1.m[6], 0.0, "m23")
        assertEquals(a[8] * b[2] + a[9] * b[6] + a[10] * b[10] + a[11] * b[14], m1.m[10], 0.0, "m33")
        assertEquals(a[12] * b[2] + a[13] * b[6] + a[14] * b[10] + a[15] * b[14], m1.m[14], 0.0, "m43")
        // 4th Column:
        assertEquals(a[0] * b[3] + a[1] * b[7] + a[2] * b[11] + a[3] * b[15], m1.m[3], 0.0, "m14")
        assertEquals(a[4] * b[3] + a[5] * b[7] + a[6] * b[11] + a[7] * b[15], m1.m[7], 0.0, "m24")
        assertEquals(a[8] * b[3] + a[9] * b[7] + a[10] * b[11] + a[11] * b[15], m1.m[11], 0.0, "m34")
        assertEquals(a[12] * b[3] + a[13] * b[7] + a[14] * b[11] + a[15] * b[15], m1.m[15], 0.0, "m44")
        //
        assertEquals(m2Copy, m2, "not mutated")
        assertSame(m3, m1, "fluent api result")
    }

    @Test
    fun testMultiplyByMatrix_Doubles() {
        val m1 = Matrix4( // matrix under test
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, M_44)
        val m1Copy = Matrix4(m1)
        val a = m1Copy.m
        val b = m1Copy.m
        val m2 = m1.multiplyByMatrix(
                b[0], b[1], b[2], b[3],
                b[4], b[5], b[6], b[7],
                b[8], b[9], b[10], b[11],
                b[12], b[13], b[14], b[15])

        // Test for result of a x b:
        //                 1st Column                                2nd Column                               3rd Column                               4th Column
        // [ (a11*b11 + a12*b21 + a13*b31 + a14*b41)  (a11*b12 + a12*b22 + a13*b32 + a14*b42)  (a11*b13 + a12*b23 + a13*b33 + a14*b43)  (a11*b14 + a12*b24 + a13*b34 + a14*b44) ]
        // [ (a21*b11 + a22*b21 + a23*b31 + a24*b41)  (a21*b12 + a22*b22 + a23*b32 + a24*b42)  (a21*b13 + a22*b23 + a23*b33 + a24*b43)  (a21*b14 + a22*b24 + a23*b34 + a24*b44) ]
        // [ (a31*b11 + a32*b21 + a33*b31 + a34*b41)  (a31*b12 + a32*b22 + a33*b32 + a34*b42)  (a31*b13 + a32*b23 + a33*b33 + a34*b43)  (a31*b14 + a32*b24 + a33*b34 + a34*b44) ]
        // [ (a41*b11 + a42*b21 + a43*b31 + a44*b41)  (a41*b12 + a42*b22 + a43*b32 + a44*b42)  (a41*b13 + a42*b23 + a43*b33 + a44*b43)  (a41*b14 + a42*b24 + a43*b34 + a44*b44) ]
        //
        // 1st Column:
        assertEquals(a[0] * b[0] + a[1] * b[4] + a[2] * b[8] + a[3] * b[12], m1.m[0], 0.0, "m11")
        assertEquals(a[4] * b[0] + a[5] * b[4] + a[6] * b[8] + a[7] * b[12], m1.m[4], 0.0, "m21")
        assertEquals(a[8] * b[0] + a[9] * b[4] + a[10] * b[8] + a[11] * b[12], m1.m[8], 0.0, "m31")
        assertEquals(a[12] * b[0] + a[13] * b[4] + a[14] * b[8] + a[15] * b[12], m1.m[12], 0.0, "m41")
        // 2nd Column:
        assertEquals(a[0] * b[1] + a[1] * b[5] + a[2] * b[9] + a[3] * b[13], m1.m[1], 0.0, "m12")
        assertEquals(a[4] * b[1] + a[5] * b[5] + a[6] * b[9] + a[7] * b[13], m1.m[5], 0.0, "m22")
        assertEquals(a[8] * b[1] + a[9] * b[5] + a[10] * b[9] + a[11] * b[13], m1.m[9], 0.0, "m32")
        assertEquals(a[12] * b[1] + a[13] * b[5] + a[14] * b[9] + a[15] * b[13], m1.m[13], 0.0, "m42")
        // 3rd Column:
        assertEquals(a[0] * b[2] + a[1] * b[6] + a[2] * b[10] + a[3] * b[14], m1.m[2], 0.0, "m13")
        assertEquals(a[4] * b[2] + a[5] * b[6] + a[6] * b[10] + a[7] * b[14], m1.m[6], 0.0, "m23")
        assertEquals(a[8] * b[2] + a[9] * b[6] + a[10] * b[10] + a[11] * b[14], m1.m[10], 0.0, "m33")
        assertEquals(a[12] * b[2] + a[13] * b[6] + a[14] * b[10] + a[15] * b[14], m1.m[14], 0.0, "m43")
        // 4th Column:
        assertEquals(a[0] * b[3] + a[1] * b[7] + a[2] * b[11] + a[3] * b[15], m1.m[3], 0.0, "m14")
        assertEquals(a[4] * b[3] + a[5] * b[7] + a[6] * b[11] + a[7] * b[15], m1.m[7], 0.0, "m24")
        assertEquals(a[8] * b[3] + a[9] * b[7] + a[10] * b[11] + a[11] * b[15], m1.m[11], 0.0, "m34")
        assertEquals(a[12] * b[3] + a[13] * b[7] + a[14] * b[11] + a[15] * b[15], m1.m[15], 0.0, "m44")
        //
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testTranspose() {
        val m1 = Matrix4(
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, M_44)
        val m2 = m1.transpose()

        // row 1
        assertEquals(M_11, m1.m[0], 0.0, "m11")
        assertEquals(M_21, m1.m[1], 0.0, "m12")
        assertEquals(M_31, m1.m[2], 0.0, "m13")
        assertEquals(M_41, m1.m[3], 0.0, "m14")
        // row 2
        assertEquals(M_12, m1.m[4], 0.0, "m21")
        assertEquals(M_22, m1.m[5], 0.0, "m22")
        assertEquals(M_32, m1.m[6], 0.0, "m23")
        assertEquals(M_42, m1.m[7], 0.0, "m24")
        // row 3
        assertEquals(M_13, m1.m[8], 0.0, "m31")
        assertEquals(M_23, m1.m[9], 0.0, "m32")
        assertEquals(M_33, m1.m[10], 0.0, "m33")
        assertEquals(M_43, m1.m[11], 0.0, "m34")
        // row 4
        assertEquals(M_14, m1.m[12], 0.0, "m41")
        assertEquals(M_24, m1.m[13], 0.0, "m42")
        assertEquals(M_34, m1.m[14], 0.0, "m43")
        assertEquals(M_44, m1.m[15], 0.0, "m44")
        //
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testTransposeMatrix() {
        val m1 = Matrix4() // matrix under test
        val m2 = Matrix4( // matrix to be transposed
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, M_44)
        val m3 = m1.transposeMatrix(m2)

        // row 1
        assertEquals(M_11, m1.m[0], 0.0, "m11")
        assertEquals(M_21, m1.m[1], 0.0, "m12")
        assertEquals(M_31, m1.m[2], 0.0, "m13")
        assertEquals(M_41, m1.m[3], 0.0, "m14")
        // row 2
        assertEquals(M_12, m1.m[4], 0.0, "m21")
        assertEquals(M_22, m1.m[5], 0.0, "m22")
        assertEquals(M_32, m1.m[6], 0.0, "m23")
        assertEquals(M_42, m1.m[7], 0.0, "m24")
        // row 3
        assertEquals(M_13, m1.m[8], 0.0, "m31")
        assertEquals(M_23, m1.m[9], 0.0, "m32")
        assertEquals(M_33, m1.m[10], 0.0, "m33")
        assertEquals(M_43, m1.m[11], 0.0, "m34")
        // row 4
        assertEquals(M_14, m1.m[12], 0.0, "m41")
        assertEquals(M_24, m1.m[13], 0.0, "m42")
        assertEquals(M_34, m1.m[14], 0.0, "m43")
        assertEquals(M_44, m1.m[15], 0.0, "m44")
        //
        assertSame(m3, m1, "fluent api result")
    }

    @Test
    fun testInvert() {
        val m1 = Matrix4( // matrix to be inverted/tested
                3.0, -2.0, 0.0, 0.0,
                1.0, 4.0, -3.0, 0.0,
                -1.0, 0.0, 2.0, 0.0,
                0.0, 0.0, 0.0, 1.0)
        val m1Original = Matrix4(m1)
        val m2: Matrix4
        // Sanity check
        val d = computeDeterminant(m1)
        assertNotEquals(d, 0.0, 1e-16, "matrix is singular")
        try {
            m2 = m1.invert() // system under test
        } catch (e: Exception) {
            fail(e::class.simpleName)
        }

        // multiplying a matrix by its inverse should result in an identity matrix
        val mIdentity = m1.multiplyByMatrix(m1Original)
        //assertContentEquals(Matrix4.identity, mIdentity.m, "identity matrix array")
        for (i in mIdentity.m.indices) {
            assertEquals(Matrix4.identity[i], mIdentity.m[i], 1e-10, "identity matrix array")
        }
        assertSame(m2, m1, "fluent api result")
    }

    @Test
    fun testInvertMatrix() {
        val m1 = Matrix4() // matrix under test
        val m2 = Matrix4( // matrix to be inverted
                3.0, -2.0, 0.0, 0.0,
                1.0, 4.0, -3.0, 0.0,
                -1.0, 0.0, 2.0, 0.0,
                0.0, 0.0, 0.0, 1.0)
        val m3: Matrix4
        // Sanity check
        val d = computeDeterminant(m2)
        assertNotEquals(d, 0.0, 1e-16, "matrix is singular")
        try {
            m3 = m1.invertMatrix(m2) // system under test
        } catch (e: Exception) {
            fail(e::class.simpleName)
        }

        // multiplying a matrix by its inverse should result in an identity matrix
        val mIdentity = m1.multiplyByMatrix(m2)
        // assertContentEquals(Matrix4.identity, mIdentity.m, "identity matrix array")
        for (i in mIdentity.m.indices) {
            assertEquals(Matrix4.identity[i], mIdentity.m[i], 1e-10, "identity matrix array")
        }
        assertSame(m3, m1, "fluent api result")
    }

    /**
     * Tests the inverting of an orthogonal matrix whose columns and rows are orthogonal unit vectors (i.e., orthonormal
     * vectors).
     */
    @Test
    fun testInvertOrthonormalMatrix() {
        val dx = 2.0
        val dy = 3.0
        val dz = 5.0
        val m1 = Matrix4() // matrix under test
        val mOrthonormal = Matrix4( // an orthonormal matrix without translation
                0.5, 0.866025, 0.0, 0.0,
                0.866025, -0.5, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0)
        val mOrthonormalTranslation = Matrix4(mOrthonormal).setTranslation(dx, dy, dz)
        val m3 = m1.invertOrthonormalMatrix(mOrthonormalTranslation)


        // Independently compute orthonormal inverse with translation.
        val mOrthonormalInverse = Matrix4(mOrthonormal).transpose()
        val u = Vec3(dx, dy, dz).multiplyByMatrix(mOrthonormalInverse)
        mOrthonormalInverse.setTranslation(-u.x, -u.y, -u.z)
        // Compare arrays of the matrix under test with our computed matrix
        assertContentEquals(mOrthonormalInverse.m, m1.m)
        assertSame(m3, m1, "fluent api result")
    }

    @Test
    fun testInvertOrthonormal() {
        val dx = 2.0
        val dy = 3.0
        val dz = 5.0
        val mOrthonormal = Matrix4( // orthonormal matrix without translation
                0.5, 0.866025, 0.0, 0.0,
                0.866025, -0.5, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0)
        val m1 = Matrix4(mOrthonormal).setTranslation(dx, dy, dz)
        val m3 = m1.invertOrthonormal() // system under test


        // Independently compute orthonormal inverse with translation.
        val mOrthonormalInverse = Matrix4(mOrthonormal).transpose()
        val u = Vec3(dx, dy, dz).multiplyByMatrix(mOrthonormalInverse)
        mOrthonormalInverse.setTranslation(-u.x, -u.y, -u.z)
        // Compare arrays of the matrix under test with our computed matrix
        assertContentEquals(mOrthonormalInverse.m, m1.m)
        assertSame(m3, m1, "fluent api result")
    }

    @Test
    fun testTransposeToArray() {
        val m1 = Matrix4( // matrix under test
                M_11, M_12, M_13, M_14,
                M_21, M_22, M_23, M_24,
                M_31, M_32, M_33, M_34,
                M_41, M_42, M_43, M_44)
        val result = m1.transposeToArray(FloatArray(16), 0)
        val expected = m1.transpose().m
        for (i in 0..15) {
            assertEquals(expected[i], result[i].toDouble(), 0.0, i.toString())
        }
    }
}