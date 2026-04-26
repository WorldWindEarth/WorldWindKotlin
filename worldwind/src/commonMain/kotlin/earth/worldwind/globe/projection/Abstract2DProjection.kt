package earth.worldwind.globe.projection

import earth.worldwind.geom.*

/**
 * Base class for 2D geographic projections that lay out the globe on the XY plane.
 *
 * Subclasses provide the projection-specific geographic-to-Cartesian math; this class supplies the standard
 * 2D-projection scaffolding: a constant up-vector, an identity-translated local frame, and a ray/XY-plane intersection.
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
}
