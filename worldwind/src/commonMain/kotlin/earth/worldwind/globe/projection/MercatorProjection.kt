package earth.worldwind.globe.projection

import earth.worldwind.geom.*
import earth.worldwind.geom.Sector.Companion.fromDegrees
import kotlin.math.*

open class MercatorProjection : Abstract2DProjection() {
    override val displayName = "Mercator"
    override val isContinuous = true
    override val projectionLimits = fromDegrees(-78.0, -180.0, 156.0, 360.0)

    override fun geographicToCartesian(
        ellipsoid: Ellipsoid, latitude: Angle, longitude: Angle, altitude: Double, offset: Double, result: Vec3
    ): Vec3 {
        val lat = latitude.coerceIn(projectionLimits.minLatitude, projectionLimits.maxLatitude)
        val lon = longitude.coerceIn(projectionLimits.minLongitude, projectionLimits.maxLongitude)

        // See "Map Projections: A Working Manual", page 44 for the source of the below formulas.
        val ecc = sqrt(ellipsoid.eccentricitySquared)
        val sinPhi = sin(lat.inRadians)
        val s = (1 + sinPhi) / (1 - sinPhi) * ((1 - ecc * sinPhi) / (1 + ecc * sinPhi)).pow(ecc)
        result.x = ellipsoid.semiMajorAxis * lon.inRadians + offset
        result.y = 0.5 * ellipsoid.semiMajorAxis * ln(s)
        result.z = altitude
        return result
    }

    // Latitude is constant per row: the projected Y (a function of latitude) is computed once here.
    override fun projectRow(ellipsoid: Ellipsoid, latRad: Double, row: ProjectionRow) {
        val ecc = sqrt(ellipsoid.eccentricitySquared)
        val sinLat = sin(latRad.coerceIn(projectionLimits.minLatitude.inRadians, projectionLimits.maxLatitude.inRadians))
        val s = (1 + sinLat) / (1 - sinLat) * ((1 - ecc * sinLat) / (1 + ecc * sinLat)).pow(ecc)
        row.s0 = ellipsoid.semiMajorAxis * ln(s) * 0.5 // y
    }

    override fun projectPoint(
        ellipsoid: Ellipsoid, lonRad: Double, row: ProjectionRow,
        xOffset: Double, yOffset: Double, offset: Double, result: Vec3,
    ) {
        val lon = lonRad.coerceIn(projectionLimits.minLongitude.inRadians, projectionLimits.maxLongitude.inRadians)
        result.x = ellipsoid.semiMajorAxis * lon - xOffset + offset
        result.y = row.s0 - yOffset
    }

    override fun cartesianToGeographic(
        ellipsoid: Ellipsoid, x: Double, y: Double, z: Double, offset: Double, result: Position
    ): Position {
        // See "Map Projections: A Working Manual", pages 44-45 for the source of the below formulas.
        val eqr = ellipsoid.semiMajorAxis
        val ecc = sqrt(ellipsoid.eccentricitySquared)
        val t = exp(-y / ellipsoid.semiMajorAxis) // eq (7-10)

        // Inverse projection requires converging series
        var phi = PI / 2 - 2 * atan(t) // eq (7-11) [first trial]
        var phiLast = 0.0
        while (abs(phi - phiLast) > CONVERGENCE_FACTOR) {
            phiLast = phi
            phi = PI / 2.0 - 2.0 * atan(t * ((1 - ecc * sin(phi)) / (1 + ecc * sin(phi))).pow(ecc / 2.0)) // eq (7-9)
        }

        val lambda = (x - offset) / eqr // eq (7-12)

        return result.setRadians(phi, lambda, z)
    }

    companion object {
        private const val CONVERGENCE_FACTOR = 0.000001
    }
}
