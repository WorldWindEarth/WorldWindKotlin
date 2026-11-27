package earth.worldwind.sensor

import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Forward, right and up of a sensor's frame in local east/north/up, each a `DoubleArray(3)`.
 * Shared by the ray solver and the frame overlay, so the drawn frame and the cast rays agree.
 */
internal fun sensorFrameAxes(
    azimuth: Double, depression: Double, roll: Double, f: DoubleArray, r: DoubleArray, u: DoubleArray,
) {
    val sinA = sin(azimuth)
    val cosA = cos(azimuth)
    val sinD = sin(depression)
    val cosD = cos(depression)
    f[0] = sinA * cosD; f[1] = cosA * cosD; f[2] = -sinD
    // Level right, then up = right × forward
    val rx = cosA
    val ry = -sinA
    val ux = ry * f[2]
    val uy = -rx * f[2]
    val uz = rx * f[1] - ry * f[0]
    val sinR = sin(roll)
    val cosR = cos(roll)
    r[0] = rx * cosR - ux * sinR; r[1] = ry * cosR - uy * sinR; r[2] = -uz * sinR
    u[0] = ux * cosR + rx * sinR; u[1] = uy * cosR + ry * sinR; u[2] = uz * cosR
}

/** Near plane of [frameTransform]'s depth: 1/1000 of the range keeps 24-bit depth near 1 m at the range. */
fun Sensor.frameNear() = (maxRange / 1000.0).coerceAtLeast(1.0)

/** Far plane of [frameTransform]'s depth: the range as a slant distance, with a margin. */
fun Sensor.frameFar() = (maxRange * 1.05).coerceAtLeast(frameNear() * 2.0)

/**
 * Sets [result] to the transform from Cartesian world coordinates to the sensor's frame, the way
 * a shadow map's light transform works but for a perspective camera: a world point `p` maps to
 * `(x, y, z, w)` with `w` its distance ahead of the sensor along the optical axis and `x/w`, `y/w`
 * running from -1 to 1 across the frame, left to right and bottom to top. So the point is inside
 * the frame when `w > 0` and `|x| ≤ w` and `|y| ≤ w`. `z/w` is OpenGL depth between [near] and
 * [far], so the same matrix renders the sensor's depth map and looks it up.
 *
 * Only for 3D globes without vertical exaggeration: the sensor's axes are taken from the
 * geodetic east/north/up at its position.
 */
fun Sensor.frameTransform(globe: Globe, result: Matrix4, near: Double = frameNear(), far: Double = frameFar()): Matrix4 {
    val f = DoubleArray(3)
    val r = DoubleArray(3)
    val u = DoubleArray(3)
    sensorFrameAxes(azimuth.inRadians, depression.inRadians, roll.inRadians, f, r, u)
    val origin = globe.geographicToCartesian(position.latitude, position.longitude, position.altitude, Vec3())
    // Geodetic east, north and up in WorldWind's Cartesian frame (Y towards the north pole)
    val lat = position.latitude.inRadians
    val lon = position.longitude.inRadians
    val sinLat = sin(lat)
    val cosLat = cos(lat)
    val sinLon = sin(lon)
    val cosLon = cos(lon)
    val e = doubleArrayOf(cosLon, 0.0, -sinLon)
    val n = doubleArrayOf(-sinLat * sinLon, cosLat, -sinLat * cosLon)
    val up = doubleArrayOf(cosLat * sinLon, sinLat, cosLat * cosLon)
    fun world(v: DoubleArray, axis: Int) = v[0] * e[axis] + v[1] * n[axis] + v[2] * up[axis]
    val tanH = tan(horizontalFov.inRadians / 2)
    val tanV = tan(verticalFov.inRadians / 2)
    val rx = world(r, 0) / tanH; val ry = world(r, 1) / tanH; val rz = world(r, 2) / tanH
    val ux = world(u, 0) / tanV; val uy = world(u, 1) / tanV; val uz = world(u, 2) / tanV
    val fx = world(f, 0); val fy = world(f, 1); val fz = world(f, 2)
    val fw = -(fx * origin.x + fy * origin.y + fz * origin.z)
    // OpenGL perspective depth of the distance ahead w: z = a·w + b, so z/w runs -1..1 over near..far
    val a = (far + near) / (far - near)
    val b = -2.0 * far * near / (far - near)
    return result.set(
        rx, ry, rz, -(rx * origin.x + ry * origin.y + rz * origin.z),
        ux, uy, uz, -(ux * origin.x + uy * origin.y + uz * origin.z),
        a * fx, a * fy, a * fz, a * fw + b,
        fx, fy, fz, fw,
    )
}

/**
 * The screen rectangle the ground part of this sensor's frame can cover, for scissoring the
 * frame overlay: a grid of rays across the frame is cut to where they lie between the heights
 * [lowAltitude] and [highAltitude] above the ellipsoid (the terrain's range under the frame) and
 * within the range, and those ends are projected through [modelviewProjection] into [viewport].
 * The ground the frame meets lies in that part of the frame's volume, so the rectangle holds it,
 * with [marginPixels] added for the outline and the shells' curvature.
 *
 * Writes `x, y, width, height` in window coordinates (origin bottom left, as `glScissor` takes
 * them) to [result] at [offset]. Returns false when the rectangle cannot be bounded (a point
 * behind the viewer), so the caller draws unscissored; an empty rectangle means the frame meets
 * no ground in that height band.
 */
fun Sensor.groundScreenRect(
    globe: Globe, modelviewProjection: Matrix4, viewport: earth.worldwind.geom.Viewport,
    lowAltitude: Double, highAltitude: Double, result: IntArray, offset: Int, marginPixels: Int = 4,
): Boolean {
    val f = DoubleArray(3)
    val r = DoubleArray(3)
    val u = DoubleArray(3)
    sensorFrameAxes(azimuth.inRadians, depression.inRadians, roll.inRadians, f, r, u)
    val origin = globe.geographicToCartesian(position.latitude, position.longitude, position.altitude, Vec3())
    val ground = globe.geographicToCartesian(position.latitude, position.longitude, 0.0, Vec3())
    val radius = sqrt(ground.x * ground.x + ground.y * ground.y + ground.z * ground.z)
    val lat = position.latitude.inRadians
    val lon = position.longitude.inRadians
    val sinLat = sin(lat); val cosLat = cos(lat); val sinLon = sin(lon); val cosLon = cos(lon)
    val e = doubleArrayOf(cosLon, 0.0, -sinLon)
    val n = doubleArrayOf(-sinLat * sinLon, cosLat, -sinLat * cosLon)
    val up = doubleArrayOf(cosLat * sinLon, sinLat, cosLat * cosLon)
    val tanH = tan(horizontalFov.inRadians / 2)
    val tanV = tan(verticalFov.inRadians / 2)
    val range = maxRange * 1.05
    val ox = origin.x; val oy = origin.y; val oz = origin.z
    val oo = ox * ox + oy * oy + oz * oz
    var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
    val m = modelviewProjection.m
    var bounded = true
    fun add(t: Double, dx: Double, dy: Double, dz: Double) {
        val px = ox + t * dx; val py = oy + t * dy; val pz = oz + t * dz
        val w = m[12] * px + m[13] * py + m[14] * pz + m[15]
        if (w <= 0.0) { bounded = false; return }
        val sx = viewport.x + ((m[0] * px + m[1] * py + m[2] * pz + m[3]) / w * 0.5 + 0.5) * viewport.width
        val sy = viewport.y + ((m[4] * px + m[5] * py + m[6] * pz + m[7]) / w * 0.5 + 0.5) * viewport.height
        if (sx < minX) minX = sx; if (sx > maxX) maxX = sx
        if (sy < minY) minY = sy; if (sy > maxY) maxY = sy
    }
    // A grid of directions across the frame. The corners alone are not enough: a frame's
    // lowest-pointing direction is the middle of its bottom edge, and along an edge the stretch
    // within the band can switch from ending on the low shell to ending at the range, a corner of
    // the region that only denser samples come close to
    for (sample in 0 until GRID * GRID) {
        val x = (sample % GRID) * 2.0 / (GRID - 1) - 1.0
        val y = (sample / GRID) * 2.0 / (GRID - 1) - 1.0
        // Direction in east/north/up, then in world coordinates, unit length
        val de = f[0] + x * tanH * r[0] + y * tanV * u[0]
        val dn = f[1] + x * tanH * r[1] + y * tanV * u[1]
        val du = f[2] + x * tanH * r[2] + y * tanV * u[2]
        var dx = de * e[0] + dn * n[0] + du * up[0]
        var dy = de * e[1] + dn * n[1] + du * up[1]
        var dz = de * e[2] + dn * n[2] + du * up[2]
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        dx /= len; dy /= len; dz /= len
        val b = ox * dx + oy * dy + oz * dz // half the linear term: |o + t·d|² = oo + 2bt + t²
        // Where the ray enters and leaves the shell at height h: t = -b ∓ sqrt(b² - (oo - ρ²))
        fun shell(h: Double, entering: Boolean): Double? {
            val rho = radius + h
            val disc = b * b - (oo - rho * rho)
            if (disc < 0.0) return null
            val t = if (entering) -b - sqrt(disc) else -b + sqrt(disc)
            return if (t > 0.0) t else null
        }
        val highAbove = oo > (radius + highAltitude) * (radius + highAltitude)
        // The edge's stretch within the height band: from entering the high shell (or the sensor,
        // if below it) to entering the low shell, or leaving the high shell, or the range
        val start = if (highAbove) shell(highAltitude, true) ?: continue else 0.0
        if (start > range) continue
        val end = minOf(range, shell(lowAltitude, true) ?: shell(highAltitude, false) ?: range)
        add(start, dx, dy, dz)
        add(maxOf(start, end), dx, dy, dz)
    }
    if (!bounded) return false
    if (minX > maxX) {
        result[offset] = 0; result[offset + 1] = 0; result[offset + 2] = 0; result[offset + 3] = 0
        return true
    }
    val x0 = (floor(minX).toInt() - marginPixels).coerceIn(viewport.x, viewport.x + viewport.width)
    val y0 = (floor(minY).toInt() - marginPixels).coerceIn(viewport.y, viewport.y + viewport.height)
    val x1 = (ceil(maxX).toInt() + marginPixels).coerceIn(viewport.x, viewport.x + viewport.width)
    val y1 = (ceil(maxY).toInt() + marginPixels).coerceIn(viewport.y, viewport.y + viewport.height)
    result[offset] = x0; result[offset + 1] = y0; result[offset + 2] = x1 - x0; result[offset + 3] = y1 - y0
    return true
}

/** Directions per side of the [groundScreenRect] sampling grid. */
private const val GRID = 7
