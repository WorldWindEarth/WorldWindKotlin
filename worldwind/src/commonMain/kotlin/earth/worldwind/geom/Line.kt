package earth.worldwind.geom

import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage

/**
 * Represents a line in Cartesian coordinates.
 */
open class Line {
    /**
     * This line's origin.
     */
    val origin = Vec3()
    /**
     * This line's direction.
     */
    val direction = Vec3()

    /**
     * Constructs a line with origin and direction both zero.
     */
    constructor()

    /**
     * Constructs a line with a specified origin and direction.
     *
     * @param origin    the line's origin
     * @param direction the line's direction
     */
    constructor(origin: Vec3, direction: Vec3): this() { set(origin, direction) }

    /**
     * Constructs a line with the origin and direction from a specified line.
     *
     * @param line the line specifying origin and direction
     */
    constructor(line: Line): this(line.origin, line.direction)

    /**
     * Sets this line to a specified origin and direction.
     *
     * @param origin    the line's new origin
     * @param direction the line's new direction
     *
     * @return this line, set to the new origin and direction
     */
    fun set(origin: Vec3, direction: Vec3) = apply {
        this.origin.copy(origin)
        this.direction.copy(direction)
    }

    /**
     * Sets this line to the specified segment. This line has its origin at the first endpoint and its direction
     * extending from the first endpoint to the second.
     *
     * @param pointA the segment's first endpoint
     * @param pointB the segment's second endpoint
     *
     * @return this line, set to the specified segment
     */
    fun setToSegment(pointA: Vec3, pointB: Vec3) = apply {
        origin.copy(pointA)
        direction.set(pointB.x - pointA.x, pointB.y - pointA.y, pointB.z - pointA.z)
    }

    /**
     * Computes a Cartesian point a specified distance along this line.
     *
     * @param distance The distance from this line's origin at which to compute the point.
     * @param result   A pre-allocated [Vec3] instance in which to return the computed point.
     *
     * @return The specified result argument containing the computed point.
     */
    fun pointAt(distance: Double, result: Vec3): Vec3 {
        result.x = origin.x + direction.x * distance
        result.y = origin.y + direction.y * distance
        result.z = origin.z + direction.z * distance
        return result
    }

    /**
     * Computes the first intersection of a triangle strip with this line. This line is interpreted as a ray;
     * intersection points behind the line's origin are ignored.
     * <br>
     * The triangle strip is specified by a list of vertex points and a list of elements indicating the triangle strip
     * tessellation of those vertices. The triangle strip elements are interpreted in the same manner as OpenGL, where
     * each index indicates a vertex position rather than an actual index into the points array (e.g. a triangle strip
     * index of 1 indicates the XYZ tuple starting at array index 3).
     *
     * @param points   an array of points containing XYZ tuples
     * @param stride   the number of coordinates between the first coordinate of adjacent points - must be at least 3
     * @param elements an array of indices into the points defining the triangle strip organization
     * @param count    the number of indices to consider
     * @param result   a pre-allocated Vec3 in which to return the nearest intersection point, if any
     *
     * @return true if this line intersects the triangle strip, otherwise false
     *
     * @throws IllegalArgumentException If array is empty, if the stride is less than 3,
     * if the count is less than 0
     */
    fun triStripIntersection(points: FloatArray, stride: Int, elements: ShortArray, count: Int, result: Vec3): Boolean {
        require(points.size >= stride) {
            logMessage(ERROR, "Line", "triStripIntersection", "missingArray")
        }
        require(stride >= 3) {
            logMessage(ERROR, "Line", "triStripIntersection", "invalidStride")
        }
        require(elements.isNotEmpty()) {
            logMessage(ERROR, "Line", "triStripIntersection", "missingArray")
        }
        require(count >= 0) {
            logMessage(ERROR, "Line", "triStripIntersection", "invalidCount")
        }

        // Taken from Moller and Trumbore
        // http://www.cs.virginia.edu/~gfx/Courses/2003/ImageSynthesis/papers/Acceleration/Fast%20MinimumStorage%20RayTriangle%20Intersection.pdf

        // Adapted from the original ray-triangle intersection algorithm to optimize for ray-triangle strip
        // intersection. We optimize by reusing constant terms, replacing use of Vec3 with inline primitives, and
        // exploiting the triangle strip organization to reuse computations common to adjacent triangles. These
        // optimizations reduced worst-case terrain picking performance for Web WorldWind by approximately 50% in
        // Chrome on a 2010 iMac and a Nexus 9.
        val vx = direction.x
        val vy = direction.y
        val vz = direction.z
        val sx = origin.x
        val sy = origin.y
        val sz = origin.z
        var tMin = Double.POSITIVE_INFINITY
        val epsilon = 0.00001

        // Get the triangle strip's first vertex.
        var vertex = elements[0] * stride
        var vert1x = points[vertex++]
        var vert1y = points[vertex++]
        var vert1z = points[vertex]

        // Get the triangle strip's second vertex.
        vertex = elements[1] * stride
        var vert2x = points[vertex++]
        var vert2y = points[vertex++]
        var vert2z = points[vertex]

        // Compute the intersection of each triangle with the specified ray.
        for (idx in 2 until count) {
            // Move the last two vertices into the first two vertices. This takes advantage of the triangle strip's
            // structure and avoids redundant reads from points and elements. During the first iteration this places the
            // triangle strip's first three vertices in vert0, vert1 and vert2, respectively.
            val vert0x = vert1x
            val vert0y = vert1y
            val vert0z = vert1z
            vert1x = vert2x
            vert1y = vert2y
            vert1z = vert2z

            // Get the triangle strip's next vertex.
            vertex = elements[idx] * stride
            vert2x = points[vertex++]
            vert2y = points[vertex++]
            vert2z = points[vertex]

            // find vectors for two edges sharing point a: vert1 - vert0 and vert2 - vert0
            val edge1x = vert1x - vert0x
            val edge1y = vert1y - vert0y
            val edge1z = vert1z - vert0z
            val edge2x = vert2x - vert0x
            val edge2y = vert2y - vert0y
            val edge2z = vert2z - vert0z

            // Compute cross product of line direction and edge2
            val px = vy * edge2z - vz * edge2y
            val py = vz * edge2x - vx * edge2z
            val pz = vx * edge2y - vy * edge2x

            // Get determinant
            val det = edge1x * px + edge1y * py + edge1z * pz // edge1 dot p
            // if det is near zero then ray lies in plane of triangle
            if (det > -epsilon && det < epsilon) continue

            val invDet = 1.0 / det

            // Compute distance for vertex A to ray origin: origin - vert0
            val tx = sx - vert0x
            val ty = sy - vert0y
            val tz = sz - vert0z

            // Calculate u parameter and test bounds: 1/det * t dot p
            val u = invDet * (tx * px + ty * py + tz * pz)
            if (u < -epsilon || u > 1 + epsilon) continue

            // Prepare to test v parameter: tvec cross edge1
            val qx = ty * edge1z - tz * edge1y
            val qy = tz * edge1x - tx * edge1z
            val qz = tx * edge1y - ty * edge1x

            // Calculate v parameter and test bounds: 1/det * dir dot q
            val v = invDet * (vx * qx + vy * qy + vz * qz)
            if (v < -epsilon || u + v > 1 + epsilon) continue

            // Calculate the point of intersection on the line: t = 1/det * edge2 dot q
            val t = invDet * (edge2x * qx + edge2y * qy + edge2z * qz)
            if (t >= 0 && t < tMin) tMin = t
        }
        if (tMin != Double.POSITIVE_INFINITY) result.set(sx + vx * tMin, sy + vy * tMin, sz + vz * tMin)
        return tMin != Double.POSITIVE_INFINITY
    }

    /**
     * Computes the Cartesian intersection point(s) of a specified line with a non-indexed list of
     * triangle vertices.
     * @param points The list of triangle vertices arranged such that each
     * 3-tuple, (i,i+1,i+2), specifies a triangle.
     * @param results The Cartesian intersection point(s) if any.
     * @returns true if the line intersects any triangle, otherwise false
     */
    fun computeTriangleListIntersection(points: List<Vec3>, results: MutableList<Vec3>): Boolean {
        var iPoint = Vec3()
        for (i in points.indices step 3) {
            if (computeTriangleIntersection(points[i], points[i + 1], points[i + 2], iPoint)) {
                results.add(iPoint)
                iPoint = Vec3()
            }
        }
        return results.isNotEmpty()
    }

    /**
     * Computes the Cartesian intersection point of a specified line with a triangle. Taken from Moller and Trumbore.
     * @param vertex0 The triangle's first vertex.
     * @param vertex1 The triangle's second vertex.
     * @param vertex2 The triangle's third vertex.
     * @param result A pre-allocated Vec3 instance in which to return the computed point.
     * @returns true if the line intersects the triangle, otherwise false
     * @see https://www.cs.virginia.edu/~gfx/Courses/2003/ImageSynthesis/papers/Acceleration/Fast%20MinimumStorage%20RayTriangle%20Intersection.pdf
     */
    fun computeTriangleIntersection(vertex0: Vec3, vertex1: Vec3, vertex2: Vec3, result: Vec3): Boolean {
        val vx = direction.x
        val vy = direction.y
        val vz = direction.z
        val sx = origin.x
        val sy = origin.y
        val sz = origin.z

        // find vectors for two edges sharing point a: vertex1 - vertex0 and vertex2 - vertex0
        val edge1x = vertex1.x - vertex0.x
        val edge1y = vertex1.y - vertex0.y
        val edge1z = vertex1.z - vertex0.z
        val edge2x = vertex2.x - vertex0.x
        val edge2y = vertex2.y - vertex0.y
        val edge2z = vertex2.z - vertex0.z

        // Compute cross product of line direction and edge2
        val px = (vy * edge2z) - (vz * edge2y)
        val py = (vz * edge2x) - (vx * edge2z)
        val pz = (vx * edge2y) - (vy * edge2x)

        // Get determinant
        val det = edge1x * px + edge1y * py + edge1z * pz // edge1 dot p
        if (det > -EPSILON && det < EPSILON) return false // if det is near zero then ray lies in plane of triangle

        val invDet = 1.0 / det

        // Compute distance for vertex A to ray origin: origin - vertex0
        val tx = sx - vertex0.x
        val ty = sy - vertex0.y
        val tz = sz - vertex0.z

        // Calculate u parameter and test bounds: 1/det * t dot p
        val u = invDet * (tx * px + ty * py + tz * pz)
        if (u < -EPSILON || u > 1 + EPSILON) return false

        // Prepare to test v parameter: t cross edge1
        val qx = (ty * edge1z) - (tz * edge1y)
        val qy = (tz * edge1x) - (tx * edge1z)
        val qz = (tx * edge1y) - (ty * edge1x)

        // Calculate v parameter and test bounds: 1/det * dir dot q
        val v = invDet * (vx * qx + vy * qy + vz * qz)
        if (v < -EPSILON || u + v > 1 + EPSILON) return false

        // Calculate the point of intersection on the line: t = 1/det * edge2 dot q
        val t = invDet * (edge2x * qx + edge2y * qy + edge2z * qz)
        if (t < 0) return false else {
            result.x = sx + vx * t
            result.y = sy + vy * t
            result.z = sz + vz * t
            return true
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Line) return false
        return origin == other.origin && direction == other.direction
    }

    override fun hashCode(): Int {
        var result = origin.hashCode()
        result = 31 * result + direction.hashCode()
        return result
    }

    override fun toString() = "Line(origin=$origin, direction=$direction)"

    companion object {
        private const val EPSILON = 0.00001
    }
}