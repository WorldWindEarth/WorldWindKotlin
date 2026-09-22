package earth.worldwind.globe.terrain

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Line
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import kotlin.math.cos
import kotlin.math.sin

/**
 * Finds where a ray from a geographic position first meets the terrain.
 *
 * [altitude] and the result's altitude are above the ellipsoid. [bearing] is clockwise from north;
 * [depression] is measured down from the horizontal, so 90 degrees looks straight down.
 * Returns false when the ray meets no terrain.
 */
fun Terrain.firstHitFrom(
    globe: Globe,
    latitude: Angle,
    longitude: Angle,
    altitude: Double,
    bearing: Angle,
    depression: Angle,
    result: Position,
): Boolean {
    val origin = Vec3()
    globe.geographicToCartesian(latitude, longitude, altitude, origin)

    val up = Vec3()
    globe.geographicToCartesianNormal(latitude, longitude, up)

    // Local east-north-up frame; bearing is undefined at the poles
    val east = Vec3().cross(POLAR_AXIS, up)
    if (east.magnitudeSquared < EPSILON) return false
    east.normalize()
    val north = Vec3().cross(up, east).normalize()

    val alongGround = cos(depression.inRadians)
    val downward = sin(depression.inRadians)
    val sinBearing = sin(bearing.inRadians)
    val cosBearing = cos(bearing.inRadians)
    val direction = Vec3(
        east.x * sinBearing * alongGround + north.x * cosBearing * alongGround - up.x * downward,
        east.y * sinBearing * alongGround + north.y * cosBearing * alongGround - up.y * downward,
        east.z * sinBearing * alongGround + north.z * cosBearing * alongGround - up.z * downward,
    )
    if (direction.magnitudeSquared < EPSILON) return false
    direction.normalize()

    val hit = Vec3()
    if (!intersect(Line(origin, direction), hit)) return false
    globe.cartesianToGeographic(hit.x, hit.y, hit.z, result)
    return true
}

/** The globe's polar axis in its Cartesian frame. */
private val POLAR_AXIS = Vec3(0.0, 1.0, 0.0)

private const val EPSILON = 1e-9
