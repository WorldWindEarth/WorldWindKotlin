package earth.worldwind.globe.projection

import earth.worldwind.geom.*
import kotlin.math.*

/**
 * Represents a polar equidistant geographic projection.
 *
 * @param isNorth `true` for the north aspect, `false` for the south aspect.
 */
open class PolarEquidistantProjection(
    /**
     * Indicates whether this projection is the north or south aspect.
     */
    var isNorth: Boolean = true
) : Abstract2DProjection() {
    override val displayName get() = if (isNorth) "North Polar" else "South Polar"

    override fun geographicToCartesian(
        ellipsoid: Ellipsoid, latitude: Angle, longitude: Angle, altitude: Double, offset: Double, result: Vec3
    ): Vec3 {
        // Formulae taken from "Map Projections -- A Working Manual", Snyder, USGS paper 1395, pg. 195.
        val latRad = latitude.inRadians
        val lonRad = longitude.inRadians
        if ((isNorth && latRad == PI / 2) || (!isNorth && latRad == -PI / 2)) {
            result.x = 0.0
            result.y = 0.0
            result.z = altitude
        } else {
            val northSouthFactor = if (isNorth) -1 else 1
            val a = ellipsoid.semiMajorAxis * (PI / 2 + latRad * northSouthFactor)
            result.x = a * sin(lonRad)
            result.y = a * cos(lonRad) * northSouthFactor
            result.z = altitude
        }
        return result
    }

    // a (the radius) depends only on latitude, so it is computed once per row. The previous inline grid
    // additionally cached cos/sin(lon) per column; this matches the Gnomonic/UPS aspects, which do not.
    override fun projectRow(ellipsoid: Ellipsoid, latRad: Double, row: ProjectionRow) {
        val northSouthFactor = if (isNorth) -1 else 1
        var a = ellipsoid.semiMajorAxis * (PI / 2 + latRad * northSouthFactor)
        if ((isNorth && latRad == PI / 2) || (!isNorth && latRad == -PI / 2)) a = 0.0
        row.s0 = a
    }

    override fun projectPoint(
        ellipsoid: Ellipsoid, lonRad: Double, row: ProjectionRow,
        xOffset: Double, yOffset: Double, offset: Double, result: Vec3,
    ) {
        val northSouthFactor = if (isNorth) -1 else 1
        result.x = row.s0 * sin(lonRad) - xOffset
        result.y = row.s0 * cos(lonRad) * northSouthFactor - yOffset
    }

    override fun cartesianToGeographic(
        ellipsoid: Ellipsoid, x: Double, y: Double, z: Double, offset: Double, result: Position
    ): Position {
        // Formulae taken from "Map Projections -- A Working Manual", Snyder, USGS paper 1395, pg. 196.
        val rho = sqrt(x * x + y * y)
        if (rho < 1.0e-4) {
            result.latitude = if (isNorth) Angle.POS90 else Angle.NEG90
            result.longitude = Angle.ZERO
            result.altitude = z
        } else {
            val c = (rho / ellipsoid.semiMajorAxis).coerceAtMost(PI)
            val latSign = if (isNorth) 1 else -1
            val ySign = if (isNorth) -1 else 1
            result.setRadians(asin(cos(c) * latSign), atan2(x, y * ySign), z)
        }
        return result
    }
}
