package earth.worldwind.geom

import earth.worldwind.geom.Angle.Companion.POS180
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.math.*

/**
 * 4 x 4 matrix in row-major order.
 */
open class Matrix4 private constructor(
    /**
     * The matrix's components, stored in row-major order.
     */
    val m: DoubleArray
){
    companion object {
        protected const val NEAR_ZERO_THRESHOLD = 1.0e-8
        protected const val TINY = 1.0e-20
        protected const val EPSILON = 1.0e-10
        protected const val MAX_SWEEPS = 32

        /**
         * The components for the 4 x 4 identity matrix, stored in row-major order.
         */
        internal val identity = doubleArrayOf(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0
        )

        /**
         * Inverts a 4 x 4 matrix, storing the result in a destination argument. The source and destination arguments
         * represent a 4 x 4 matrix with a one-dimensional array in row-major order. The source and destination may
         * reference the same array.
         *
         * @param src the matrix components to invert in row-major order
         * @param dst the inverted components in row-major order
         *
         * @return true if the matrix was successfully inverted, false otherwise
         */
        protected fun invert(src: DoubleArray, dst: DoubleArray): Boolean {
            // Copy the specified matrix into a mutable two-dimensional array.
            val a = Array(4) { DoubleArray(4) }
            a[0][0] = src[0]
            a[0][1] = src[1]
            a[0][2] = src[2]
            a[0][3] = src[3]
            a[1][0] = src[4]
            a[1][1] = src[5]
            a[1][2] = src[6]
            a[1][3] = src[7]
            a[2][0] = src[8]
            a[2][1] = src[9]
            a[2][2] = src[10]
            a[2][3] = src[11]
            a[3][0] = src[12]
            a[3][1] = src[13]
            a[3][2] = src[14]
            a[3][3] = src[15]

            val index = IntArray(4)
            var d = ludcmp(a, index)

            // Compute the matrix's determinant.
            for (i in 0..3) d *= a[i][i]

            // The matrix is singular if its determinant is zero or very close to zero.
            if (abs(d) < NEAR_ZERO_THRESHOLD) return false

            val y = Array(4) { DoubleArray(4) }
            val col = DoubleArray(4)
            for (j in 0..3) {
                for (i in 0..3) col[i] = 0.0
                col[j] = 1.0
                lubksb(a, index, col)
                for (i in 0..3) y[i][j] = col[i]
            }
            dst[0] = y[0][0]
            dst[1] = y[0][1]
            dst[2] = y[0][2]
            dst[3] = y[0][3]
            dst[4] = y[1][0]
            dst[5] = y[1][1]
            dst[6] = y[1][2]
            dst[7] = y[1][3]
            dst[8] = y[2][0]
            dst[9] = y[2][1]
            dst[10] = y[2][2]
            dst[11] = y[2][3]
            dst[12] = y[3][0]
            dst[13] = y[3][1]
            dst[14] = y[3][2]
            dst[15] = y[3][3]
            return true
        }

        /**
         * Utility method to perform an LU factorization of a matrix. Algorithm derived from "Numerical Recipes in C", Press
         * et al., 1988.
         *
         * @param A     matrix to be factored
         * @param index permutation vector
         *
         * @return condition number of matrix
         */
        protected fun ludcmp(A: Array<DoubleArray>, index: IntArray): Double {
            val vv = DoubleArray(4)
            var d = 1.0
            var temp: Double
            for (i in 0..3) {
                var big = 0.0
                for (j in 0..3) if (abs(A[i][j]).also { temp = it } > big) big = temp
                if (big == 0.0) return 0.0 // Matrix is singular if the entire row contains zero.
                else vv[i] = 1 / big
            }
            for (j in 0..3) {
                for (i in 0 until j) {
                    var sum = A[i][j]
                    for (k in 0 until i) sum -= A[i][k] * A[k][j]
                    A[i][j] = sum
                }
                var big = 0.0
                var imax = -1
                var dum: Double
                for (i in j..3) {
                    var sum = A[i][j]
                    for (k in 0 until j) sum -= A[i][k] * A[k][j]
                    A[i][j] = sum
                    if (vv[i] * abs(sum).also { dum = it } >= big) {
                        big = dum
                        imax = i
                    }
                }
                if (j != imax) {
                    for (k in 0..3) {
                        dum = A[imax][k]
                        A[imax][k] = A[j][k]
                        A[j][k] = dum
                    }
                    d = -d
                    vv[imax] = vv[j]
                }
                index[j] = imax
                if (A[j][j] == 0.0) A[j][j] = TINY
                if (j != 3) {
                    dum = 1.0 / A[j][j]
                    for (i in (j + 1)..3) {
                        A[i][j] *= dum
                    }
                }
            }
            return d
        }

        /**
         * Utility method to solve a linear system with an LU factorization of a matrix. Solves Ax=b, where A is in LU
         * factorized form. Algorithm derived from "Numerical Recipes in C", Press et al., 1988
         *
         * @param A     an LU factorization of a matrix
         * @param index permutation vector of that LU factorization
         * @param b     vector to be solved
         */
        protected fun lubksb(A: Array<DoubleArray>, index: IntArray, b: DoubleArray) {
            var ii = -1
            for (i in 0..3) {
                val ip = index[i]
                var sum = b[ip]
                b[ip] = b[i]
                if (ii != -1) for (j in ii until i) sum -= A[i][j] * b[j]
                else if (sum != 0.0) ii = i
                b[i] = sum
            }
            for (i in 3 downTo 0) {
                var sum = b[i]
                for (j in (i + 1)..3) sum -= A[i][j] * b[j]
                b[i] = sum / A[i][i]
            }
        }
    }

    /**
     * Constructs a 4 x 4 identity matrix.
     */
    constructor(): this(identity.copyOf())

    /**
     * Constructs a 4 x 4 matrix with specified components.
     *
     * @param m11 matrix element at row 1, column 1
     * @param m12 matrix element at row 1, column 2
     * @param m13 matrix element at row 1, column 3
     * @param m14 matrix element at row 1, column 4
     * @param m21 matrix element at row 2, column 1
     * @param m22 matrix element at row 2, column 2
     * @param m23 matrix element at row 2, column 3
     * @param m24 matrix element at row 2, column 4
     * @param m31 matrix element at row 3, column 1
     * @param m32 matrix element at row 3, column 2
     * @param m33 matrix element at row 3, column 3
     * @param m34 matrix element at row 3, column 4
     * @param m41 matrix element at row 4, column 1
     * @param m42 matrix element at row 4, column 2
     * @param m43 matrix element at row 4, column 3
     * @param m44 matrix element at row 4, column 4
     */
    constructor(
        m11: Double, m12: Double, m13: Double, m14: Double,
        m21: Double, m22: Double, m23: Double, m24: Double,
        m31: Double, m32: Double, m33: Double, m34: Double,
        m41: Double, m42: Double, m43: Double, m44: Double
    ): this(doubleArrayOf(m11, m12, m13, m14, m21, m22, m23, m24, m31, m32, m33, m34, m41, m42, m43, m44))

    /**
     * Constructs a 4 x 4 matrix with the components of a specified matrix.
     *
     * @param matrix the matrix specifying the new components
     */
    constructor(matrix: Matrix4): this(matrix.m.copyOf())

    /**
     * Sets this 4 x 4 matrix to specified components.
     *
     * @param m11 matrix element at row 1, column 1
     * @param m12 matrix element at row 1, column 2
     * @param m13 matrix element at row 1, column 3
     * @param m14 matrix element at row 1, column 4
     * @param m21 matrix element at row 2, column 1
     * @param m22 matrix element at row 2, column 2
     * @param m23 matrix element at row 2, column 3
     * @param m24 matrix element at row 2, column 4
     * @param m31 matrix element at row 3, column 1
     * @param m32 matrix element at row 3, column 2
     * @param m33 matrix element at row 3, column 3
     * @param m34 matrix element at row 3, column 4
     * @param m41 matrix element at row 4, column 1
     * @param m42 matrix element at row 4, column 2
     * @param m43 matrix element at row 4, column 3
     * @param m44 matrix element at row 4, column 4
     *
     * @return this matrix set to the specified components
     */
    fun set(
        m11: Double, m12: Double, m13: Double, m14: Double,
        m21: Double, m22: Double, m23: Double, m24: Double,
        m31: Double, m32: Double, m33: Double, m34: Double,
        m41: Double, m42: Double, m43: Double, m44: Double
    ) = apply {
        m[0] = m11
        m[1] = m12
        m[2] = m13
        m[3] = m14
        m[4] = m21
        m[5] = m22
        m[6] = m23
        m[7] = m24
        m[8] = m31
        m[9] = m32
        m[10] = m33
        m[11] = m34
        m[12] = m41
        m[13] = m42
        m[14] = m43
        m[15] = m44
    }

    /**
     * Sets this 4 x 4 matrix to the components of a specified matrix.
     *
     * @param matrix the matrix specifying the new components
     *
     * @return this matrix with its components set to that of the specified matrix
     */
    fun copy(matrix: Matrix4) = apply { matrix.m.copyInto(m) }

    /**
     * Sets the translation components of this matrix to specified values.
     *
     * @param x the X translation component
     * @param y the Y translation component
     * @param z the Z translation component
     *
     * @return this matrix with its translation components set to the specified values and all other components
     * unmodified
     */
    fun setTranslation(x: Double, y: Double, z: Double) = apply {
        m[3] = x
        m[7] = y
        m[11] = z
    }

    /**
     * Sets the rotation components of this matrix to a specified axis and angle. Positive angles are interpreted as
     * counter-clockwise rotation about the axis when viewed when viewed from the positive end of the axis, looking
     * toward the negative end of the axis.
     * <br>
     * The result of this method is undefined if the axis components are not a unit vector.
     *
     * @param x     the X component of the rotation axis unit vector
     * @param y     the Y component of the rotation axis unit vector
     * @param z     the Z component of the rotation axis unit vector
     * @param angle the angle of rotation
     *
     * @return this matrix with its rotation components set to the specified values and all other components unmodified
     */
    fun setRotation(x: Double, y: Double, z: Double, angle: Angle) = apply {
        val c = cos(angle.radians)
        val s = sin(angle.radians)
        m[0] = c + (1 - c) * x * x
        m[1] = (1 - c) * x * y - s * z
        m[2] = (1 - c) * x * z + s * y
        m[4] = (1 - c) * x * y + s * z
        m[5] = c + (1 - c) * y * y
        m[6] = (1 - c) * y * z - s * x
        m[8] = (1 - c) * x * z - s * y
        m[9] = (1 - c) * y * z + s * x
        m[10] = c + (1 - c) * z * z
    }

    /**
     * Sets the scale components of this matrix to specified values.
     *
     * @param xScale the X scale component
     * @param yScale the Y scale component
     * @param zScale the Z scale component
     *
     * @return this matrix with its scale components set to the specified values and all other components unmodified
     */
    fun setScale(xScale: Double, yScale: Double, zScale: Double) = apply {
        m[0] = xScale
        m[5] = yScale
        m[10] = zScale
    }

    /**
     * Sets this matrix to the 4 x 4 identity matrix.
     *
     * @return this matrix, set to the identity matrix
     */
    fun setToIdentity() = apply { identity.copyInto(m) }

    /**
     * Sets this matrix to a translation matrix with specified translation components.
     *
     * @param x the X translation component
     * @param y the Y translation component
     * @param z the Z translation component
     *
     * @return this matrix with its translation components set to those specified and all other components set to that
     * of an identity matrix
     */
    fun setToTranslation(x: Double, y: Double, z: Double) = apply {
        m[0] = 1.0
        m[1] = 0.0
        m[2] = 0.0
        m[3] = x
        m[4] = 0.0
        m[5] = 1.0
        m[6] = 0.0
        m[7] = y
        m[8] = 0.0
        m[9] = 0.0
        m[10] = 1.0
        m[11] = z
        m[12] = 0.0
        m[13] = 0.0
        m[14] = 0.0
        m[15] = 1.0
    }

    /**
     * Sets this matrix to a rotation matrix with a specified axis and angle. Positive angles are interpreted as
     * counter-clockwise rotation about the axis when viewed when viewed from the positive end of the axis, looking
     * toward the negative end of the axis.
     * <br>
     * The result of this method is undefined if the axis components are not a unit vector.
     *
     * @param x     the X component of the rotation axis unit vector
     * @param y     the Y component of the rotation axis unit vector
     * @param z     the Z component of the rotation axis unit vector
     * @param angle the angle of rotation
     *
     * @return this matrix with its rotation components set to those specified and all other components set to that of
     * an identity matrix
     */
    fun setToRotation(x: Double, y: Double, z: Double, angle: Angle) = apply {
        val c = cos(angle.radians)
        val s = sin(angle.radians)
        m[0] = c + (1 - c) * x * x
        m[1] = (1 - c) * x * y - s * z
        m[2] = (1 - c) * x * z + s * y
        m[3] = 0.0
        m[4] = (1 - c) * x * y + s * z
        m[5] = c + (1 - c) * y * y
        m[6] = (1 - c) * y * z - s * x
        m[7] = 0.0
        m[8] = (1 - c) * x * z - s * y
        m[9] = (1 - c) * y * z + s * x
        m[10] = c + (1 - c) * z * z
        m[11] = 0.0
        m[12] = 0.0
        m[13] = 0.0
        m[14] = 0.0
        m[15] = 1.0
    }

    /**
     * Sets this matrix to a scale matrix with specified scale components.
     *
     * @param xScale the X scale component
     * @param yScale the Y scale component
     * @param zScale the Z scale component
     *
     * @return this matrix with its scale components set to those specified and all other components set to that of an
     * identity matrix
     */
    fun setToScale(xScale: Double, yScale: Double, zScale: Double) = apply {
        m[0] = xScale
        m[1] = 0.0
        m[2] = 0.0
        m[3] = 0.0
        m[4] = 0.0
        m[5] = yScale
        m[6] = 0.0
        m[7] = 0.0
        m[8] = 0.0
        m[9] = 0.0
        m[10] = zScale
        m[11] = 0.0
        m[12] = 0.0
        m[13] = 0.0
        m[14] = 0.0
        m[15] = 1.0
    }

    /**
     * Sets this matrix to the matrix product of two specified matrices.
     *
     * @param a the first matrix multiplicand
     * @param b The second matrix multiplicand
     *
     * @return this matrix set to the product of a x b
     */
    fun setToMultiply(a: Matrix4, b: Matrix4) = apply {
        val ma = a.m
        val mb = b.m
        m[0] = ma[0] * mb[0] + ma[1] * mb[4] + ma[2] * mb[8] + ma[3] * mb[12]
        m[1] = ma[0] * mb[1] + ma[1] * mb[5] + ma[2] * mb[9] + ma[3] * mb[13]
        m[2] = ma[0] * mb[2] + ma[1] * mb[6] + ma[2] * mb[10] + ma[3] * mb[14]
        m[3] = ma[0] * mb[3] + ma[1] * mb[7] + ma[2] * mb[11] + ma[3] * mb[15]
        m[4] = ma[4] * mb[0] + ma[5] * mb[4] + ma[6] * mb[8] + ma[7] * mb[12]
        m[5] = ma[4] * mb[1] + ma[5] * mb[5] + ma[6] * mb[9] + ma[7] * mb[13]
        m[6] = ma[4] * mb[2] + ma[5] * mb[6] + ma[6] * mb[10] + ma[7] * mb[14]
        m[7] = ma[4] * mb[3] + ma[5] * mb[7] + ma[6] * mb[11] + ma[7] * mb[15]
        m[8] = ma[8] * mb[0] + ma[9] * mb[4] + ma[10] * mb[8] + ma[11] * mb[12]
        m[9] = ma[8] * mb[1] + ma[9] * mb[5] + ma[10] * mb[9] + ma[11] * mb[13]
        m[10] = ma[8] * mb[2] + ma[9] * mb[6] + ma[10] * mb[10] + ma[11] * mb[14]
        m[11] = ma[8] * mb[3] + ma[9] * mb[7] + ma[10] * mb[11] + ma[11] * mb[15]
        m[12] = ma[12] * mb[0] + ma[13] * mb[4] + ma[14] * mb[8] + ma[15] * mb[12]
        m[13] = ma[12] * mb[1] + ma[13] * mb[5] + ma[14] * mb[9] + ma[15] * mb[13]
        m[14] = ma[12] * mb[2] + ma[13] * mb[6] + ma[14] * mb[10] + ma[15] * mb[14]
        m[15] = ma[12] * mb[3] + ma[13] * mb[7] + ma[14] * mb[11] + ma[15] * mb[15]
    }

    /**
     * Sets this matrix to an infinite perspective projection matrix for the specified viewport dimensions, vertical
     * field of view and near clip distance.
     * <br>
     * An infinite perspective projection matrix maps points in a manner similar to a standard projection matrix, but is
     * not bounded by depth. Objects at any depth greater than or equal to the near distance may be rendered. In
     * addition, this matrix interprets vertices with a w-coordinate of 0 as infinitely far from the camera in the
     * direction indicated by the point's coordinates.
     * <br>
     * The field of view must be positive and less than 180. The near distance must be positive.
     *
     * @param viewportWidth  the viewport width in screen coordinates
     * @param viewportHeight the viewport height in screen coordinates
     * @param vFieldOfView   the vertical field of view
     * @param nearDistance   the near clip plane distance in model coordinates
     *
     * @throws IllegalArgumentException If either the width or the height is less than or equal to zero, if the field of
     * view is less than or equal to zero or greater than 180, if the near distance is
     * less than or equal to zero
     */
    fun setToInfiniteProjection(
        viewportWidth: Int, viewportHeight: Int, vFieldOfView: Angle, nearDistance: Double
    ) = apply {
        require(viewportWidth > 0) {
            logMessage(ERROR, "Matrix4", "setToInfiniteProjection", "invalidWidth")
        }
        require(viewportHeight > 0) {
            logMessage(ERROR, "Matrix4", "setToInfiniteProjection", "invalidHeight")
        }
        require(vFieldOfView > ZERO && vFieldOfView < POS180) {
            logMessage(ERROR, "Matrix4", "setToInfiniteProjection", "invalidFieldOfView")
        }
        require(nearDistance > 0) {
            logMessage(ERROR, "Matrix4", "setToInfiniteProjection", "invalidClipDistance")
        }

        // Compute the dimensions of the near rectangle given the specified parameters.
        val aspect = viewportWidth / viewportHeight.toDouble()
        val tanFov2 = tan(vFieldOfView.radians * 0.5)
        val nearHeight = 2 * nearDistance * tanFov2
        val nearWidth = nearHeight * aspect

        // Taken from Mathematics for 3D Game Programming and Computer Graphics, Second Edition, equation 4.52.
        m[0] = 2 * nearDistance / nearWidth
        m[1] = 0.0
        m[2] = 0.0
        m[3] = 0.0
        m[4] = 0.0
        m[5] = 2 * nearDistance / nearHeight
        m[6] = 0.0
        m[7] = 0.0
        m[8] = 0.0
        m[9] = 0.0
        m[10] = -1.0
        m[11] = -2 * nearDistance
        m[12] = 0.0
        m[13] = 0.0
        m[14] = -1.0
        m[15] = 0.0
    }

    /**
     * Sets this matrix to a perspective projection matrix for the specified viewport dimensions, vertical field of view
     * and clip distances.
     * <br>
     * A perspective projection matrix maps points in eye coordinates into clip coordinates in a way that causes distant
     * objects to appear smaller, and preserves the appropriate depth information for each point. In model coordinates,
     * a perspective projection is defined by frustum originating at the eye position and extending outward in the
     * viewer's direction. The near distance and the far distance identify the minimum and maximum distance,
     * respectively, at which an object in the scene is visible.
     * <br>
     * The field of view must be positive and less than 180. Near and far distances must be positive and must not be
     * equal to one another.
     *
     * @param viewportWidth  the viewport width in screen coordinates
     * @param viewportHeight the viewport height in screen coordinates
     * @param vFieldOfView   the vertical field of view
     * @param nearDistance   the near clip plane distance in model coordinates
     * @param farDistance    the far clip plane distance in model coordinates
     *
     * @throws IllegalArgumentException If either the width or the height is less than or equal to zero, if the field of
     * view is less than or equal to zero or greater than 180, if the near and far
     * distances are equal, or if either the near or far distance are less than or
     * equal to zero
     */
    fun setToPerspectiveProjection(
        viewportWidth: Int, viewportHeight: Int, vFieldOfView: Angle, nearDistance: Double, farDistance: Double
    ) = apply {
        require(viewportWidth > 0) {
            logMessage(ERROR, "Matrix4", "setToPerspectiveProjection", "invalidWidth")
        }
        require(viewportHeight > 0) {
            logMessage(ERROR, "Matrix4", "setToPerspectiveProjection", "invalidHeight")
        }
        require(vFieldOfView > ZERO && vFieldOfView < POS180) {
            logMessage(ERROR, "Matrix4", "setToPerspectiveProjection", "invalidFieldOfView")
        }
        require(nearDistance != farDistance) {
            logMessage(ERROR, "Matrix4", "setToPerspectiveProjection", "invalidClipDistance")
        }
        require(nearDistance > 0 && farDistance > 0) {
            logMessage(ERROR, "Matrix4", "setToPerspectiveProjection", "invalidClipDistance")
        }

        // Compute the dimensions of the near rectangle given the specified parameters.
        val aspect = viewportWidth / viewportHeight.toDouble()
        val tanFov2 = tan(vFieldOfView.radians * 0.5)
        val nearHeight = 2 * nearDistance * tanFov2
        val nearWidth = nearHeight * aspect

        // Taken from Mathematics for 3D Game Programming and Computer Graphics, Second Edition, equation 4.52.
        m[0] = 2 * nearDistance / nearWidth
        m[1] = 0.0
        m[2] = 0.0
        m[3] = 0.0
        m[4] = 0.0
        m[5] = 2 * nearDistance / nearHeight
        m[6] = 0.0
        m[7] = 0.0
        m[8] = 0.0
        m[9] = 0.0
        m[10] = -(farDistance + nearDistance) / (farDistance - nearDistance)
        m[11] = -(2 * nearDistance * farDistance) / (farDistance - nearDistance)
        m[12] = 0.0
        m[13] = 0.0
        m[14] = -1.0
        m[15] = 0.0
    }

    /**
     * Sets this matrix to a screen projection matrix for the specified viewport dimensions.
     * <br>
     * A screen projection matrix is an orthographic projection that interprets points in model coordinates as
     * representing a screen XY and a Z depth. Screen projection matrices therefore map coordinates directly into screen
     * coordinates without modification. A point's XY coordinates are interpreted as literal screen coordinates and must
     * be in the viewport to be visible. A point's Z coordinate is interpreted as a depth value that ranges from 0 to 1.
     * Additionally, the screen projection matrix preserves the depth value returned by
     * `RenderContext.project`.
     *
     * @param viewportWidth  the viewport width in screen coordinates
     * @param viewportHeight the viewport height in screen coordinates
     *
     * @throws IllegalArgumentException If either the width or the height is less than or equal to zero
     */
    fun setToScreenProjection(viewportWidth: Double, viewportHeight: Double) = apply {
        require(viewportWidth > 0) {
            logMessage(ERROR, "Matrix4", "setToScreenProjection", "invalidWidth")
        }
        require(viewportHeight > 0) {
            logMessage(ERROR, "Matrix4", "setToScreenProjection", "invalidHeight")
        }

        // Taken from Mathematics for 3D Game Programming and Computer Graphics, Second Edition, equation 4.57.
        // Simplified to assume that the viewport origin is (0, 0).
        //
        // The third row of this projection matrix is configured so that points with z coordinates representing
        // depth values ranging from 0 to 1 are not modified after transformation into window coordinates. This
        // projection matrix maps z values in the range [0, 1] to the range [-1, 1] by applying the following
        // function to incoming z coordinates:
        //
        // zp = z0 * 2 - 1
        //
        // Where 'z0' is the point's z coordinate and 'zp' is the projected z coordinate. The GPU then maps the
        // projected z coordinate into window coordinates in the range [0, 1] by applying the following function:
        //
        // zw = zp * 0.5 + 0.5
        //
        // The result is that a point's z coordinate is effectively passed to the GPU without modification.
        m[0] = 2 / viewportWidth
        m[1] = 0.0
        m[2] = 0.0
        m[3] = -1.0
        m[4] = 0.0
        m[5] = 2 / viewportHeight
        m[6] = 0.0
        m[7] = -1.0
        m[8] = 0.0
        m[9] = 0.0
        m[10] = 2.0
        m[11] = -1.0
        m[12] = 0.0
        m[13] = 0.0
        m[14] = 0.0
        m[15] = 1.0
    }

    /**
     * Sets this matrix to the symmetric covariance Matrix computed from an array of points.
     * <br>
     * The computed covariance matrix represents the correlation between each pair of x-, y-, and z-coordinates as
     * they're distributed about the point array's arithmetic mean. Its layout is as follows:
     * <br>
     * ` C(x, x)  C(x, y)  C(x, z) <br> C(x, y)  C(y, y)  C(y, z) <br> C(x, z)  C(y, z)  C(z, z) `
     * <br>
     * C(i, j) is the covariance of coordinates i and j, where i or j are a coordinate's dispersion about its mean
     * value. If any entry is zero, then there's no correlation between the two coordinates defining that entry. If the
     * returned matrix is diagonal, then all three coordinates are uncorrelated, and the specified point is distributed
     * evenly about its mean point.
     *
     * @param array  the array of points to consider
     * @param count  the number of array elements to consider
     * @param stride the number of coordinates between the first coordinate of adjacent points - must be at least 3
     *
     * @return this matrix set to the covariance matrix for the specified array of points
     *
     * @throws IllegalArgumentException If the array is null or empty, if the count is less than 0, or if the stride is
     * less than 3
     */
    fun setToCovarianceOfPoints(array: FloatArray, count: Int, stride: Int) = apply {
        require(array.size >= stride) {
            logMessage(ERROR, "Matrix4", "setToCovarianceOfPoints", "invalidArray")
        }
        require(count >= 0) {
            logMessage(ERROR, "Matrix4", "setToCovarianceOfPoints", "invalidCount")
        }
        require(stride >= 3) {
            logMessage(ERROR, "Matrix4", "setToCovarianceOfPoints", "invalidStride")
        }

        var mx = 0.0
        var my = 0.0
        var mz = 0.0
        var c11 = 0.0
        var c22 = 0.0
        var c33 = 0.0
        var c12 = 0.0
        var c13 = 0.0
        var c23 = 0.0
        var numPoints = 0.0
        for (idx in 0 until count step stride) {
            mx += array[idx]
            my += array[idx + 1]
            mz += array[idx + 2]
            numPoints++
        }
        mx /= numPoints
        my /= numPoints
        mz /= numPoints
        for (idx in 0 until count step stride) {
            val dx = array[idx] - mx
            val dy = array[idx + 1] - my
            val dz = array[idx + 2] - mz
            c11 += dx * dx
            c22 += dy * dy
            c33 += dz * dz
            c12 += dx * dy // c12 = c21
            c13 += dx * dz // c13 = c31
            c23 += dy * dz // c23 = c32
        }
        m[0] = c11 / numPoints
        m[1] = c12 / numPoints
        m[2] = c13 / numPoints
        m[3] = 0.0
        m[4] = c12 / numPoints
        m[5] = c22 / numPoints
        m[6] = c23 / numPoints
        m[7] = 0.0
        m[8] = c13 / numPoints
        m[9] = c23 / numPoints
        m[10] = c33 / numPoints
        m[11] = 0.0
        m[12] = 0.0
        m[13] = 0.0
        m[14] = 0.0
        m[15] = 0.0
    }

    /**
     * Multiplies this matrix by a translation matrix with specified translation values.
     *
     * @param x the X translation component
     * @param y the Y translation component
     * @param z the Z translation component
     *
     * @return this matrix multiplied by the translation matrix implied by the specified values
     */
    fun multiplyByTranslation(x: Double, y: Double, z: Double) = apply {
        m[3] += m[0] * x + m[1] * y + m[2] * z
        m[7] += m[4] * x + m[5] * y + m[6] * z
        m[11] += m[8] * x + m[9] * y + m[10] * z
        m[15] += m[12] * x + m[13] * y + m[14] * z
    }

    /**
     * Multiplies this matrix by a rotation matrix about a specified axis and angle. Positive angles are interpreted as
     * counter-clockwise rotation about the axis.
     *
     * @param x     the X component of the rotation axis
     * @param y     the Y component of the rotation axis
     * @param z     the Z component of the rotation axis
     * @param angle the angle of rotation
     *
     * @return this matrix multiplied by the rotation matrix implied by the specified values
     */
    fun multiplyByRotation(x: Double, y: Double, z: Double, angle: Angle) = apply {
        val c = cos(angle.radians)
        val s = sin(angle.radians)
        multiplyByMatrix(
            c + (1 - c) * x * x,
            (1 - c) * x * y - s * z,
            (1 - c) * x * z + s * y,
            0.0,
            (1 - c) * x * y + s * z,
            c + (1 - c) * y * y,
            (1 - c) * y * z - s * x,
            0.0,
            (1 - c) * x * z - s * y,
            (1 - c) * y * z + s * x,
            c + (1 - c) * z * z,
            0.0,
            0.0,
            0.0,
            0.0,
            1.0
        )
    }

    /**
     * Multiplies this matrix by a scale matrix with specified values.
     *
     * @param xScale the X scale component
     * @param yScale the Y scale component
     * @param zScale the Z scale component
     *
     * @return this matrix multiplied by the scale matrix implied by the specified values
     */
    fun multiplyByScale(xScale: Double, yScale: Double, zScale: Double) = apply {
        m[0] *= xScale
        m[4] *= xScale
        m[8] *= xScale
        m[12] *= xScale
        m[1] *= yScale
        m[5] *= yScale
        m[9] *= yScale
        m[13] *= yScale
        m[2] *= zScale
        m[6] *= zScale
        m[10] *= zScale
        m[14] *= zScale
    }

    /**
     * Multiplies this matrix by a specified matrix.
     *
     * @param matrix the matrix to multiply with this matrix
     *
     * @return this matrix after multiplying it by the specified matrix
     */
    fun multiplyByMatrix(matrix: Matrix4) = apply {
        val ma = m
        val mb = matrix.m
        var ma0 = ma[0]
        var ma1 = ma[1]
        var ma2 = ma[2]
        var ma3 = ma[3]
        ma[0] = ma0 * mb[0] + ma1 * mb[4] + ma2 * mb[8] + ma3 * mb[12]
        ma[1] = ma0 * mb[1] + ma1 * mb[5] + ma2 * mb[9] + ma3 * mb[13]
        ma[2] = ma0 * mb[2] + ma1 * mb[6] + ma2 * mb[10] + ma3 * mb[14]
        ma[3] = ma0 * mb[3] + ma1 * mb[7] + ma2 * mb[11] + ma3 * mb[15]
        ma0 = ma[4]
        ma1 = ma[5]
        ma2 = ma[6]
        ma3 = ma[7]
        ma[4] = ma0 * mb[0] + ma1 * mb[4] + ma2 * mb[8] + ma3 * mb[12]
        ma[5] = ma0 * mb[1] + ma1 * mb[5] + ma2 * mb[9] + ma3 * mb[13]
        ma[6] = ma0 * mb[2] + ma1 * mb[6] + ma2 * mb[10] + ma3 * mb[14]
        ma[7] = ma0 * mb[3] + ma1 * mb[7] + ma2 * mb[11] + ma3 * mb[15]
        ma0 = ma[8]
        ma1 = ma[9]
        ma2 = ma[10]
        ma3 = ma[11]
        ma[8] = ma0 * mb[0] + ma1 * mb[4] + ma2 * mb[8] + ma3 * mb[12]
        ma[9] = ma0 * mb[1] + ma1 * mb[5] + ma2 * mb[9] + ma3 * mb[13]
        ma[10] = ma0 * mb[2] + ma1 * mb[6] + ma2 * mb[10] + ma3 * mb[14]
        ma[11] = ma0 * mb[3] + ma1 * mb[7] + ma2 * mb[11] + ma3 * mb[15]
        ma0 = ma[12]
        ma1 = ma[13]
        ma2 = ma[14]
        ma3 = ma[15]
        ma[12] = ma0 * mb[0] + ma1 * mb[4] + ma2 * mb[8] + ma3 * mb[12]
        ma[13] = ma0 * mb[1] + ma1 * mb[5] + ma2 * mb[9] + ma3 * mb[13]
        ma[14] = ma0 * mb[2] + ma1 * mb[6] + ma2 * mb[10] + ma3 * mb[14]
        ma[15] = ma0 * mb[3] + ma1 * mb[7] + ma2 * mb[11] + ma3 * mb[15]
    }

    /**
     * Multiplies this matrix by a matrix specified by individual components.
     *
     * @param m11 matrix element at row 1, column 1
     * @param m12 matrix element at row 1, column 2
     * @param m13 matrix element at row 1, column 3
     * @param m14 matrix element at row 1, column 4
     * @param m21 matrix element at row 2, column 1
     * @param m22 matrix element at row 2, column 2
     * @param m23 matrix element at row 2, column 3
     * @param m24 matrix element at row 2, column 4
     * @param m31 matrix element at row 3, column 1
     * @param m32 matrix element at row 3, column 2
     * @param m33 matrix element at row 3, column 3
     * @param m34 matrix element at row 3, column 4
     * @param m41 matrix element at row 4, column 1
     * @param m42 matrix element at row 4, column 2
     * @param m43 matrix element at row 4, column 3
     * @param m44 matrix element at row 4, column 4
     *
     * @return this matrix with its components multiplied by the specified values
     */
    fun multiplyByMatrix(
        m11: Double, m12: Double, m13: Double, m14: Double,
        m21: Double, m22: Double, m23: Double, m24: Double,
        m31: Double, m32: Double, m33: Double, m34: Double,
        m41: Double, m42: Double, m43: Double, m44: Double
    ) = apply {
        var mr1 = m[0]
        var mr2 = m[1]
        var mr3 = m[2]
        var mr4 = m[3]
        m[0] = mr1 * m11 + mr2 * m21 + mr3 * m31 + mr4 * m41
        m[1] = mr1 * m12 + mr2 * m22 + mr3 * m32 + mr4 * m42
        m[2] = mr1 * m13 + mr2 * m23 + mr3 * m33 + mr4 * m43
        m[3] = mr1 * m14 + mr2 * m24 + mr3 * m34 + mr4 * m44
        mr1 = m[4]
        mr2 = m[5]
        mr3 = m[6]
        mr4 = m[7]
        m[4] = mr1 * m11 + mr2 * m21 + mr3 * m31 + mr4 * m41
        m[5] = mr1 * m12 + mr2 * m22 + mr3 * m32 + mr4 * m42
        m[6] = mr1 * m13 + mr2 * m23 + mr3 * m33 + mr4 * m43
        m[7] = mr1 * m14 + mr2 * m24 + mr3 * m34 + mr4 * m44
        mr1 = m[8]
        mr2 = m[9]
        mr3 = m[10]
        mr4 = m[11]
        m[8] = mr1 * m11 + mr2 * m21 + mr3 * m31 + mr4 * m41
        m[9] = mr1 * m12 + mr2 * m22 + mr3 * m32 + mr4 * m42
        m[10] = mr1 * m13 + mr2 * m23 + mr3 * m33 + mr4 * m43
        m[11] = mr1 * m14 + mr2 * m24 + mr3 * m34 + mr4 * m44
        mr1 = m[12]
        mr2 = m[13]
        mr3 = m[14]
        mr4 = m[15]
        m[12] = mr1 * m11 + mr2 * m21 + mr3 * m31 + mr4 * m41
        m[13] = mr1 * m12 + mr2 * m22 + mr3 * m32 + mr4 * m42
        m[14] = mr1 * m13 + mr2 * m23 + mr3 * m33 + mr4 * m43
        m[15] = mr1 * m14 + mr2 * m24 + mr3 * m34 + mr4 * m44
    }

    /**
     * Transposes this matrix in place.
     *
     * @return this matrix, transposed.
     */
    fun transpose() = apply {
        var tmp = m[1]
        m[1] = m[4]
        m[4] = tmp

        tmp = m[2]
        m[2] = m[8]
        m[8] = tmp

        tmp = m[3]
        m[3] = m[12]
        m[12] = tmp

        tmp = m[6]
        m[6] = m[9]
        m[9] = tmp

        tmp = m[7]
        m[7] = m[13]
        m[13] = tmp

        tmp = m[11]
        m[11] = m[14]
        m[14] = tmp
    }

    /**
     * Sets this matrix to the transpose of a specified matrix.
     *
     * @param matrix the matrix whose transpose is to be computed
     *
     * @return this matrix with its values set to the transpose of the specified matrix
     */
    fun transposeMatrix(matrix: Matrix4) = apply {
        m[0] = matrix.m[0]
        m[1] = matrix.m[4]
        m[2] = matrix.m[8]
        m[3] = matrix.m[12]
        m[4] = matrix.m[1]
        m[5] = matrix.m[5]
        m[6] = matrix.m[9]
        m[7] = matrix.m[13]
        m[8] = matrix.m[2]
        m[9] = matrix.m[6]
        m[10] = matrix.m[10]
        m[11] = matrix.m[14]
        m[12] = matrix.m[3]
        m[13] = matrix.m[7]
        m[14] = matrix.m[11]
        m[15] = matrix.m[15]
    }

    /**
     * Transposes this matrix, storing the result in the specified single precision array. The result is compatible with
     * GLSL uniform matrices, and can be passed to the function glUniformMatrix4fv.
     *
     * @param result a pre-allocated array of length 16 in which to return the transposed components
     *
     * @return the result argument set to the transposed components
     */
    fun transposeToArray(result: FloatArray, offset: Int): FloatArray {
        var o = offset
        require(result.size - o >= 16) {
            logMessage(ERROR, "Matrix4", "transposeToArray", "missingArray")
        }
        result[o++] = m[0].toFloat()
        result[o++] = m[4].toFloat()
        result[o++] = m[8].toFloat()
        result[o++] = m[12].toFloat()
        result[o++] = m[1].toFloat()
        result[o++] = m[5].toFloat()
        result[o++] = m[9].toFloat()
        result[o++] = m[13].toFloat()
        result[o++] = m[2].toFloat()
        result[o++] = m[6].toFloat()
        result[o++] = m[10].toFloat()
        result[o++] = m[14].toFloat()
        result[o++] = m[3].toFloat()
        result[o++] = m[7].toFloat()
        result[o++] = m[11].toFloat()
        result[o] = m[15].toFloat()
        return result
    }

    /**
     * Inverts this matrix in place.
     * <br>
     * This throws an exception if this matrix is singular.
     *
     * @return this matrix, inverted
     *
     * @throws IllegalArgumentException If this matrix cannot be inverted
     */
    fun invert() = apply {
        val success = invert(m, m) // passing the same array as src and dst is supported
        require(success) {
            // the matrix is singular
            logMessage(ERROR, "Matrix4", "invertMatrix", "singularMatrix")
        }
    }

    /**
     * Inverts the specified matrix and stores the result in this matrix.
     * <br>
     * This throws an exception if the specified matrix is singular.
     * <br>
     * The result of this method is undefined if this matrix is passed in as the matrix to invert.
     *
     * @param matrix the matrix whose inverse is computed
     *
     * @return this matrix set to the inverse of the specified matrix
     *
     * @throws IllegalArgumentException If the matrix cannot be inverted
     */
    fun invertMatrix(matrix: Matrix4) = apply {
        val success = invert(matrix.m, m) // store inverse of matrix in this matrix
        require(success) {
            // the matrix is singular
            logMessage(ERROR, "Matrix4", "invertMatrix", "singularMatrix")
        }
    }

    /**
     * Inverts this orthonormal transform matrix in place. This matrix's upper 3x3 is transposed, then its fourth column
     * is transformed by the transposed upper 3x3 and negated.
     * <br>
     * The result of this method is undefined if this matrix's values are not consistent with those of an orthonormal
     * transform.
     *
     * @return this matrix, inverted
     */
    fun invertOrthonormal() = apply {
        // This is assumed to contain matrix 3D transformation matrix. The upper 3x3 is transposed, the translation
        // components are multiplied by the transposed-upper-3x3 and negated.
        var tmp = m[1]
        m[1] = m[4]
        m[4] = tmp

        tmp = m[2]
        m[2] = m[8]
        m[8] = tmp

        tmp = m[6]
        m[6] = m[9]
        m[9] = tmp

        val x = m[3]
        val y = m[7]
        val z = m[11]

        m[3] = -(m[0] * x) - m[1] * y - m[2] * z
        m[7] = -(m[4] * x) - m[5] * y - m[6] * z
        m[11] = -(m[8] * x) - m[9] * y - m[10] * z

        m[12] = 0.0
        m[13] = 0.0
        m[14] = 0.0
        m[15] = 1.0
    }

    /**
     * Inverts the specified orthonormal transform matrix and stores the result in 'this' matrix. The specified matrix's
     * upper 3x3 is transposed, then its fourth column is transformed by the transposed upper 3x3 and negated.  The
     * result is stored in 'this' matrix.
     * <br>
     * The result of this method is undefined if this matrix is passed in as the matrix to invert, or if the matrix's
     * values are not consistent with those of an orthonormal transform.
     *
     * @param matrix the matrix whose inverse is computed. The matrix is assumed to represent an orthonormal transform
     * matrix.
     *
     * @return this matrix set to the inverse of the specified matrix
     */
    fun invertOrthonormalMatrix(matrix: Matrix4) = apply {
        // The matrix is assumed to contain matrix 3D transformation matrix. The upper 3x3 is transposed, the translation
        // components are multiplied by the transposed-upper-3x3 and negated.
        m[0] = matrix.m[0]
        m[1] = matrix.m[4]
        m[2] = matrix.m[8]
        m[3] = -(matrix.m[0] * matrix.m[3]) - matrix.m[4] * matrix.m[7] - matrix.m[8] * matrix.m[11]
        m[4] = matrix.m[1]
        m[5] = matrix.m[5]
        m[6] = matrix.m[9]
        m[7] = -(matrix.m[1] * matrix.m[3]) - matrix.m[5] * matrix.m[7] - matrix.m[9] * matrix.m[11]
        m[8] = matrix.m[2]
        m[9] = matrix.m[6]
        m[10] = matrix.m[10]
        m[11] = -(matrix.m[2] * matrix.m[3]) - matrix.m[6] * matrix.m[7] - matrix.m[10] * matrix.m[11]
        m[12] = 0.0
        m[13] = 0.0
        m[14] = 0.0
        m[15] = 1.0
    }

    /**
     * Applies a specified depth offset to this projection matrix. The depth offset may be any real number and is
     * typically used to draw geometry slightly closer to the user's eye in order to give those shapes visual priority
     * over nearby or geometry. An offset of zero has no effect. An offset less than zero brings depth values closer to
     * the eye, while an offset greater than zero pushes depth values away from the eye.
     * <br>
     * The result of this method is undefined if this matrix is not a projection matrix. Projection matrices can be
     * created by calling `setToPerspectiveProjection` or `setToScreenProjection`
     * <br>
     * Depth offset may be applied to both perspective and screen projection matrices. The effect on each type is
     * outlined here:
     * <br>
     * **Perspective Projection**
     * <br>
     * The effect of depth offset on a perspective projection increases exponentially with distance from the eye. This
     * has the effect of adjusting the offset for the loss in depth precision with geometry drawn further from the eye.
     * Distant geometry requires a greater offset to differentiate itself from nearby geometry, while close geometry
     * does not.
     * <br>
     * **Screen Projection**
     * <br>
     * The effect of depth offset on an screen projection increases linearly with distance from the eye. While it is
     * reasonable to apply a depth offset to an screen projection, the effect is most appropriate when applied to the
     * projection used to draw the scene. For example, when an object's coordinates are projected by a perspective
     * projection into screen coordinates then drawn using a screen projection, it is best to apply the offset to the
     * original perspective projection. The method `RenderContext.project` performs the correct behavior for
     * the projection type used to draw the scene.
     *
     * @param depthOffset the amount of offset to apply
     *
     * @return this matrix with its components adjusted to account for the specified depth offset
     */
    fun offsetProjectionDepth(depthOffset: Double) = apply { m[10] *= 1 + depthOffset }

    /**
     * Returns this viewing matrix's eye point. In model coordinates, a viewing matrix's eye point is the point the
     * viewer is looking from and maps to the center of the screen.
     * <br>
     * The result of this method is undefined if this matrix is not a viewing matrix.
     *
     * @param result a pre-allocated `Vec3` in which to return the extracted value
     *
     * @return the specified result argument containing the viewing matrix's eye point
     */
    fun extractEyePoint(result: Vec3): Vec3 {
        // The eye point of a modelview matrix is computed by transforming the origin (0, 0, 0, 1) by the matrix's
        // inverse. This is equivalent to transforming the inverse of this matrix's translation components in the
        // rightmost column by the transpose of its upper 3x3 components.
        result.x = -(m[0] * m[3]) - m[4] * m[7] - m[8] * m[11]
        result.y = -(m[1] * m[3]) - m[5] * m[7] - m[9] * m[11]
        result.z = -(m[2] * m[3]) - m[6] * m[7] - m[10] * m[11]
        return result
    }

    /**
     * Returns this viewing matrix's forward vector.
     * <br>
     * The result of this method is undefined if this matrix is not a viewing matrix.
     *
     * @param result a pre-allocated `Vec3` in which to return the extracted value
     *
     * @return the specified result argument containing the viewing matrix's forward vector
     */
    fun extractForwardVector(result: Vec3): Vec3 {
        // The forward vector of a modelview matrix is computed by transforming the negative Z axis (0, 0, -1, 0) by the
        // matrix's inverse. We have pre-computed the result inline here to simplify this computation.
        result.x = -m[8]
        result.y = -m[9]
        result.z = -m[10]
        return result
    }

    /**
     * Returns this viewing matrix's heading angle. The roll argument enables the caller to disambiguate
     * heading and roll when the two rotation axes for heading and roll are parallel, causing gimbal lock.
     * <br>
     * The result of this method is undefined if this matrix is not a viewing matrix.
     *
     * @param roll the viewing matrix's roll angle, or 0 if the roll angle is unknown
     *
     * @return the extracted heading angle
     */
    fun extractHeading(roll: Angle): Angle {
        val cr = cos(roll.radians)
        val sr = sin(roll.radians)
        val ch = cr * m[0] - sr * m[4]
        val sh = sr * m[5] - cr * m[1]
        return atan2(sh, ch).radians
    }

    /**
     * Returns this viewing matrix's tilt angle.
     * <br>
     * The result of this method is undefined if this matrix is not a viewing matrix.
     *
     * @return the extracted heading angle
     */
    fun extractTilt(): Angle {
        val ct = m[10]
        val st = sqrt(m[2] * m[2] + m[6] * m[6])
        return atan2(st, ct).radians
    }

    /**
     * Returns this symmetric matrix's eigenvectors. The eigenvectors are returned in the specified result arguments in
     * order of descending magnitude (most prominent to least prominent). Each eigenvector has length equal to its
     * corresponding eigenvalue.
     * <br>
     * This method returns false if this matrix is not a symmetric matrix.
     *
     * @param result1 a pre-allocated Vec3 in which to return the most prominent eigenvector
     * @param result2 a pre-allocated Vec3 in which to return the second most prominent eigenvector
     * @param result3 a pre-allocated Vec3 in which to return the least prominent eigenvector
     *
     * @return true if this matrix is symmetric and its eigenvectors can be determined, otherwise false
     */
    fun extractEigenvectors(result1: Vec3, result2: Vec3, result3: Vec3): Boolean {
        // Taken from Mathematics for 3D Game Programming and Computer Graphics, Second Edition,
        // listing 14.6.
        if (m[1] != m[4] || m[2] != m[8] || m[6] != m[9]) return false // matrix is not symmetric

        // Since the matrix is symmetric m12=m21, m13=m31 and m23=m32, therefore we can ignore the values m21,
        // m32 and m32.
        var m11 = m[0]
        var m12 = m[1]
        var m13 = m[2]
        var m22 = m[5]
        var m23 = m[6]
        var m33 = m[10]
        val r = Array(3) { DoubleArray(3) }
        r[2][2] = 1.0
        r[1][1] = r[2][2]
        r[0][0] = r[1][1]
        for (a in 0 until MAX_SWEEPS) {
            // Exit if off-diagonal entries small enough
            if (abs(m12) < EPSILON && abs(m13) < EPSILON && abs(m23) < EPSILON) break

            // Annihilate (1,2) entry.
            if (m12 != 0.0) {
                val u = (m22 - m11) * 0.5 / m12
                val u2 = u * u
                val u2p1 = u2 + 1
                val t = if (u2p1 != u2) (if (u < 0) -1 else 1) * (sqrt(u2p1) - abs(u)) else 0.5 / u
                val c = 1 / sqrt(t * t + 1)
                val s = c * t
                m11 -= t * m12
                m22 += t * m12
                m12 = 0.0
                var temp = c * m13 - s * m23
                m23 = s * m13 + c * m23
                m13 = temp
                for (i in 0..2) {
                    temp = c * r[i][0] - s * r[i][1]
                    r[i][1] = s * r[i][0] + c * r[i][1]
                    r[i][0] = temp
                }
            }

            // Annihilate (1,3) entry.
            if (m13 != 0.0) {
                val u = (m33 - m11) * 0.5 / m13
                val u2 = u * u
                val u2p1 = u2 + 1
                val t = if (u2p1 != u2) (if (u < 0) -1 else 1) * (sqrt(u2p1) - abs(u)) else 0.5 / u
                val c = 1 / sqrt(t * t + 1)
                val s = c * t
                m11 -= t * m13
                m33 += t * m13
                m13 = 0.0
                var temp = c * m12 - s * m23
                m23 = s * m12 + c * m23
                m12 = temp
                for (i in 0..2) {
                    temp = c * r[i][0] - s * r[i][2]
                    r[i][2] = s * r[i][0] + c * r[i][2]
                    r[i][0] = temp
                }
            }

            // Annihilate (2,3) entry.
            if (m23 != 0.0) {
                val u = (m33 - m22) * 0.5 / m23
                val u2 = u * u
                val u2p1 = u2 + 1
                val t = if (u2p1 != u2) (if (u < 0) -1 else 1) * (sqrt(u2p1) - abs(u)) else 0.5 / u
                val c = 1 / sqrt(t * t + 1)
                val s = c * t
                m22 -= t * m23
                m33 += t * m23
                m23 = 0.0
                var temp = c * m12 - s * m13
                m13 = s * m12 + c * m13
                m12 = temp
                for (i in 0..2) {
                    temp = c * r[i][1] - s * r[i][2]
                    r[i][2] = s * r[i][1] + c * r[i][2]
                    r[i][1] = temp
                }
            }
        }

        // Sort the eigenvectors by descending magnitude.
        var i1 = 0
        var i2 = 1
        var i3 = 2
        if (m11 < m22) {
            val temp = m11
            m11 = m22
            m22 = temp
            val itemp = i1
            i1 = i2
            i2 = itemp
        }
        if (m22 < m33) {
            val temp = m22
            m22 = m33
            m33 = temp
            val itemp = i2
            i2 = i3
            i3 = itemp
        }
        if (m11 < m22) {
            val temp = m11
            m11 = m22
            m22 = temp
            val itemp = i1
            i1 = i2
            i2 = itemp
        }
        result1.set(r[0][i1], r[1][i1], r[2][i1])
        result2.set(r[0][i2], r[1][i2], r[2][i2])
        result3.set(r[0][i3], r[1][i3], r[2][i3])
        result1.normalize()
        result2.normalize()
        result3.normalize()
        result1.multiply(m11)
        result2.multiply(m22)
        result3.multiply(m33)
        return true
    }

    /**
     * Projects a Cartesian point to screen coordinates. This method assumes this matrix represents an inverse
     * modelview-projection matrix. The result of this method is undefined if this matrix is not an inverse
     * modelview-projection matrix.
     * <br>
     * The resultant screen point is in OpenGL screen coordinates, with the origin in the bottom-left corner and axes
     * that extend up and to the right from the origin.
     * <br>
     * This stores the projected point in the result argument, and returns a boolean value indicating whether or not the
     * projection is successful. This returns false if the Cartesian point is clipped by the near clipping plane or the
     * far clipping plane.
     *
     * @param x        the Cartesian point's X component
     * @param y        the Cartesian point's y component
     * @param z        the Cartesian point's z component
     * @param viewport the viewport defining the screen point's coordinate system
     * @param result   a pre-allocated [Vec3] in which to return the projected point
     *
     * @return true if the transformation is successful, otherwise false
     */
    fun project(x: Double, y: Double, z: Double, viewport: Viewport, result: Vec3): Boolean {
        // Transform the model point from model coordinates to eye coordinates then to clip coordinates. This inverts
        // the Z axis and stores the negative of the eye coordinate Z value in the W coordinate.
        var sx = m[0] * x + m[1] * y + m[2] * z + m[3]
        var sy = m[4] * x + m[5] * y + m[6] * z + m[7]
        var sz = m[8] * x + m[9] * y + m[10] * z + m[11]
        val sw = m[12] * x + m[13] * y + m[14] * z + m[15]
        if (sw == 0.0) return false

        // Complete the conversion from model coordinates to clip coordinates by dividing by W. The resultant X, Y
        // and Z coordinates are in the range [-1,1].
        sx /= sw
        sy /= sw
        sz /= sw

        // Clip the point against the near and far clip planes.
        if (sz < -1 || sz > 1) return false

        // Convert the point from clip coordinate to the range [0,1]. This enables the X and Y coordinates to be
        // converted to screen coordinates, and the Z coordinate to represent a depth value in the range[0,1].
        sx = sx * 0.5 + 0.5
        sy = sy * 0.5 + 0.5
        sz = sz * 0.5 + 0.5

        // Convert the X and Y coordinates from the range [0,1] to screen coordinates.
        sx = sx * viewport.width + viewport.x
        sy = sy * viewport.height + viewport.y
        result.x = sx
        result.y = sy
        result.z = sz
        return true
    }

    /**
     * Un-projects a screen coordinate point to Cartesian coordinates at the near clip plane and the far clip plane.
     * This method assumes this matrix represents an inverse modelview-projection matrix. The result of this method is
     * undefined if this matrix is not an inverse modelview-projection matrix.
     * <br>
     * The screen point is understood to be in OpenGL screen coordinates, with the origin in the bottom-left corner and
     * axes that extend up and to the right from the origin.
     * <br>
     * This function stores the un-projected points in the result argument, and a boolean value indicating whether the
     * un-projection is successful.
     *
     * @param x          the screen point's X component
     * @param y          the screen point's Y component
     * @param viewport   the viewport defining the screen point's coordinate system
     * @param nearResult a pre-allocated [Vec3] in which to return the un-projected near clip plane point
     * @param farResult  a pre-allocated [Vec3] in which to return the un-projected far clip plane point
     *
     * @return true if the transformation is successful, otherwise false
     */
    fun unProject(x: Double, y: Double, viewport: Viewport, nearResult: Vec3, farResult: Vec3): Boolean {
        // Convert the XY screen coordinates to coordinates in the range [0, 1]. This enables the XY coordinates to
        // be converted to clip coordinates.
        var sx = (x - viewport.x) / viewport.width
        var sy = (y - viewport.y) / viewport.height

        // Convert from coordinates in the range [0, 1] to clip coordinates in the range [-1, 1].
        sx = sx * 2 - 1
        sy = sy * 2 - 1

        // Transform the screen point from clip coordinates to model coordinates. This is a partial transformation that
        // factors out the contribution from the screen point's X and Y components. The contribution from the Z
        // component, which is both -1 and +1, is included next.
        val mx = m[0] * sx + m[1] * sy + m[3]
        val my = m[4] * sx + m[5] * sy + m[7]
        val mz = m[8] * sx + m[9] * sy + m[11]
        val mw = m[12] * sx + m[13] * sy + m[15]

        // Transform the screen point at the near clip plane (z = -1) to model coordinates.
        val nx = mx - m[2]
        val ny = my - m[6]
        val nz = mz - m[10]
        val nw = mw - m[14]

        // Transform the screen point at the far clip plane (z = +1) to model coordinates.
        val fx = mx + m[2]
        val fy = my + m[6]
        val fz = mz + m[10]
        val fw = mw + m[14]
        if (nw == 0.0 || fw == 0.0) return false

        // Complete the conversion from near clip coordinates to model coordinates by dividing by the W component.
        nearResult.x = nx / nw
        nearResult.y = ny / nw
        nearResult.z = nz / nw

        // Complete the conversion from far clip coordinates to model coordinates by dividing by the W component.
        farResult.x = fx / fw
        farResult.y = fy / fw
        farResult.z = fz / fw
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Matrix4) return false
        return m.contentEquals(other.m)
    }

    override fun hashCode() = m.contentHashCode()

    override fun toString() =
        "Matrix4([${m[0]}, ${m[1]}, ${m[2]}, ${m[3]}], [${m[4]}, ${m[5]}, ${m[6]}, ${m[7]}], [${m[8]}, ${m[9]}, ${m[10]}, ${m[11]}], [${m[12]}, ${m[13]}, ${m[14]}, ${m[15]}])"
}