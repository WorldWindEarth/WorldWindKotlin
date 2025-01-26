package earth.worldwind.geom

import earth.worldwind.globe.Globe
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Represents a bounding box in Cartesian coordinates. Typically used as a bounding volume.
 */
open class BoundingBox {
    /**
     * The box's center point.
     */
    internal val center = Vec3(0.0, 0.0, 0.0)
    /**
     * The center point of the box's bottom. (The origin of the R axis.)
     */
    protected val bottomCenter = Vec3(-0.5, 0.0, 0.0)
    /**
     * The center point of the box's top. (The end of the R axis.)
     */
    protected val topCenter = Vec3(0.5, 0.0, 0.0)
    /**
     * The box's R axis, its longest axis.
     */
    protected val r = Vec3(1.0, 0.0, 0.0)
    /**
     * The box's S axis, its mid-length axis.
     */
    protected val s = Vec3(0.0, 1.0, 0.0)
    /**
     * The box's T axis, its shortest axis.
     */
    protected val t = Vec3(0.0, 0.0, 1.0)
    /**
     * The box's radius. (The half-length of its diagonal.)
     */
    protected var radius = sqrt(3.0)

    private val endPoint1 = Vec3()
    private val endPoint2 = Vec3()
    private val scratchHeights = FloatArray(NUM_LAT * NUM_LON)
    private val scratchPoints = FloatArray(NUM_LAT * NUM_LON * 3)
    private var coherentPlaneIdx = -1

    /**
     * Indicates whether this bounding box is a unit box centered at the Cartesian origin (0, 0, 0).
     *
     * @return true if this bounding box is a unit box, otherwise false
     */
    val isUnitBox get() = center.x == 0.0 && center.y == 0.0 && center.z == 0.0 && radius == sqrt(3.0)

    /**
     * Sets this bounding box to a unit box centered at the Cartesian origin (0, 0, 0).
     *
     * @return This bounding box set to a unit box
     */
    fun setToUnitBox() = apply {
        center.set(0.0, 0.0, 0.0)
        bottomCenter.set(-0.5, 0.0, 0.0)
        topCenter.set(0.5, 0.0, 0.0)

        r.set(1.0, 0.0, 0.0)
        s.set(0.0, 1.0, 0.0)
        t.set(0.0, 0.0, 1.0)

        radius = sqrt(3.0)
    }

    /**
     * Sets this bounding box such that it minimally encloses a specified array of points.
     *
     * @param array  the array of points to consider
     * @param count  the number of array elements to consider
     * @param stride the number of coordinates between the first coordinate of adjacent points - must be at least 3
     *
     * @return This bounding box set to contain the specified array of points.
     */
    fun setToPoints(array: FloatArray, count: Int, stride: Int) = apply {
        // Compute this box's axes by performing a principal component analysis on the array of points.
        val matrix = Matrix4()
        matrix.setToCovarianceOfPoints(array, count, stride)
        matrix.extractEigenvectors(r, s, t)
        r.normalize()
        s.normalize()
        t.normalize()

        // Find the extremes along each axis.
        var rMin = Double.POSITIVE_INFINITY
        var rMax = Double.NEGATIVE_INFINITY
        var sMin = Double.POSITIVE_INFINITY
        var sMax = Double.NEGATIVE_INFINITY
        var tMin = Double.POSITIVE_INFINITY
        var tMax = Double.NEGATIVE_INFINITY

        val p = Vec3()
        for (idx in 0 until count step stride) {
            p.set(array[idx].toDouble(), array[idx + 1].toDouble(), array[idx + 2].toDouble())

            val pdr = p.dot(r)
            if (rMin > pdr) rMin = pdr
            if (rMax < pdr) rMax = pdr

            val pds = p.dot(s)
            if (sMin > pds) sMin = pds
            if (sMax < pds) sMax = pds

            val pdt = p.dot(t)
            if (tMin > pdt) tMin = pdt
            if (tMax < pdt) tMax = pdt
        }

        // Ensure that the extremes along each axis have nonzero separation.
        if (rMax == rMin) rMax = rMin + 1
        if (sMax == sMin) sMax = sMin + 1
        if (tMax == tMin) tMax = tMin + 1

        // Compute the box properties from its unit axes and the extremes along each axis.
        val rLen = rMax - rMin
        val sLen = sMax - sMin
        val tLen = tMax - tMin

        val rSum = rMax + rMin
        val sSum = sMax + sMin
        val tSum = tMax + tMin

        val cx = 0.5 * (r.x * rSum + s.x * sSum + t.x * tSum)
        val cy = 0.5 * (r.y * rSum + s.y * sSum + t.y * tSum)
        val cz = 0.5 * (r.z * rSum + s.z * sSum + t.z * tSum)

        val rx2 = 0.5 * r.x * rLen
        val ry2 = 0.5 * r.y * rLen
        val rz2 = 0.5 * r.z * rLen

        center.set(cx, cy, cz)
        topCenter.set(cx + rx2, cy + ry2, cz + rz2)
        bottomCenter.set(cx - rx2, cy - ry2, cz - rz2)

        r.multiply(rLen)
        s.multiply(sLen)
        t.multiply(tLen)

        radius = 0.5 * sqrt(rLen * rLen + sLen * sLen + tLen * tLen)
    }

    /**
     * Sets this bounding box such that it contains a specified sector on a specified globe with min and max terrain
     * height.
     * <br>
     * To create a bounding box that contains the sector at mean sea level, specify zero for the minimum and maximum
     * height. To create a bounding box that contains the terrain surface in this sector, specify the actual minimum and
     * maximum height values associated with the terrain in the sector, multiplied by the scene's vertical
     * exaggeration.
     * <br>
     *
     * @param sector    the sector for which to create the bounding box
     * @param globe     the globe associated with the sector
     * @param minHeight the minimum terrain height within the sector
     * @param maxHeight the maximum terrain height within the sector
     *
     * @return this bounding box set to contain the specified sector
     */
    fun setToSector(sector: Sector, globe: Globe, minHeight: Float, maxHeight: Float) = apply {
        // Compute the cartesian points for a 3x3 geographic grid. This grid captures enough detail to bound the
        // sector. Use minimum elevation at the corners and max elevation everywhere else.
        val heights = scratchHeights
        heights.fill(maxHeight)
        heights[0] = minHeight
        heights[2] = minHeight
        heights[6] = minHeight
        heights[8] = minHeight
        val points = scratchPoints
        globe.geographicToCartesianGrid(sector, NUM_LAT, NUM_LON, heights, null, points)

        // Compute the local coordinate axes. Since we know this box is bounding a geographic sector, we use the
        // local coordinate axes at its centroid as the box axes. Using these axes results in a box that has +-10%
        // the volume of a box with axes derived from a principal component analysis, but is faster to compute.
        val centroidLat = sector.centroidLatitude
        val centroidLon = sector.centroidLongitude
        val matrix = globe.geographicToCartesianTransform(centroidLat, centroidLon, 0.0, Matrix4())
        val m = matrix.m
        r.set(m[0], m[4], m[8])
        s.set(m[1], m[5], m[9])
        t.set(m[2], m[6], m[10])

        // Find the extremes along each axis.
        val rExtremes = doubleArrayOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        val sExtremes = doubleArrayOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        val tExtremes = doubleArrayOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)

        val p = Vec3()
        for (idx in points.indices step 3) {
            p.set(points[idx].toDouble(), points[idx + 1].toDouble(), points[idx + 2].toDouble())
            adjustExtremes(r, rExtremes, s, sExtremes, t, tExtremes, p)
        }

        // If the sector encompasses more than one hemisphere, the 3x3 grid does not capture enough detail to bound
        // the sector. The antipodal points along the parallel through the sector's centroid represent its extremes
        // in longitude. Incorporate those antipodal points into the extremes along each axis.
        if (sector.deltaLongitude.inDegrees > 180.0) {
            val altitude = maxHeight.toDouble()
            globe.geographicToCartesian(sector.centroidLatitude, sector.centroidLongitude.plusDegrees(90.0), altitude, endPoint1)
            globe.geographicToCartesian(sector.centroidLatitude, sector.centroidLongitude.minusDegrees(90.0), altitude, endPoint2)
            adjustExtremes(r, rExtremes, s, sExtremes, t, tExtremes, endPoint1)
            adjustExtremes(r, rExtremes, s, sExtremes, t, tExtremes, endPoint2)
        }

        // Sort the axes from most prominent to least prominent. The frustum intersection methods assume that the axes
        // are defined in this way.
        if (rExtremes[1] - rExtremes[0] < sExtremes[1] - sExtremes[0]) swapAxes(r, rExtremes, s, sExtremes)
        if (sExtremes[1] - sExtremes[0] < tExtremes[1] - tExtremes[0]) swapAxes(s, sExtremes, t, tExtremes)
        if (rExtremes[1] - rExtremes[0] < sExtremes[1] - sExtremes[0]) swapAxes(r, rExtremes, s, sExtremes)

        // Compute the box properties from its unit axes and the extremes along each axis.
        val rLen = rExtremes[1] - rExtremes[0]
        val sLen = sExtremes[1] - sExtremes[0]
        val tLen = tExtremes[1] - tExtremes[0]

        val rSum = rExtremes[1] + rExtremes[0]
        val sSum = sExtremes[1] + sExtremes[0]
        val tSum = tExtremes[1] + tExtremes[0]

        val cx = 0.5 * (r.x * rSum + s.x * sSum + t.x * tSum)
        val cy = 0.5 * (r.y * rSum + s.y * sSum + t.y * tSum)
        val cz = 0.5 * (r.z * rSum + s.z * sSum + t.z * tSum)

        val rx2 = 0.5 * r.x * rLen
        val ry2 = 0.5 * r.y * rLen
        val rz2 = 0.5 * r.z * rLen

        center.set(cx, cy, cz)
        topCenter.set(cx + rx2, cy + ry2, cz + rz2)
        bottomCenter.set(cx - rx2, cy - ry2, cz - rz2)

        r.multiply(rLen)
        s.multiply(sLen)
        t.multiply(tLen)

        radius = 0.5 * sqrt(rLen * rLen + sLen * sLen + tLen * tLen)
    }

    /**
     * Translates this bounding box by specified components.
     *
     * @param x the X translation component
     * @param y the Y translation component
     * @param z the Z translation component
     *
     * @return this bounding box translated by the specified components
     */
    fun translate(x: Double, y: Double, z: Double) = apply {
        center.x += x
        center.y += y
        center.z += z

        bottomCenter.x += x
        bottomCenter.y += y
        bottomCenter.z += z

        topCenter.x += x
        topCenter.y += y
        topCenter.z += z
    }

    fun distanceTo(point: Vec3): Double {
        var minDist2 = Double.POSITIVE_INFINITY

        // Start with distance to the center of the box.
        var dist2 = center.distanceToSquared(point)
        if (minDist2 > dist2) minDist2 = dist2

        // Test distance to the bottom of the R axis.
        dist2 = bottomCenter.distanceToSquared(point)
        if (minDist2 > dist2) minDist2 = dist2

        // Test distance to the top of the R axis.
        dist2 = topCenter.distanceToSquared(point)
        if (minDist2 > dist2) minDist2 = dist2

        // Test distance to the bottom of the S axis.
        endPoint1.x = center.x - 0.5 * s.x
        endPoint1.y = center.y - 0.5 * s.y
        endPoint1.z = center.z - 0.5 * s.z
        dist2 = endPoint1.distanceToSquared(point)
        if (minDist2 > dist2) minDist2 = dist2

        // Test distance to the top of the S axis.
        endPoint1.x = center.x + 0.5 * s.x
        endPoint1.y = center.y + 0.5 * s.y
        endPoint1.z = center.z + 0.5 * s.z
        dist2 = endPoint1.distanceToSquared(point)
        if (minDist2 > dist2) minDist2 = dist2
        return sqrt(minDist2)
    }

    /**
     * Indicates whether this bounding box intersects a specified frustum.
     *
     * @param frustum The frustum of interest.
     *
     * @return true if the specified frustum intersects this bounding box, otherwise false.
     */
    fun intersectsFrustum(frustum: Frustum): Boolean {
        endPoint1.copy(bottomCenter)
        endPoint2.copy(topCenter)
        // There is a high probability that the node is outside the same coherent plane as last frame.
        // Start testing against that plane hoping for fast rejection.
        val coherentPlane = if (coherentPlaneIdx >= 0) frustum.planes[coherentPlaneIdx] else null
        var idx = -1
        return coherentPlane?.let { intersectsAt(it) >= 0 } != false && frustum.planes.all { plane ->
            (++idx == coherentPlaneIdx || intersectsAt(plane) >= 0).also { if (!it) coherentPlaneIdx = idx }
        }
    }

    private fun intersectsAt(plane: Plane): Double {
        val n = plane.normal
        val effectiveRadius = 0.5 * (abs(s.dot(n)) + abs(t.dot(n)))

        // Test the distance from the first end-point.
        val dq1 = plane.dot(endPoint1)
        val bq1 = dq1 <= -effectiveRadius

        // Test the distance from the second end-point.
        val dq2 = plane.dot(endPoint2)
        val bq2 = dq2 <= -effectiveRadius
        if (bq1 && bq2) return -1.0 // endpoints more distant from plane than effective radius; box is on neg. side of plane
        if (bq1 == bq2) return 0.0 // endpoints less distant from plane than effective radius; can't draw any conclusions

        // Compute and return the endpoints of the box on the positive side of the plane
        val dot = n.x * (endPoint1.x - endPoint2.x) + n.y * (endPoint1.y - endPoint2.y) + n.z * (endPoint1.z - endPoint2.z)
        val t = (effectiveRadius + dq1) / dot

        // Truncate the line to only that in the positive half-space, e.g., inside the frustum.
        val x = (endPoint2.x - endPoint1.x) * t + endPoint1.x
        val y = (endPoint2.y - endPoint1.y) * t + endPoint1.y
        val z = (endPoint2.z - endPoint1.z) * t + endPoint1.z
        if (bq1) endPoint1.set(x, y, z) else endPoint2.set(x, y, z)
        return t
    }

    override fun toString() = "BoundingBox(center=$center, bottomCenter=$bottomCenter, topCenter=$topCenter, r=$r, s=$s, t=$t, radius=$radius)"

    companion object {
        private const val NUM_LAT = 3
        private const val NUM_LON = 3

        private fun adjustExtremes(
            r: Vec3, rExtremes: DoubleArray, s: Vec3, sExtremes: DoubleArray, t: Vec3, tExtremes: DoubleArray, p: Vec3
        ) {
            val pdr = p.dot(r)
            if (rExtremes[0] > pdr) rExtremes[0] = pdr
            if (rExtremes[1] < pdr) rExtremes[1] = pdr

            val pds = p.dot(s)
            if (sExtremes[0] > pds) sExtremes[0] = pds
            if (sExtremes[1] < pds) sExtremes[1] = pds

            val pdt = p.dot(t)
            if (tExtremes[0] > pdt) tExtremes[0] = pdt
            if (tExtremes[1] < pdt) tExtremes[1] = pdt
        }

        private fun swapAxes(a: Vec3, aExtremes: DoubleArray, b: Vec3, bExtremes: DoubleArray) {
            a.swap(b)

            var tmp = aExtremes[0]
            aExtremes[0] = bExtremes[0]
            bExtremes[0] = tmp

            tmp = aExtremes[1]
            aExtremes[1] = bExtremes[1]
            bExtremes[1] = tmp
        }
    }
}