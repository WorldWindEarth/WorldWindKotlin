package earth.worldwind.globe.projection

import earth.worldwind.geom.*

/**
 * Implements an Equirectangular projection, also known as Equidistant Cylindrical, Plate Carree and Rectangular. The
 * projected globe is spherical, not ellipsoidal.
 */
open class EquirectangularProjection : Abstract2DProjection() {
    override val displayName = "Equirectangular"
    override val isContinuous = true

    override fun geographicToCartesian(
        ellipsoid: Ellipsoid, latitude: Angle, longitude: Angle, altitude: Double, offset: Double, result: Vec3
    ): Vec3 {
        result.x = ellipsoid.semiMajorAxis * longitude.inRadians + offset
        result.y = ellipsoid.semiMajorAxis * latitude.inRadians
        result.z = altitude
        return result
    }

    override fun projectRow(ellipsoid: Ellipsoid, latRad: Double, row: ProjectionRow) {
        row.s0 = ellipsoid.semiMajorAxis * latRad // y is constant across the row
    }

    override fun projectPoint(
        ellipsoid: Ellipsoid, lonRad: Double, row: ProjectionRow,
        xOffset: Double, yOffset: Double, offset: Double, result: Vec3,
    ) {
        result.x = ellipsoid.semiMajorAxis * lonRad - xOffset + offset
        result.y = row.s0 - yOffset
    }

    override fun cartesianToGeographic(
        ellipsoid: Ellipsoid, x: Double, y: Double, z: Double, offset: Double, result: Position
    ): Position {
        val eqr = ellipsoid.semiMajorAxis
        return result.setRadians(y / eqr, (x - offset) / eqr, z)
    }
}
