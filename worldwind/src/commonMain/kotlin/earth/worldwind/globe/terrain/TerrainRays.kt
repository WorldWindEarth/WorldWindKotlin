package earth.worldwind.globe.terrain

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Line
import earth.worldwind.geom.Matrix4
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
    scratch: TerrainRayScratch = TerrainRayScratch(),
): Boolean {
    val ray = scratch.ray
    globe.geographicToCartesian(latitude, longitude, altitude, ray.origin)
    // Local frame with east, north and up as its first three columns; at a pole, north runs along the given meridian
    val m = globe.geographicToCartesianTransform(latitude, longitude, altitude, scratch.frame).m

    val alongGround = cos(depression.inRadians)
    val east = sin(bearing.inRadians) * alongGround
    val north = cos(bearing.inRadians) * alongGround
    val up = -sin(depression.inRadians)
    ray.direction.set(
        m[0] * east + m[1] * north + m[2] * up,
        m[4] * east + m[5] * north + m[6] * up,
        m[8] * east + m[9] * north + m[10] * up,
    )

    val hit = scratch.hit
    if (!intersect(ray, hit)) return false
    globe.cartesianToGeographic(hit.x, hit.y, hit.z, result)
    return true
}

/** Reusable working storage for [firstHitFrom], so callers tracing rays every frame allocate nothing. */
class TerrainRayScratch {
    internal val ray = Line()
    internal val frame = Matrix4()
    internal val hit = Vec3()
}
