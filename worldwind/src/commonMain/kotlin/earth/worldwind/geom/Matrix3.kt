package earth.worldwind.geom

import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.math.cos
import kotlin.math.sin

/**
 * 3 x 3 matrix in row-major order.
 */
open class Matrix3 private constructor(
    /**
     * The matrix's components, stored in row-major order.
     */
    val m: DoubleArray
){
    companion object {
        /**
         * The components for the 3 x 3 identity matrix, stored in row-major order.
         */
        internal val identity = doubleArrayOf(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
        )
    }

    /**
     * Constructs a 3 x 3 identity matrix.
     */
    constructor(): this(identity.copyOf())

    /**
     * Constructs a 3 x 3 matrix with specified components.
     *
     * @param m11 matrix element at row 1, column 1
     * @param m12 matrix element at row 1, column 2
     * @param m13 matrix element at row 1, column 3
     * @param m21 matrix element at row 2, column 1
     * @param m22 matrix element at row 2, column 2
     * @param m23 matrix element at row 2, column 3
     * @param m31 matrix element at row 3, column 1
     * @param m32 matrix element at row 3, column 2
     * @param m33 matrix element at row 3, column 3
     */
    constructor(
        m11: Double, m12: Double, m13: Double,
        m21: Double, m22: Double, m23: Double,
        m31: Double, m32: Double, m33: Double
    ): this(doubleArrayOf(m11, m12, m13, m21, m22, m23, m31, m32, m33))

    /**
     * Constructs a 3 x 3 matrix with the components of a specified matrix.
     *
     * @param matrix the matrix specifying the new components
     */
    constructor(matrix: Matrix3): this(matrix.m.copyOf())

    /**
     * Sets this 3 x 3 matrix to specified components.
     *
     * @param m11 matrix element at row 1, column 1
     * @param m12 matrix element at row 1, column 2
     * @param m13 matrix element at row 1, column 3
     * @param m21 matrix element at row 2, column 1
     * @param m22 matrix element at row 2, column 2
     * @param m23 matrix element at row 2, column 3
     * @param m31 matrix element at row 3, column 1
     * @param m32 matrix element at row 3, column 2
     * @param m33 matrix element at row 3, column 3
     *
     * @return this matrix set to the specified components
     */
    fun set(
        m11: Double, m12: Double, m13: Double,
        m21: Double, m22: Double, m23: Double,
        m31: Double, m32: Double, m33: Double
    ) = apply {
        m[0] = m11
        m[1] = m12
        m[2] = m13
        m[3] = m21
        m[4] = m22
        m[5] = m23
        m[6] = m31
        m[7] = m32
        m[8] = m33
    }

    /**
     * Sets this 3 x 3 matrix to the components of a specified matrix.
     *
     * @param matrix the matrix specifying the new components
     *
     * @return this matrix with its components set to that of the specified matrix
     */
    fun copy(matrix: Matrix3) = apply { matrix.m.copyInto(m) }

    /**
     * Sets the translation components of this matrix to specified values.
     *
     * @param x the X translation component
     * @param y the Y translation component
     *
     * @return this matrix with its translation components set to the specified values and all other components
     * unmodified
     */
    fun setTranslation(x: Double, y: Double) = apply {
        m[2] = x
        m[5] = y
    }

    /**
     * Sets the rotation components of this matrix to a specified angle. Positive angles are interpreted as
     * counter-clockwise rotation.
     *
     * @param angle the angle of rotation
     *
     * @return this matrix with its rotation components set to the specified values and all other components unmodified
     */
    fun setRotation(angle: Angle) = apply {
        val c = cos(angle.inRadians)
        val s = sin(angle.inRadians)
        m[0] = c
        m[1] = -s
        m[3] = s
        m[4] = c
    }

    /**
     * Sets the scale components of this matrix to specified values.
     *
     * @param xScale the X scale component
     * @param yScale the Y scale component
     *
     * @return this matrix with its scale components set to the specified values and all other components unmodified
     */
    fun setScale(xScale: Double, yScale: Double) = apply {
        m[0] = xScale
        m[4] = yScale
    }

    /**
     * Sets this matrix to the 3 x 3 identity matrix.
     *
     * @return this matrix, set to the identity matrix
     */
    fun setToIdentity() = apply { identity.copyInto(m) }

    /**
     * Sets this matrix to a translation matrix with specified translation components.
     *
     * @param x the X translation component
     * @param y the Y translation component
     *
     * @return this matrix with its translation components set to those specified and all other components set to that
     * of an identity matrix
     */
    fun setToTranslation(x: Double, y: Double) = apply {
        m[0] = 1.0
        m[1] = 0.0
        m[2] = x
        m[3] = 0.0
        m[4] = 1.0
        m[5] = y
        m[6] = 0.0
        m[7] = 0.0
        m[8] = 1.0
    }

    /**
     * Sets this matrix to a rotation matrix with a specified angle. Positive angles are interpreted as
     * counter-clockwise rotation.
     *
     * @param angle the angle of rotation
     *
     * @return this matrix with its rotation components set to those specified and all other components set to that of
     * an identity matrix
     */
    fun setToRotation(angle: Angle) = apply {
        val c = cos(angle.inRadians)
        val s = sin(angle.inRadians)
        m[0] = c
        m[1] = -s
        m[2] = 0.0
        m[3] = s
        m[4] = c
        m[5] = 0.0
        m[6] = 0.0
        m[7] = 0.0
        m[8] = 1.0
    }

    /**
     * Sets this matrix to a scale matrix with specified scale components.
     *
     * @param xScale the X scale component
     * @param yScale the Y scale component
     *
     * @return this matrix with its scale components set to those specified and all other components set to that of an
     * identity matrix
     */
    fun setToScale(xScale: Double, yScale: Double) = apply {
        m[0] = xScale
        m[1] = 0.0
        m[2] = 0.0
        m[3] = 0.0
        m[4] = yScale
        m[5] = 0.0
        m[6] = 0.0
        m[7] = 0.0
        m[8] = 1.0
    }

    /**
     * Sets this matrix to one that flips and shifts the y-axis. The resultant matrix maps Y=0 to Y=1 and Y=1 to Y=0.
     * All existing values are overwritten. This matrix is usually used to change the coordinate origin from an upper
     * left coordinate origin to a lower left coordinate origin.
     * <br>
     * This matrix is typically necessary to align the coordinate system of images (top-left origin) with that of OpenGL
     * (bottom-left origin).
     *
     * @return this matrix set to values described above
     */
    fun setToVerticalFlip() = apply {
        m[0] = 1.0
        m[1] = 0.0
        m[2] = 0.0
        m[3] = 0.0
        m[4] = -1.0
        m[5] = 1.0
        m[6] = 0.0
        m[7] = 0.0
        m[8] = 1.0
    }

    /**
     * Sets this matrix to one that transforms normalized coordinates from a source sector to a destination sector.
     * Normalized coordinates within a sector range from 0 to 1, with (0, 0) indicating the lower left corner and (1, 1)
     * indicating the upper right. The resultant matrix maps a normalized source coordinate (X, Y) to its corresponding
     * normalized destination coordinate (X', Y').
     * <br>
     * This matrix typically necessary to transform texture coordinates from one geographic region to another. For
     * example, the texture coordinates for a terrain tile spanning one region must be transformed to coordinates
     * appropriate for an image tile spanning a potentially different region.
     *
     * @param src the source sector
     * @param dst the destination sector
     *
     * @return this matrix set to values described above
     */
    fun setToTileTransform(src: Sector, dst: Sector) = apply {
        val srcDeltaLat = src.deltaLatitude.inDegrees
        val srcDeltaLon = src.deltaLongitude.inDegrees
        val dstDeltaLat = dst.deltaLatitude.inDegrees
        val dstDeltaLon = dst.deltaLongitude.inDegrees
        val xs = srcDeltaLon / dstDeltaLon
        val ys = srcDeltaLat / dstDeltaLat
        val xt = (src.minLongitude.inDegrees - dst.minLongitude.inDegrees) / dstDeltaLon
        val yt = (src.minLatitude.inDegrees - dst.minLatitude.inDegrees) / dstDeltaLat
        m[0] = xs
        m[1] = 0.0
        m[2] = xt
        m[3] = 0.0
        m[4] = ys
        m[5] = yt
        m[6] = 0.0
        m[7] = 0.0
        m[8] = 1.0
    }

    /**
     * Sets this matrix to the matrix product of two specified matrices.
     *
     * @param a the first matrix multiplicand
     * @param b The second matrix multiplicand
     *
     * @return this matrix set to the product of a x b
     */
    fun setToMultiply(a: Matrix3, b: Matrix3) = apply {
        val ma = a.m
        val mb = b.m
        m[0] = ma[0] * mb[0] + ma[1] * mb[3] + ma[2] * mb[6]
        m[1] = ma[0] * mb[1] + ma[1] * mb[4] + ma[2] * mb[7]
        m[2] = ma[0] * mb[2] + ma[1] * mb[5] + ma[2] * mb[8]
        m[3] = ma[3] * mb[0] + ma[4] * mb[3] + ma[5] * mb[6]
        m[4] = ma[3] * mb[1] + ma[4] * mb[4] + ma[5] * mb[7]
        m[5] = ma[3] * mb[2] + ma[4] * mb[5] + ma[5] * mb[8]
        m[6] = ma[6] * mb[0] + ma[7] * mb[3] + ma[8] * mb[6]
        m[7] = ma[6] * mb[1] + ma[7] * mb[4] + ma[8] * mb[7]
        m[8] = ma[6] * mb[2] + ma[7] * mb[5] + ma[8] * mb[8]
    }

    /**
     * Multiplies this matrix by a translation matrix with specified translation values.
     *
     * @param x the X translation component
     * @param y the Y translation component
     *
     * @return this matrix multiplied by the translation matrix implied by the specified values
     */
    fun multiplyByTranslation(x: Double, y: Double) = apply {
        multiplyByMatrix(1.0, 0.0, x, 0.0, 1.0, y, 0.0, 0.0, 1.0)
    }

    /**
     * Multiplies this matrix by a rotation matrix about a specified axis and angle. Positive angles are interpreted as
     * counter-clockwise rotation.
     *
     * @param angle the angle of rotation
     *
     * @return this matrix multiplied by the rotation matrix implied by the specified values
     */
    fun multiplyByRotation(angle: Angle) = apply {
        val c = cos(angle.inRadians)
        val s = sin(angle.inRadians)
        multiplyByMatrix(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)
    }

    /**
     * Multiplies this matrix by a scale matrix with specified values.
     *
     * @param xScale the X scale component
     * @param yScale the Y scale component
     *
     * @return this matrix multiplied by the scale matrix implied by the specified values
     */
    fun multiplyByScale(xScale: Double, yScale: Double) = apply {
        multiplyByMatrix(xScale, 0.0, 0.0, 0.0, yScale, 0.0, 0.0, 0.0, 1.0)
    }

    /**
     * Multiplies this matrix by a matrix that flips and shifts the y-axis. The vertical flip matrix maps Y=0 to Y=1 and
     * Y=1 to Y=0. This matrix is usually used to change the coordinate origin from an upper left coordinate origin to a
     * lower left coordinate origin.
     * <br>
     * This is typically necessary to align the coordinate system of images (top-left origin) with that of OpenGL
     * (bottom-left origin).
     *
     * @return this matrix multiplied by a vertical flip matrix implied by values described above
     */
    fun multiplyByVerticalFlip() = apply {
        m[2] += m[1]
        m[5] += m[4]
        m[8] += m[7]
        m[1] = -m[1]
        m[4] = -m[4]
        m[7] = -m[7]
    }

    /**
     * Multiplies this matrix by a matrix that transforms normalized coordinates from a source sector to a destination
     * sector. Normalized coordinates within a sector range from 0 to 1, with (0, 0) indicating the lower left corner
     * and (1, 1) indicating the upper right. The resultant matrix maps a normalized source coordinate (X, Y) to its
     * corresponding normalized destination coordinate (X', Y').
     * <br>
     * This matrix typically necessary to transform texture coordinates from one geographic region to another. For
     * example, the texture coordinates for a terrain tile spanning one region must be transformed to coordinates
     * appropriate for an image tile spanning a potentially different region.
     *
     * @param src the source sector
     * @param dst the destination sector
     *
     * @return this matrix multiplied by the transform matrix implied by values described above
     */
    fun multiplyByTileTransform(src: Sector, dst: Sector) = apply {
        val srcDeltaLat = src.deltaLatitude.inDegrees
        val srcDeltaLon = src.deltaLongitude.inDegrees
        val dstDeltaLat = dst.deltaLatitude.inDegrees
        val dstDeltaLon = dst.deltaLongitude.inDegrees
        val xs = srcDeltaLon / dstDeltaLon
        val ys = srcDeltaLat / dstDeltaLat
        val xt = (src.minLongitude.inDegrees - dst.minLongitude.inDegrees) / dstDeltaLon
        val yt = (src.minLatitude.inDegrees - dst.minLatitude.inDegrees) / dstDeltaLat

        m[2] += m[0] * xt + m[1] * yt
        m[5] += m[3] * xt + m[4] * yt
        m[8] += m[6] * xt + m[6] * yt
        m[0] *= xs
        m[1] *= ys
        m[3] *= xs
        m[4] *= ys
        m[6] *= xs
        m[7] *= ys
    }

    /**
     * Multiplies this matrix by a specified matrix.
     *
     * @param matrix the matrix to multiply with this matrix
     *
     * @return this matrix after multiplying it by the specified matrix
     */
    fun multiplyByMatrix(matrix: Matrix3) = apply {
        val ma = m
        val mb = matrix.m
        var ma0 = ma[0]
        var ma1 = ma[1]
        var ma2 = ma[2]
        ma[0] = ma0 * mb[0] + ma1 * mb[3] + ma2 * mb[6]
        ma[1] = ma0 * mb[1] + ma1 * mb[4] + ma2 * mb[7]
        ma[2] = ma0 * mb[2] + ma1 * mb[5] + ma2 * mb[8]
        ma0 = ma[3]
        ma1 = ma[4]
        ma2 = ma[5]
        ma[3] = ma0 * mb[0] + ma1 * mb[3] + ma2 * mb[6]
        ma[4] = ma0 * mb[1] + ma1 * mb[4] + ma2 * mb[7]
        ma[5] = ma0 * mb[2] + ma1 * mb[5] + ma2 * mb[8]
        ma0 = ma[6]
        ma1 = ma[7]
        ma2 = ma[8]
        ma[6] = ma0 * mb[0] + ma1 * mb[3] + ma2 * mb[6]
        ma[7] = ma0 * mb[1] + ma1 * mb[4] + ma2 * mb[7]
        ma[8] = ma0 * mb[2] + ma1 * mb[5] + ma2 * mb[8]
    }

    /**
     * Multiplies this matrix by a matrix specified by individual components.
     *
     * @param m11 matrix element at row 1, column 1
     * @param m12 matrix element at row 1, column 2
     * @param m13 matrix element at row 1, column 3
     * @param m21 matrix element at row 2, column 1
     * @param m22 matrix element at row 2, column 2
     * @param m23 matrix element at row 2, column 3
     * @param m31 matrix element at row 3, column 1
     * @param m32 matrix element at row 3, column 2
     * @param m33 matrix element at row 3, column 3
     *
     * @return this matrix with its components multiplied by the specified values
     */
    fun multiplyByMatrix(
        m11: Double, m12: Double, m13: Double,
        m21: Double, m22: Double, m23: Double,
        m31: Double, m32: Double, m33: Double
    ) = apply {
        var mr1 = m[0]
        var mr2 = m[1]
        var mr3 = m[2]
        m[0] = mr1 * m11 + mr2 * m21 + mr3 * m31
        m[1] = mr1 * m12 + mr2 * m22 + mr3 * m32
        m[2] = mr1 * m13 + mr2 * m23 + mr3 * m33
        mr1 = m[3]
        mr2 = m[4]
        mr3 = m[5]
        m[3] = mr1 * m11 + mr2 * m21 + mr3 * m31
        m[4] = mr1 * m12 + mr2 * m22 + mr3 * m32
        m[5] = mr1 * m13 + mr2 * m23 + mr3 * m33
        mr1 = m[6]
        mr2 = m[7]
        mr3 = m[8]
        m[6] = mr1 * m11 + mr2 * m21 + mr3 * m31
        m[7] = mr1 * m12 + mr2 * m22 + mr3 * m32
        m[8] = mr1 * m13 + mr2 * m23 + mr3 * m33
    }

    /**
     * Transposes this matrix in place.
     *
     * @return this matrix, transposed.
     */
    fun transpose() = apply {
        var tmp = m[1]
        m[1] = m[3]
        m[3] = tmp

        tmp = m[2]
        m[2] = m[6]
        m[6] = tmp

        tmp = m[5]
        m[5] = m[7]
        m[7] = tmp
    }

    /**
     * Transposes the specified matrix and stores the result in this matrix.
     *
     * @param matrix the matrix whose transpose is computed
     *
     * @return this matrix set to the transpose of the specified matrix
     */
    fun transposeMatrix(matrix: Matrix3) = apply {
        m[0] = matrix.m[0]
        m[1] = matrix.m[3]
        m[2] = matrix.m[6]
        m[3] = matrix.m[1]
        m[4] = matrix.m[4]
        m[5] = matrix.m[7]
        m[6] = matrix.m[2]
        m[7] = matrix.m[5]
        m[8] = matrix.m[8]
    }

    /**
     * Transposes this matrix, storing the result in the specified single precision array. The result is compatible with
     * GLSL uniform matrices, and can be passed to the function glUniformMatrix3fv.
     *
     * @param result a pre-allocated array of length 9 in which to return the transposed components
     *
     * @return the result argument set to the transposed components
     */
    fun transposeToArray(result: FloatArray, offset: Int): FloatArray {
        var o = offset
        require(result.size - o >= 9) {
            logMessage(ERROR, "Matrix4", "transposeToArray", "missingArray")
        }
        result[o++] = m[0].toFloat()
        result[o++] = m[3].toFloat()
        result[o++] = m[6].toFloat()
        result[o++] = m[1].toFloat()
        result[o++] = m[4].toFloat()
        result[o++] = m[7].toFloat()
        result[o++] = m[2].toFloat()
        result[o++] = m[5].toFloat()
        result[o] = m[8].toFloat()
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
    fun invert(): Matrix3 {
        throw UnsupportedOperationException("Matrix3.invert is not implemented") // TODO
    }

    /**
     * Inverts the specified matrix and stores the result in this matrix.
     * <br>
     * This throws an exception if the matrix is singular.
     * <br>
     * The result of this method is undefined if this matrix is passed in as the matrix to invert.
     *
     * @param matrix the matrix whose inverse is computed
     *
     * @return this matrix set to the inverse of the specified matrix
     */
    fun invertMatrix(matrix: Matrix3): Matrix3 { 
        TODO("Matrix3.invertMatrix is not implemented")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Matrix3) return false
        return m.contentEquals(other.m)
    }

    override fun hashCode() = m.contentHashCode()

    override fun toString() =
        "Matrix3([${m[0]}, ${m[1]}, ${m[2]}], [${m[3]}, ${m[4]}, ${m[5]}], [${m[6]}, ${m[7]}, ${m[8]}])"
}