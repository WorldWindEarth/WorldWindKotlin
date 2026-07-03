package earth.worldwind.globe.projection

import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.geom.coords.TMCoord

/**
 * Provides a Transverse Mercator ellipsoidal projection. The projection's central meridian may be specified and
 * defaults to the Prime Meridian (0 longitude). By default, the projection computes values for 30 degrees either side
 * of the central meridian; large widths may cause the projection to fail.
 *
 * The projection limits reflect the central meridian and the width, clamped to a minimum of -180 degrees and a maximum
 * of +180 degrees, so a band whose central meridian is +/-180 cannot be displayed.
 */
open class TransverseMercatorProjection(
    centralMeridian: Angle = ZERO, centralLatitude: Angle = ZERO, width: Angle = DEFAULT_WIDTH
) : Abstract2DProjection() {
    override val displayName = "Transverse Mercator"

    var centralMeridian: Angle = centralMeridian
    var centralLatitude: Angle = centralLatitude
    var width: Angle = width
    override val projectionLimits get() = makeProjectionLimits(centralMeridian, width)
    protected open val scale = 1.0

    override fun geographicToCartesian(
        ellipsoid: Ellipsoid, latitude: Angle, longitude: Angle, altitude: Double, offset: Double, result: Vec3
    ): Vec3 {
        val lat = latitude.coerceIn(MIN_LAT, MAX_LAT)
        val minLon = centralMeridian - width
        val maxLon = centralMeridian + width
        val lon = longitude.coerceIn(minLon, maxLon)
        val tm = TMCoord.fromLatLon(
            lat, lon, ellipsoid.semiMajorAxis, 1.0 / ellipsoid.inverseFlattening,
            centralLatitude, centralMeridian, 0.0, 0.0, scale
        )
        result.x = tm.easting
        result.y = tm.northing
        result.z = altitude
        return result
    }

    // Only the clamped latitude is per-row; the Transverse Mercator series runs per point (it needs longitude).
    override fun projectRow(ellipsoid: Ellipsoid, latRad: Double, row: ProjectionRow) {
        row.s0 = latRad.coerceIn(MIN_LAT.inRadians, MAX_LAT.inRadians)
    }

    override fun projectPoint(
        ellipsoid: Ellipsoid, lonRad: Double, row: ProjectionRow,
        xOffset: Double, yOffset: Double, offset: Double, result: Vec3,
    ) {
        val clampedLon = lonRad.coerceIn((centralMeridian - width).inRadians, (centralMeridian + width).inRadians)
        val tm = TMCoord.fromLatLon(
            row.s0.radians, clampedLon.radians, ellipsoid.semiMajorAxis, 1.0 / ellipsoid.inverseFlattening,
            centralLatitude, centralMeridian, 0.0, 0.0, scale
        )
        result.x = tm.easting - xOffset
        result.y = tm.northing - yOffset
    }

    override fun cartesianToGeographic(
        ellipsoid: Ellipsoid, x: Double, y: Double, z: Double, offset: Double, result: Position
    ): Position {
        val tm = TMCoord.fromTM(x, y, centralLatitude, centralMeridian, 0.0, 0.0, scale)
        return result.set(tm.latitude, tm.longitude, z)
    }

    companion object {
        val DEFAULT_WIDTH: Angle = 30.0.degrees
        // The Transverse Mercator series breaks down beyond this band; latitudes outside it are clamped before projection.
        private val MIN_LAT: Angle = (-82.0).degrees
        private val MAX_LAT: Angle = 86.0.degrees

        private fun makeProjectionLimits(centralMeridian: Angle, width: Angle): Sector {
            val minLon = (centralMeridian.inDegrees - width.inDegrees).coerceAtLeast(-180.0)
            val maxLon = (centralMeridian.inDegrees + width.inDegrees).coerceAtMost(180.0)
            return Sector(fromDegrees(-90.0), fromDegrees(90.0), fromDegrees(minLon), fromDegrees(maxLon))
        }
    }
}
