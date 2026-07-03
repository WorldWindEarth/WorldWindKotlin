package earth.worldwind.globe.projection

import earth.worldwind.geom.*
import kotlin.math.*

/**
 * Provides a Sinusoidal spherical projection.
 */
open class SinusoidalProjection : Abstract2DProjection() {
    override val displayName = "Sinusoidal"

    override fun geographicToCartesian(
        ellipsoid: Ellipsoid, latitude: Angle, longitude: Angle, altitude: Double, offset: Double, result: Vec3
    ): Vec3 {
        val latCos = cos(latitude.inRadians)
        result.x = if (latCos > 0) ellipsoid.semiMajorAxis * longitude.inRadians * latCos else 0.0
        result.y = ellipsoid.semiMajorAxis * latitude.inRadians
        result.z = altitude
        return result
    }

    override fun projectRow(ellipsoid: Ellipsoid, latRad: Double, row: ProjectionRow) {
        row.s0 = ellipsoid.semiMajorAxis * latRad // y
        var cosLat = cos(latRad)
        if (cosLat < 0) cosLat = 0.0
        row.s1 = cosLat
    }

    override fun projectPoint(
        ellipsoid: Ellipsoid, lonRad: Double, row: ProjectionRow,
        xOffset: Double, yOffset: Double, offset: Double, result: Vec3,
    ) {
        result.x = ellipsoid.semiMajorAxis * lonRad * row.s1 - xOffset
        result.y = row.s0 - yOffset
    }

    override fun cartesianToGeographic(
        ellipsoid: Ellipsoid, x: Double, y: Double, z: Double, offset: Double, result: Position
    ): Position {
        val eqr = ellipsoid.semiMajorAxis
        val latRadians = (y / eqr).coerceIn(-PI / 2, PI / 2)
        val latCos = cos(latRadians)
        val lonRadians = if (latCos > 0) (x / (eqr * latCos)).coerceIn(-PI, PI) else 0.0
        return result.setRadians(latRadians, lonRadians, z)
    }
}
