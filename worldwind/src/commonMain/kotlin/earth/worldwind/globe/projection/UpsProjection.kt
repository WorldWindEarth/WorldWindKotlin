package earth.worldwind.globe.projection

import earth.worldwind.geom.*
import earth.worldwind.geom.Sector.Companion.fromDegrees
import kotlin.math.*

/**
 * Represents a Universal Polar Stereographic geographic projection.
 *
 * @param isNorth `true` for the north aspect, `false` for the south aspect.
 */
open class UpsProjection(
    /**
     * Indicates whether this projection is the north or south aspect.
     */
    var isNorth: Boolean = true
) : Abstract2DProjection() {
    override val displayName get() = if (isNorth) "North UPS" else "South UPS"
    override val projectionLimits get() =
        if (isNorth) fromDegrees(0.0, -180.0, 90.0, 360.0) else fromDegrees(-90.0, -180.0, 90.0, 360.0)

    override fun geographicToCartesian(
        ellipsoid: Ellipsoid, latitude: Angle, longitude: Angle, altitude: Double, offset: Double, result: Vec3
    ): Vec3 {
        // Formulas taken from "Map Projections -- A Working Manual", Snyder, USGS paper 1395, pg. 161.
        val latRad = latitude.inRadians
        val lonRad = longitude.inRadians
        if ((isNorth && latRad == PI / 2) || (!isNorth && latRad == -PI / 2)) {
            result.x = 0.0
            result.y = 0.0
            result.z = altitude
        } else {
            val poleFactor = if (isNorth) 1 else -1
            val ecc = sqrt(ellipsoid.eccentricitySquared)
            val s = sqrt((1.0 + ecc).pow(1.0 + ecc) * (1.0 - ecc).pow(1.0 - ecc))
            var lat = latRad
            if ((isNorth && lat < 0) || (!isNorth && lat > 0)) lat = 0.0
            val sp = sin(lat * poleFactor)
            val t = sqrt(((1 - sp) / (1 + sp)) * ((1 + ecc * sp) / (1 - ecc * sp)).pow(ecc))
            val r = 2 * ellipsoid.semiMajorAxis * UPS_K0 * t / s
            result.x = r * sin(lonRad)
            result.y = -r * cos(lonRad) * poleFactor
            result.z = altitude
        }
        return result
    }

    // The polar radius r depends only on latitude, so it is computed once per row.
    override fun projectRow(ellipsoid: Ellipsoid, latRad: Double, row: ProjectionRow) {
        val ecc = sqrt(ellipsoid.eccentricitySquared)
        val s = sqrt((1.0 + ecc).pow(1.0 + ecc) * (1.0 - ecc).pow(1.0 - ecc))
        val poleFactor = if (isNorth) 1 else -1
        val clampedLat = latRad.coerceIn(projectionLimits.minLatitude.inRadians, projectionLimits.maxLatitude.inRadians)
        val sp = sin(clampedLat * poleFactor)
        val t = sqrt(((1 - sp) / (1 + sp)) * ((1 + ecc * sp) / (1 - ecc * sp)).pow(ecc))
        row.s0 = 2 * ellipsoid.semiMajorAxis * UPS_K0 * t / s // r
    }

    override fun projectPoint(
        ellipsoid: Ellipsoid, lonRad: Double, row: ProjectionRow,
        xOffset: Double, yOffset: Double, offset: Double, result: Vec3,
    ) {
        val poleFactor = if (isNorth) 1 else -1
        result.x = row.s0 * sin(lonRad) - xOffset
        result.y = -row.s0 * cos(lonRad) * poleFactor - yOffset
    }

    override fun cartesianToGeographic(
        ellipsoid: Ellipsoid, x: Double, y: Double, z: Double, offset: Double, result: Position
    ): Position {
        val ySign = if (isNorth) -1 else 1
        val lon = atan2(x, y * ySign)
        val ecc = sqrt(ellipsoid.eccentricitySquared)
        val r = sqrt(x * x + y * y)
        val s = sqrt((1.0 + ecc).pow(1.0 + ecc) * (1.0 - ecc).pow(1.0 - ecc))
        val t = r * s / (2 * ellipsoid.semiMajorAxis * UPS_K0)
        val ecc2 = ellipsoid.eccentricitySquared
        val ecc4 = ecc2 * ecc2
        val ecc6 = ecc4 * ecc2
        val ecc8 = ecc6 * ecc2
        val a = PI / 2 - 2 * atan(t)
        val b = ecc2 / 2 + 5 * ecc4 / 24 + ecc6 / 12 + 13 * ecc8 / 360
        val c = 7 * ecc4 / 48 + 29 * ecc6 / 240 + 811 * ecc8 / 11520
        val d = 7 * ecc6 / 120 + 81 * ecc8 / 1120
        val e = 4279 * ecc8 / 161280
        val ap = a - c + e
        val bp = b - 3 * d
        val cp = 2 * c - 8 * e
        val dp = 4 * d
        val ep = 8 * e
        val s2p = sin(2 * a)
        var lat = ap + s2p * (bp + s2p * (cp + s2p * (dp + ep * s2p)))
        if (!isNorth) lat = -lat
        return result.setRadians(lat, lon, z)
    }

    companion object {
        // Standard UPS scale factor.
        private const val UPS_K0 = 0.994
    }
}
