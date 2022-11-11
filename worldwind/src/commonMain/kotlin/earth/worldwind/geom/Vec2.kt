package earth.worldwind.geom

import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.math.sqrt

/**
 * Two-component vector with X and Y coordinates.
 */
open class Vec2(
    /**
     * The vector's X component.
     */
    var x: Double,
    /**
     * The vector's Y component.
     */
    var y: Double
) {
    /**
     * Computes the squared magnitude of this vector. This is equivalent to squaring the result of
     * `magnitude` but is potentially much more efficient.
     *
     * @return the squared magnitude of this vector
     */
    open val magnitudeSquared get() = x * x + y * y
    /**
     * Computes the magnitude of this vector.
     *
     * @return the magnitude of this vector
     */
    val magnitude get() = sqrt(magnitudeSquared)

    /**
     * Constructs a two-component vector with X and Y both 0.
     */
    constructor(): this(x = 0.0, y = 0.0)

    /**
     * Constructs a two-component vector with the X and Y of a specified vector.
     *
     * @param vector the vector specifying the components
     */
    constructor(vector: Vec2): this(vector.x, vector.y)

    /**
     * Copies this vector's components to the specified single precision array. The result is compatible with GLSL
     * uniform vectors, and can be passed to the function glUniform2fv.
     *
     * @param result a pre-allocated array of length 2 in which to return the components
     *
     * @return the result argument set to this vector's components
     */
    open fun toArray(result: FloatArray, offset: Int): FloatArray {
        var o = offset
        require(result.size - o >= 2) {
            logMessage(ERROR, "Vec2", "toArray", "missingArray")
        }
        result[o++] = x.toFloat()
        result[o] = y.toFloat()
        return result
    }

    /**
     * Computes the distance from this vector to another vector.
     *
     * @param vector the vector to compute the distance to
     *
     * @return the distance between the vectors
     */
    fun distanceTo(vector: Vec2) = sqrt(distanceToSquared(vector))

    /**
     * Computes the squared distance from this vector to a specified vector. This is equivalent to squaring the result
     * of `distanceTo` but is potentially much more efficient.
     *
     * @param vector the vector to compute the distance to
     *
     * @return the squared distance between the vectors
     */
    fun distanceToSquared(vector: Vec2): Double {
        val dx = x - vector.x
        val dy = y - vector.y
        return dx * dx + dy * dy
    }

    /**
     * Sets this vector to the specified X and Y.
     *
     * @param x the new X component
     * @param y the new Y component
     *
     * @return this vector set to the specified values
     */
    fun set(x: Double, y: Double) = apply {
        this.x = x
        this.y = y
    }

    /**
     * Sets this vector to the X and Y of a specified vector.
     *
     * @param vector the vector specifying the new components
     *
     * @return this vector with its X and Y set to that of the specified vector
     */
    fun copy(vector: Vec2) = set(vector.x, vector.y)

    /**
     * Swaps this vector with the specified vector. This vector's components are set to the values of the specified
     * vector's components, and the specified vector's components are set to the values of this vector's components.
     *
     * @param vector the vector to swap with this vector
     *
     * @return this vector set to the values of the specified vector
     */
    fun swap(vector: Vec2) = apply {
        var tmp = x
        x = vector.x
        vector.x = tmp

        tmp = y
        y = vector.y
        vector.y = tmp
    }

    /**
     * Adds a specified vector to this vector.
     *
     * @param vector the vector to add
     *
     * @return this vector after adding the specified vector to it
     */
    fun add(vector: Vec2) = apply { plusAssign(vector) }

    /**
     * Creates new vector containing sum of this and specified vectors.
     *
     * @param vector the vector to add
     *
     * @return new vector containing sum of this and specified vectors.
     */
    operator fun plus(vector: Vec2) = Vec2(this).apply { plusAssign(vector) }

    /**
     * Adds a specified vector to this vector.
     *
     * @param vector the vector to add
     */
    operator fun plusAssign(vector: Vec2) {
        x += vector.x
        y += vector.y
    }

    /**
     * Subtracts a specified vector from this vector.
     *
     * @param vector the vector to subtract
     *
     * @return this vector after subtracting the specified vector from it
     */
    fun subtract(vector: Vec2) = apply { minusAssign(vector) }

    /**
     * Creates new vector containing difference of this and specified vectors.
     *
     * @param vector the vector to subtract
     *
     * @return new vector containing difference of this and specified vectors.
     */
    operator fun minus(vector: Vec2) = Vec2(this).apply { minusAssign(vector) }

    /**
     * Subtracts a specified vector from this vector.
     *
     * @param vector the vector to subtract
     */
    operator fun minusAssign(vector: Vec2) {
        x -= vector.x
        y -= vector.y
    }

    /**
     * Multiplies this vector by a scalar.
     *
     * @param scalar the scalar to multiply this vector by
     *
     * @return this vector multiplied by the specified scalar
     */
    open fun multiply(scalar: Double) = apply { timesAssign(scalar) }

    /**
     * Creates new vector containing this vector multiplied by a scalar.
     *
     * @param scalar the scalar to multiply this vector by
     *
     * @return new vector containing this vector multiplied by a scalar.
     */
    open operator fun times(scalar: Double) = Vec2(this).apply { timesAssign(scalar) }

    /**
     * Multiplies this vector by a scalar.
     *
     * @param scalar the scalar to multiply this vector by
     */
    open operator fun timesAssign(scalar: Double) {
        x *= scalar
        y *= scalar
    }

    /**
     * Multiplies this vector by a 3x3 matrix. The multiplication is performed with an implicit Z component of 1. The
     * resultant Z component of the product is then divided through the X and Y components.
     *
     * @param matrix the matrix to multiply this vector by
     *
     * @return this vector multiplied by the specified matrix
     */
    fun multiplyByMatrix(matrix: Matrix3) = apply {
        val m = matrix.m
        val x = m[0] * x + m[1] * y + m[2]
        val y = m[3] * this.x + m[4] * y + m[5]
        val z = m[6] * this.x + m[7] * this.y + m[8]
        this.x = x / z
        this.y = y / z
    }

    /**
     * Divides this vector by a scalar.
     *
     * @param divisor the scalar to divide this vector by
     *
     * @return this vector divided by the specified scalar
     */
    open fun divide(divisor: Double) = apply { divAssign(divisor) }

    /**
     * Creates new vector containing this vector divided by a scalar.
     *
     * @param divisor the scalar to divide this vector by
     *
     * @return new vector containing this vector divided by a scalar
     */
    open operator fun div(divisor: Double) = Vec2(this).apply { divAssign(divisor) }

    /**
     * Divides this vector by a scalar.
     *
     * @param divisor the scalar to divide this vector by
     */
    open operator fun divAssign(divisor: Double) {
        x /= divisor
        y /= divisor
    }

    /**
     * Creates new vector which has components with opposite sign to the vector.
     *
     * @return new vector, which has components with opposite sign to the vector
     */
    open operator fun unaryMinus() = Vec2(this).negate()

    /**
     * Negates the components of this vector.
     *
     * @return this vector, negated
     */
    open fun negate() = apply {
        x = -x
        y = -y
    }

    /**
     * Normalizes this vector to a unit vector.
     *
     * @return this vector, normalized
     */
    open fun normalize() = apply {
        val magnitude = magnitude
        if (magnitude != 0.0) {
            x /= magnitude
            y /= magnitude
        }
    }

    /**
     * Computes the scalar dot product of this vector and a specified vector.
     *
     * @param vector the vector to multiply
     *
     * @return the dot product of the two vectors
     */
    fun dot(vector: Vec2) = x * vector.x + y * vector.y

    /**
     * Mixes (interpolates) a specified vector with this vector, modifying this vector.
     *
     * @param vector The vector to mix with this one
     * @param weight The relative weight of this vector, typically in the range [0,1]
     *
     * @return this vector modified to the mix of itself and the specified vector
     */
    fun mix(vector: Vec2, weight: Double) = apply {
        val w0 = 1 - weight
        x = x * w0 + vector.x * weight
        y = y * w0 + vector.y * weight
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Vec2) return false
        return x == other.x && y == other.y
    }

    override fun hashCode(): Int {
        var result = x.hashCode()
        result = 31 * result + y.hashCode()
        return result
    }

    override fun toString() = "Vec2(x=$x, y=$y)"
}