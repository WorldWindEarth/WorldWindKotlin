package earth.worldwind.geom

import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.math.sqrt

/**
 * Three-component vector with X, Y and Z coordinates.
 */
open class Vec3(
    /**
     * The vector's X component.
     */
    x: Double,
    /**
     * The vector's Y component.
     */
    y: Double,
    /**
     * The vector's Z component.
     */
    var z: Double
): Vec2(x, y) {
    /**
     * Computes the squared magnitude of this vector. This is equivalent to squaring the result of
     * `magnitude` but is potentially much more efficient.
     */
    override val magnitudeSquared get() = super.magnitudeSquared + z * z

    /**
     * Constructs a three-component vector with X, Y and Z all 0.
     */
    constructor(): this(x = 0.0, y = 0.0, z = 0.0)

    /**
     * Constructs a three-component vector with the X, Y and Z of a specified vector.
     *
     * @param vector the vector specifying the components
     */
    constructor(vector: Vec3): this(vector.x, vector.y, vector.z)

    /**
     * Copies this vector's components to the specified single precision array. The result is compatible with GLSL
     * uniform vectors, and can be passed to the function glUniform3fv.
     *
     * @param result a pre-allocated array of length 3 in which to return the components
     *
     * @return the result argument set to this vector's components
     */
    override fun toArray(result: FloatArray, offset: Int): FloatArray {
        var o = offset
        require(result.size - o >= 3) {
            logMessage(ERROR, "Vec3", "toArray", "missingArray")
        }
        result[o++] = x.toFloat()
        result[o++] = y.toFloat()
        result[o] = z.toFloat()
        return result
    }

    /**
     * Computes the distance from this vector to another vector.
     *
     * @param vector The vector to compute the distance to
     *
     * @return the distance between the vectors
     */
    fun distanceTo(vector: Vec3) = sqrt(distanceToSquared(vector))

    /**
     * Computes the squared distance from this vector to a specified vector. This is equivalent to squaring the result
     * of `distanceTo` but is potentially much more efficient.
     *
     * @param vector the vector to compute the distance to
     *
     * @return the squared distance between the vectors
     */
    fun distanceToSquared(vector: Vec3): Double {
        val dx = x - vector.x
        val dy = y - vector.y
        val dz = z - vector.z
        return dx * dx + dy * dy + dz * dz
    }

    /**
     * Sets this vector to the specified X, Y and Z.
     *
     * @param x the new X component
     * @param y the new Y component
     * @param z the new Z component
     *
     * @return this vector set to the specified values
     */
    fun set(x: Double, y: Double, z: Double) = apply {
        set(x, y)
        this.z = z
    }

    /**
     * Sets this vector to the X, Y and Z of a specified vector.
     *
     * @param vector the vector specifying the new components
     *
     * @return this vector with its X, Y and Z set to that of the specified vector
     */
    fun copy(vector: Vec3) = set(vector.x, vector.y, vector.z)

    /**
     * Swaps this vector with the specified vector. This vector's components are set to the values of the specified
     * vector's components, and the specified vector's components are set to the values of this vector's components.
     *
     * @param vector the vector to swap with this vector
     *
     * @return this vector set to the values of the specified vector
     */
    fun swap(vector: Vec3) = apply {
        super.swap(vector)
        val tmp = z
        z = vector.z
        vector.z = tmp
    }

    /**
     * Adds a specified vector to this vector.
     *
     * @param vector the vector to add
     *
     * @return this vector after adding the specified vector to it
     */
    fun add(vector: Vec3) = apply {
        super.add(vector)
        z += vector.z
    }

    /**
     * Subtracts a specified vector from this vector.
     *
     * @param vector the vector to subtract
     *
     * @return this vector after subtracting the specified vector from it
     */
    fun subtract(vector: Vec3) = apply {
        super.subtract(vector)
        z -= vector.z
    }

    /**
     * Multiplies this vector by a scalar.
     *
     * @param scalar the scalar to multiply this vector by
     *
     * @return this vector multiplied by the specified scalar
     */
    override fun multiply(scalar: Double) = apply {
        super.multiply(scalar)
        z *= scalar
    }

    /**
     * Multiplies this vector by a 4x4 matrix. The multiplication is performed with an implicit W component of 1. The
     * resultant W component of the product is then divided through the X, Y, and Z components.
     *
     * @param matrix the matrix to multiply this vector by
     *
     * @return this vector multiplied by the specified matrix
     */
    fun multiplyByMatrix(matrix: Matrix4) = apply {
        val m = matrix.m
        val x = m[0] * x + m[1] * y + m[2] * z + m[3]
        val y = m[4] * this.x + m[5] * y + m[6] * z + m[7]
        val z = m[8] * this.x + m[9] * this.y + m[10] * z + m[11]
        val w = m[12] * this.x + m[13] * this.y + m[14] * this.z + m[15]
        this.x = x / w
        this.y = y / w
        this.z = z / w
    }

    /**
     * Divides this vector by a scalar.
     *
     * @param divisor the scalar to divide this vector by
     *
     * @return this vector divided by the specified scalar
     */
    override fun divide(divisor: Double) = apply {
        super.divide(divisor)
        z /= divisor
    }

    /**
     * Negates the components of this vector.
     *
     * @return this vector, negated
     */
    override fun negate() = apply {
        super.negate()
        z = -z
    }

    /**
     * Normalizes this vector to a unit vector.
     *
     * @return this vector, normalized
     */
    override fun normalize() = apply {
        val magnitude = magnitude
        if (magnitude != 0.0) {
            x /= magnitude
            y /= magnitude
            z /= magnitude
        }
    }

    /**
     * Computes the scalar dot product of this vector and a specified vector.
     *
     * @param vector the vector to multiply
     *
     * @return the dot product of the two vectors
     */
    fun dot(vector: Vec3) = super.dot(vector) + z * vector.z

    /**
     * Computes the cross product of this vector and a specified vector, modifying this vector.
     *
     * @param vector the vector to cross with this vector
     *
     * @return this vector set to the cross product of itself and the specified vector
     */
    fun cross(vector: Vec3) = apply {
        val x = y * vector.z - z * vector.y
        val y = z * vector.x - this.x * vector.z
        val z = this.x * vector.y - this.y * vector.x
        this.x = x
        this.y = y
        this.z = z
    }

    /**
     * Computes the cross product of two vectors, setting this vector to the result.
     *
     * @param a the first vector
     * @param b the second vector
     *
     * @return this vector set to the cross product of the two specified vectors
     */
    fun cross(a: Vec3, b: Vec3) = apply {
        x = a.y * b.z - a.z * b.y
        y = a.z * b.x - a.x * b.z
        z = a.x * b.y - a.y * b.x
    }

    /**
     * Mixes (interpolates) a specified vector with this vector, modifying this vector.
     *
     * @param vector The vector to mix with this one
     * @param weight The relative weight of this vector, typically in the range [0,1]
     *
     * @return this vector modified to the mix of itself and the specified vector
     */
    fun mix(vector: Vec3, weight: Double) = apply {
        val w0 = 1 - weight
        x = x * w0 + vector.x * weight
        y = y * w0 + vector.y * weight
        z = z * w0 + vector.z * weight
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Vec3) return false
        if (!super.equals(other)) return false
        return z == other.z
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + z.hashCode()
        return result
    }

    override fun toString() = "Vec3(x=$x, y=$y, z=$z)"
}