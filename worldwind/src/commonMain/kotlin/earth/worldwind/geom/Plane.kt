package earth.worldwind.geom

/**
 * Represents a plane in Cartesian coordinates. The plane's X, Y and Z components indicate the plane's normal vector.
 * The distance component indicates the plane's distance from the origin relative to its unit normal.
 */
open class Plane {
    companion object {
        protected const val NEAR_ZERO_THRESHOLD = 1e-10
    }

    /**
     * The normal vector to the plane.
     */
    val normal = Vec3(0.0, 0.0, 1.0)
    /**
     * The plane's distance from the origin.
     */
    var distance = 0.0

    /**
     * Constructs a plane in the X-Y plane with its unit normal pointing along the Z axis.
     */
    constructor()

    /**
     * Constructs a plane with specified normal vector components and distance from the origin.
     * This constructor normalizes the components, ensuring that the plane has a unit normal vector.
     *
     * @param x        the X component of the plane's normal vector
     * @param y        the Y component of the plane's normal vector
     * @param z        the Z component of the plane's normal vector
     * @param distance the plane's distance from the origin
     */
    constructor(x: Double, y: Double, z: Double, distance: Double) { set(x, y, z, distance) }

    /**
     * Constructs a plane with the normal vector and distance from a specified plane.
     *
     * @param plane the plane specifying the normal vector and distance
     */
    constructor(plane: Plane): this() { copy(plane) }

    /**
     * Computes the distance between this plane and a point.
     *
     * @param point the point whose distance to compute
     *
     * @return the computed distance
     */
    fun distanceToPoint(point: Vec3) = dot(point)

    /**
     * Sets this plane's specified normal vector and distance to specified values. This normalizes the components,
     * ensuring that the plane has a unit normal vector.
     *
     * @param x        the X component of the plane's normal vector
     * @param y        the Y component of the plane's normal vector
     * @param z        the Z component of the plane's normal vector
     * @param distance the plane's distance from the origin
     *
     * @return this plane with its normal vector and distance set to specified values
     */
    fun set(x: Double, y: Double, z: Double, distance: Double) = apply {
        normal.x = x
        normal.y = y
        normal.z = z
        this.distance = distance
        normalizeIfNeeded()
    }

    /**
     * Sets this plane's normal vector and distance to that of a specified plane.
     *
     * @param plane the plane specifying the normal vector and distance
     *
     * @return this plane with its normal vector and distance set to those of the specified plane
     */
    fun copy(plane: Plane) = apply {
        // Assumes the specified plane's parameters are normalized.
        normal.copy(plane.normal)
        distance = plane.distance
    }

    /**
     * Transforms this plane by a specified matrix.
     *
     * @param matrix the matrix to apply to this plane
     *
     * @return this plane transformed by the specified matrix
     */
    fun transformByMatrix(matrix: Matrix4) = apply {
        val m = matrix.m
        val x = m[0] * normal.x + m[1] * normal.y + m[2] * normal.z + m[3] * distance
        val y = m[4] * normal.x + m[5] * normal.y + m[6] * normal.z + m[7] * distance
        val z = m[8] * normal.x + m[9] * normal.y + m[10] * normal.z + m[11] * distance
        val distance = m[12] * normal.x + m[13] * normal.y + m[14] * normal.z + m[15] * distance
        normal.x = x
        normal.y = y
        normal.z = z
        this.distance = distance
        normalizeIfNeeded()
    }

    /**
     * Computes the dot product of this plane's components with a specified vector. Since the plane was defined with a
     * unit normal vector, this function returns the distance of the vector from the plane.
     *
     * @param vector the vector to dot with this plane's components
     *
     * @return the computed dot product
     */
    fun dot(vector: Vec3) = normal.dot(vector) + distance

    /**
     * Determines whether a specified line segment intersects this plane.
     *
     * @param endPoint1 the line segment's first end point
     * @param endPoint2 the line segment's second end point
     *
     * @return true if the line segment intersects this plane, otherwise false
     */
    fun intersectsSegment(endPoint1: Vec3, endPoint2: Vec3): Boolean {
        val distance1 = dot(endPoint1)
        val distance2 = dot(endPoint2)
        return distance1 * distance2 <= 0
    }

    /**
     * Determines whether two points are on the same side of this plane.
     *
     * @param pointA the first point
     * @param pointB the second point
     *
     * @return -1 if both points are on the negative side of this plane, +1 if both points are on the positive side of
     * this plane, 0 if the points are on opposite sides of this plane
     */
    fun onSameSide(pointA: Vec3, pointB: Vec3): Int {
        val da = distanceToPoint(pointA)
        val db = distanceToPoint(pointB)
        if (da < 0 && db < 0) return -1
        return if (da > 0 && db > 0) 1 else 0
    }

    /**
     * Clips a line segment to this plane, returning an two-point array indicating the clipped segment. If the direction
     * of the line formed by the two points is positive with respect to this plane's normal vector, the first point in
     * the array will be the intersection point on the plane, and the second point will be the original segment end
     * point. If the direction of the line is negative with respect to this plane's normal vector, the first point in
     * the array will be the original segment's begin point, and the second point will be the intersection point on the
     * plane. If the segment does not intersect the plane, null is returned. If the segment is coincident with the
     * plane, the input points are returned, in their input order.
     *
     * @param pointA the first line segment endpoint
     * @param pointB the second line segment endpoint
     *
     * @return an array of two points both on the positive side of the plane, or null if the segment does not intersect
     * this plane
     */
    fun clip(pointA: Vec3, pointB: Vec3): Array<Vec3>? {
        if (pointA == pointB) return null

        // Get the projection of the segment onto the plane.
        val line = Line().setToSegment(pointA, pointB)
        val lDotV = normal.dot(line.direction)

        // Are the line and plane parallel?
        if (lDotV == 0.0) { // line and plane are parallel and may be coincident.
            val lDotS = dot(line.origin)
            return if (lDotS == 0.0) arrayOf(pointA, pointB) // line is coincident with the plane
            else null // line is not coincident with the plane.
        }

        // Not parallel so the line intersects. But does the segment intersect?
        val t = -dot(line.origin) / lDotV // lDotS / lDotV
        if (t < 0 || t > 1) return null // segment does not intersect
        val p = line.pointAt(t, Vec3())
        return if (lDotV > 0) arrayOf(p, pointB) else arrayOf(pointA, p)
    }

    protected open fun normalizeIfNeeded() {
        // Compute the plane normal's magnitude in order to determine whether or not the plane needs normalization.
        val magnitude = normal.magnitude

        // Don't normalize a zero vector; the result is NaN when it should be 0.0.
        if (magnitude == 0.0) return

        // Don't normalize a unit vector, this indicates that the caller has already normalized the vector, but floating
        // point round-off results in a length not exactly 1.0. Since we're normalizing on the caller's behalf, we want
        // to avoid unnecessary any normalization that modifies the specified values.
        if (magnitude >= 1 - NEAR_ZERO_THRESHOLD && magnitude <= 1 + NEAR_ZERO_THRESHOLD) return

        // Normalize the caller-specified plane coordinates.
        normal.x /= magnitude
        normal.y /= magnitude
        normal.z /= magnitude
        distance /= magnitude
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Plane) return false
        return normal == other.normal && distance == other.distance
    }

    override fun hashCode(): Int {
        var result = normal.hashCode()
        result = 31 * result + distance.hashCode()
        return result
    }

    override fun toString() = "Plane(normal=$normal, distance=$distance)"
}