package earth.worldwind.globe.projection

import earth.worldwind.geom.*
import earth.worldwind.geom.Sector.Companion.fromDegrees
import kotlin.math.*

/**
 * Represents a polar Gnomonic geographic projection.
 *
 * @param isNorth `true` for the north aspect, `false` for the south aspect.
 */
open class GnomonicProjection(
    /**
     * Indicates whether this projection is the north or south aspect.
     */
    var isNorth: Boolean = true
) : Abstract2DProjection() {
    override val displayName get() = if (isNorth) "North Gnomonic" else "South Gnomonic"
    override val projectionLimits get() =
        if (isNorth) fromDegrees(30.0, -180.0, 60.0, 360.0) else fromDegrees(-90.0, -180.0, 60.0, 360.0)

    override fun geographicToCartesian(
        ellipsoid: Ellipsoid, latitude: Angle, longitude: Angle, altitude: Double, offset: Double, result: Vec3
    ): Vec3 {
        // Formulae taken from "Map Projections -- A Working Manual", Snyder, USGS paper 1395, pg. 167.
        val latRad = latitude.inRadians
        val lonRad = longitude.inRadians
        if ((isNorth && latRad == PI / 2) || (!isNorth && latRad == -PI / 2)) {
            result.x = 0.0
            result.y = 0.0
            result.z = altitude
        } else {
            val poleFactor = if (isNorth) 1 else -1
            val a = ellipsoid.semiMajorAxis / tan(latRad) // R cot(phi)
            result.x = a * sin(lonRad) * poleFactor // eqs. 22-6, 22-10
            result.y = a * -cos(lonRad) // eqs. 22-7, 22-11
            result.z = altitude
        }
        return result
    }

    // a = R cot(phi) depends only on latitude, so it is computed once per row.
    override fun projectRow(ellipsoid: Ellipsoid, latRad: Double, row: ProjectionRow) {
        val clampedLat = latRad.coerceIn(projectionLimits.minLatitude.inRadians, projectionLimits.maxLatitude.inRadians)
        var a = ellipsoid.semiMajorAxis / tan(clampedLat)
        if ((isNorth && clampedLat == PI / 2) || (!isNorth && clampedLat == -PI / 2)) a = 0.0
        row.s0 = a
    }

    override fun projectPoint(
        ellipsoid: Ellipsoid, lonRad: Double, row: ProjectionRow,
        xOffset: Double, yOffset: Double, offset: Double, result: Vec3,
    ) {
        val poleFactor = if (isNorth) 1 else -1
        result.x = row.s0 * sin(lonRad) * poleFactor - xOffset
        result.y = row.s0 * -cos(lonRad) - yOffset
    }

    override fun cartesianToGeographic(
        ellipsoid: Ellipsoid, x: Double, y: Double, z: Double, offset: Double, result: Position
    ): Position {
        // Formulae taken from "Map Projections -- A Working Manual", Snyder, USGS paper 1395, pg. 167.
        val rho = sqrt(x * x + y * y)
        if (rho < 1.0e-4) {
            result.latitude = if (isNorth) Angle.POS90 else Angle.NEG90
            result.longitude = Angle.ZERO
            result.altitude = z
        } else {
            val c = atan2(rho, ellipsoid.semiMajorAxis).coerceAtMost(PI)
            val latSign = if (isNorth) 1 else -1
            val ySign = if (isNorth) -1 else 1
            result.setRadians(asin(cos(c) * latSign), atan2(x, y * ySign), z)
        }
        return result
    }
}
