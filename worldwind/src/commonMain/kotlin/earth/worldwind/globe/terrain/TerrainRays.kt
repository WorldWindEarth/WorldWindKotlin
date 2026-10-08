package earth.worldwind.globe.terrain

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Line
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
    scratch: TerrainRayScratch = TerrainRayScratch(),
): Boolean {
    val ray = scratch.ray
    globe.geographicToCartesian(latitude, longitude, altitude, ray.origin)
    val up = scratch.up
    globe.geographicToCartesianNormal(latitude, longitude, up)

    // Local east-north-up frame. The polar axis is Y, so east = Y x up; undefined at the poles
    var eastX = up.z
    var eastZ = -up.x
    val eastLength = sqrt(eastX * eastX + eastZ * eastZ)
    if (eastLength < EPSILON) return false
    eastX /= eastLength
    eastZ /= eastLength
    // north = up x east
    val northX = up.y * eastZ
    val northY = up.z * eastX - up.x * eastZ
    val northZ = -up.y * eastX

    val alongGround = cos(depression.inRadians)
    val downward = sin(depression.inRadians)
    val east = sin(bearing.inRadians) * alongGround
    val north = cos(bearing.inRadians) * alongGround
    ray.direction.set(
        eastX * east + northX * north - up.x * downward,
        northY * north - up.y * downward,
        eastZ * east + northZ * north - up.z * downward,
    )
    if (ray.direction.magnitudeSquared < EPSILON) return false
    ray.direction.normalize()

    val hit = scratch.hit
    if (!intersect(ray, hit)) return false
    globe.cartesianToGeographic(hit.x, hit.y, hit.z, result)
    return true
}

/** Reusable working storage for [firstHitFrom], so callers tracing rays every frame allocate nothing. */
class TerrainRayScratch {
    internal val ray = Line()
    internal val up = Vec3()
    internal val hit = Vec3()
}

private const val EPSILON = 1e-9
