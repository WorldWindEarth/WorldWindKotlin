package earth.worldwind.globe.projection

import earth.worldwind.geom.*
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage

/**
 * Base class for 2D geographic projections that lay out the globe on the XY plane.
 *
 * Subclasses provide the projection-specific geographic-to-Cartesian math; this class supplies the standard
 * 2D-projection scaffolding: a constant up-vector, an identity-translated local frame, a ray/XY-plane intersection,
 * and the shared bulk grid/border tessellation loops.
 *
 * The bulk generators [geographicToCartesianGrid] / [geographicToCartesianBorder] hold the row/column iteration once
 * for all projections; a subclass supplies only [projectPoint] (project one geographic point to XY) and, when it can
 * hoist per-latitude work out of the longitude loop, [projectRow].
 */
abstract class Abstract2DProjection : GeographicProjection {
    final override val is2D = true
    private val scratchVec = Vec3()

    override fun geographicToCartesianNormal(ellipsoid: Ellipsoid, latitude: Angle, longitude: Angle, result: Vec3) =
        result.set(0.0, 0.0, 1.0)

    override fun geographicToCartesianTransform(
        ellipsoid: Ellipsoid, latitude: Angle, longitude: Angle, altitude: Double, result: Matrix4
    ): Matrix4 {
        val vec = geographicToCartesian(ellipsoid, latitude, longitude, altitude, 0.0, scratchVec)
        return cartesianToLocalTransform(ellipsoid, vec.x, vec.y, vec.z, result)
    }

    override fun cartesianToLocalTransform(ellipsoid: Ellipsoid, x: Double, y: Double, z: Double, result: Matrix4) = result.set(
        1.0, 0.0, 0.0, x,
        0.0, 1.0, 0.0, y,
        0.0, 0.0, 1.0, z,
        0.0, 0.0, 0.0, 1.0
    )

    override fun intersect(ellipsoid: Ellipsoid, line: Line, result: Vec3): Boolean {
        val vz = line.direction.z
        val sz = line.origin.z

        if (vz == 0.0 && sz != 0.0) return false // ray parallel to and not coincident with the XY plane
        val t = -sz / vz // intersection distance, simplified for the XY plane
        if (t < 0) return false // intersection is behind the ray's origin

        result.x = line.origin.x + line.direction.x * t
        result.y = line.origin.y + line.direction.y * t
        result.z = sz + vz * t
        return true
    }

    // ---- Bulk tessellation -------------------------------------------------------------------------------

    /** Per-latitude scalars hoisted out of the longitude loop by [projectRow] and read by [projectPoint].
     *  A subclass gives [s0]/[s1] whatever meaning its formula needs (the projected Y, a cos-latitude scale,
     *  a polar radius, a clamped latitude, …). */
    protected class ProjectionRow {
        var s0 = 0.0
        var s1 = 0.0
    }

    /** Compute the per-latitude scalars for [latRad] (radians) into [row], hoisting the latitude-only part
     *  of the projection out of the inner longitude loop. Default no-op — override when there is such a part. */
    protected open fun projectRow(ellipsoid: Ellipsoid, latRad: Double, row: ProjectionRow) {}

    /** Project the point at longitude [lonRad] (radians), its latitude already folded into [row] by
     *  [projectRow], to Cartesian X/Y in [result]. The result is origin-translated by [xOffset]/[yOffset];
     *  [offset] is the continuous-projection longitude shift (used only by the cylindrical projections).
     *  Runs in the inner loop for every grid/border point — keep it allocation-free. */
    protected abstract fun projectPoint(
        ellipsoid: Ellipsoid, lonRad: Double, row: ProjectionRow,
        xOffset: Double, yOffset: Double, offset: Double, result: Vec3,
    )

    override fun geographicToCartesianGrid(
        ellipsoid: Ellipsoid, sector: Sector, numLat: Int, numLon: Int, height: FloatArray?, verticalExaggeration: Double,
        origin: Vec3?, offset: Double, result: FloatArray, rowOffset: Int, rowStride: Int
    ): FloatArray {
        require(numLat >= 1 && numLon >= 1) {
            logMessage(ERROR, displayName, "geographicToCartesianGrid",
                "Number of latitude or longitude locations is less than one")
        }
        require(height == null || height.size >= numLat * numLon) {
            logMessage(ERROR, displayName, "geographicToCartesianGrid", "missingArray")
        }

        val minLat = sector.minLatitude.inRadians
        val maxLat = sector.maxLatitude.inRadians
        val minLon = sector.minLongitude.inRadians
        val maxLon = sector.maxLongitude.inRadians
        val deltaLat = (maxLat - minLat) / if (numLat > 1) numLat - 1 else 1
        val deltaLon = (maxLon - minLon) / if (numLon > 1) numLon - 1 else 1
        var elevIndex = 0
        val xOffset = origin?.x ?: 0.0
        val yOffset = origin?.y ?: 0.0
        val zOffset = origin?.z ?: 0.0

        val row = ProjectionRow()
        val p = Vec3()
        var rowIndex = rowOffset
        val stride = if (rowStride == 0) numLon * 3 else rowStride
        var lat = minLat
        for (latIndex in 0 until numLat) {
            if (latIndex == numLat - 1) lat = maxLat
            projectRow(ellipsoid, lat, row)

            var lon = minLon
            var colIndex = rowIndex
            for (lonIndex in 0 until numLon) {
                if (lonIndex == numLon - 1) lon = maxLon
                projectPoint(ellipsoid, lon, row, xOffset, yOffset, offset, p)
                result[colIndex++] = p.x.toFloat()
                result[colIndex++] = p.y.toFloat()
                result[colIndex++] = if (height != null) (height[elevIndex++] * verticalExaggeration - zOffset).toFloat() else 0f
                lon += deltaLon
            }
            rowIndex += stride
            lat += deltaLat
        }
        return result
    }

    override fun geographicToCartesianBorder(
        ellipsoid: Ellipsoid, sector: Sector, numLat: Int, numLon: Int, height: Float,
        origin: Vec3?, offset: Double, result: FloatArray
    ): FloatArray {
        val minLat = sector.minLatitude.inRadians
        val maxLat = sector.maxLatitude.inRadians
        val minLon = sector.minLongitude.inRadians
        val maxLon = sector.maxLongitude.inRadians
        val deltaLat = (maxLat - minLat) / (if (numLat > 1) numLat - 3 else 1)
        val deltaLon = (maxLon - minLon) / (if (numLon > 1) numLon - 3 else 1)
        val xOffset = origin?.x ?: 0.0
        val yOffset = origin?.y ?: 0.0
        val zOffset = origin?.z ?: 0.0

        val row = ProjectionRow()
        val p = Vec3()
        var resultIndex = 0
        var lat = minLat
        var lon = minLon
        for (latIndex in 0 until numLat) {
            when {
                latIndex < 2 -> lat = minLat
                latIndex < numLat - 2 -> lat += deltaLat
                else -> lat = maxLat
            }
            projectRow(ellipsoid, lat, row)

            var lonIndex = 0
            while (lonIndex < numLon) {
                when {
                    lonIndex < 2 -> lon = minLon
                    lonIndex < numLon - 2 -> lon += deltaLon
                    else -> lon = maxLon
                }
                projectPoint(ellipsoid, lon, row, xOffset, yOffset, offset, p)
                result[resultIndex++] = p.x.toFloat()
                result[resultIndex++] = p.y.toFloat()
                result[resultIndex++] = height - zOffset.toFloat()
                if (lonIndex == 0 && latIndex != 0 && latIndex != numLat - 1) {
                    val skip = numLon - 2
                    lonIndex += skip
                    resultIndex += skip * 3
                }
                lonIndex++
            }
        }
        return result
    }
}
