package earth.worldwind.shape

import earth.worldwind.geom.Line
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe

object RayIntersector {
    /**
     * Computes intersections between a ray and transformed triangle points.
     *
     * @param globe - The globe to use for coordinate conversions
     * @param ray - The ray to test
     * @param transformedPoints - Array of transformed triangle points
     * @param eyePoint - The eye point for distance calculations
     * @returns Array of positions where intersections occurred, sorted by distance
     */
    fun computeFirstIntersection(globe: Globe, ray: Line, transformedPoints: List<List<Vec3>>, eyePoint: Vec3): Position? {
        val intersections = computeIntersections(globe, ray, transformedPoints, eyePoint)
        return if (intersections.isNotEmpty()) intersections[0] else null
    }

    fun computeIntersections(globe: Globe, ray: Line, transformedPoints: List<List<Vec3>>, eyePoint: Vec3): List<Position> {
        val results = mutableListOf<Position>()
        val eyeDists = mutableListOf<Double>()

        for (points in transformedPoints) {
            val intersectionPoints = mutableListOf<Vec3>()
            if (ray.computeTriangleListIntersection(points, intersectionPoints)) {
                for (intersectionPoint in intersectionPoints) {
                    val position = Position()
                    globe.cartesianToGeographic(intersectionPoint.x, intersectionPoint.y, intersectionPoint.z, position)

                    // Sort by distance from eye point
                    val distance = intersectionPoint.distanceTo(eyePoint)
                    var inserted = false
                    for (i in eyeDists.indices) {
                        if (distance < eyeDists[i]) {
                            results.add(i, position)
                            eyeDists.add(i, distance)
                            inserted = true
                            break
                        }
                    }
                    if (!inserted) {
                        results.add(position)
                        eyeDists.add(distance)
                    }
                }
            }
        }

        return results
    }
}