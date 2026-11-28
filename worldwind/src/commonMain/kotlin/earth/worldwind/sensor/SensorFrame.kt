package earth.worldwind.sensor

import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.globe.projection.Wgs84Projection
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
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

/**
 * The globe's Cartesian coordinates around a sensor, as east/north/up metres from it.
 *
 * On a 3D globe the rows of [toLocal] are the geodetic east, north and up at the sensor, and
 * [quad] is zero: the Cartesian frame is the Earth's own, and the frame transform is exact in it.
 *
 * On a 2D globe the Cartesian frame is the map: x and y are projected, not metres, and the map's
 * plane is not the Earth's curved surface. Near the sensor the projection is close to linear, so
 * [toLocal] is the inverse of its derivative at the sensor, taken numerically (any projection,
 * vertical exaggeration included), giving the "linear metres" `(e, n, u)` of a point on the map.
 * The true east/north/up metres of the point are those plus a second-order term,
 * `quad · (e², e·n, n²)` per axis: the ground falling away below the map's plane with the Earth's
 * curvature, and the projection's change of scale across the frame (Mercator's, growing towards
 * the poles). Taken numerically as well, from the map's own inverse, and left to third order:
 * at 60° latitude in Mercator a few centimetres 20 km out.
 */
internal class SensorLocalFrame {
    /** The sensor's Cartesian point. */
    val origin = Vec3()
    /** Row-major 3×3: a Cartesian offset from [origin] to east, north and up metres. */
    val toLocal = DoubleArray(9)
    /** Row-major 3×3, the inverse: its columns are a metre's Cartesian step east, north and up. */
    val toWorld = DoubleArray(9)
    /**
     * Row-major 3×3: per axis (east, north, up), the true metres to add per `e²`, `e·n` and `n²`
     * of the linear metres. All zero on a 3D globe; see [hasQuad].
     */
    val quad = DoubleArray(9)
    /** Whether [quad] has any term: a 2D globe. */
    var hasQuad = false
        private set
    /** The radius the 2D frame takes the Earth to have there, metres (the Gaussian mean radius); 0 on a 3D globe. */
    var radius = 0.0

    /** East/north/up linear metres of the Cartesian point ([x], [y], [z]), without the [quad] term, into [out]. */
    fun local(x: Double, y: Double, z: Double, out: DoubleArray) {
        val dx = x - origin.x; val dy = y - origin.y; val dz = z - origin.z
        val m = toLocal
        out[0] = m[0] * dx + m[1] * dy + m[2] * dz
        out[1] = m[3] * dx + m[4] * dy + m[5] * dz
        out[2] = m[6] * dx + m[7] * dy + m[8] * dz
    }

    /**
     * Sets this to the frame at [position] on [globe], at the globe's current offset. False on a 2D
     * globe where the projection has no derivative there (at its limits), and nothing can be drawn.
     */
    fun set(globe: Globe, position: Position): Boolean {
        val lat = position.latitude
        val lon = position.longitude
        globe.geographicToCartesian(lat, lon, position.altitude, origin)
        val phi = lat.inRadians
        val sinLat = sin(phi)
        val cosLat = cos(phi)
        if (!globe.is2D) {
            enuRows(phi, lon.inRadians, toLocal)
            for (i in 0..2) for (j in 0..2) toWorld[3 * j + i] = toLocal[3 * i + j]
            quad.fill(0.0)
            hasQuad = false
            radius = 0.0
            return true
        }
        // Radii of curvature along the meridian and the prime vertical: a metre in latitude and in longitude
        val a = globe.equatorialRadius
        val e2 = globe.ellipsoid.eccentricitySquared
        val w = 1.0 - e2 * sinLat * sinLat
        val primeVertical = a / sqrt(w)
        val meridian = a * (1.0 - e2) / (w * sqrt(w))
        val dLon = STEP / (primeVertical * max(cosLat, 1e-9))
        val dLat = STEP / meridian
        val p = scratchP
        val q = scratchQ
        fun column(j: Int) {
            toWorld[j] = (p.x - q.x) / (2 * STEP)
            toWorld[3 + j] = (p.y - q.y) / (2 * STEP)
            toWorld[6 + j] = (p.z - q.z) / (2 * STEP)
        }
        globe.geographicToCartesian(lat, lon.plusRadians(dLon), position.altitude, p)
        globe.geographicToCartesian(lat, lon.plusRadians(-dLon), position.altitude, q)
        column(0)
        globe.geographicToCartesian((phi + dLat).radians, lon, position.altitude, p)
        globe.geographicToCartesian((phi - dLat).radians, lon, position.altitude, q)
        column(1)
        globe.geographicToCartesian(lat, lon, position.altitude + STEP, p)
        globe.geographicToCartesian(lat, lon, position.altitude - STEP, q)
        column(2)
        if (!invert3(toWorld, toLocal)) return false
        radius = sqrt(meridian * primeVertical)
        fitQuad(globe, position)
        return true
    }

    /**
     * Fits [quad] by second differences of the true east/north/up metres over a square of linear
     * metres around the sensor: map point, through the map's inverse to geographic, onto the
     * ellipsoid, into the sensor's geodetic east/north/up.
     */
    private fun fitQuad(globe: Globe, position: Position) {
        val ellipsoid = globe.ellipsoid
        // The sensor on the ellipsoid, and its geodetic east/north/up there
        val so = scratchQ
        wgs.geographicToCartesian(ellipsoid, position.latitude, position.longitude, position.altitude, 0.0, so)
        val sx = so.x; val sy = so.y; val sz = so.z
        enuRows(position.latitude.inRadians, position.longitude.inRadians, rows)
        val geo = scratchGeo
        val p = scratchP
        val w = toWorld
        // The true minus the linear metres of axis k at linear (e, n, 0)
        fun excess(e: Double, n: Double, k: Int): Double {
            globe.cartesianToGeographic(origin.x + w[0] * e + w[1] * n, origin.y + w[3] * e + w[4] * n, origin.z + w[6] * e + w[7] * n, geo)
            wgs.geographicToCartesian(ellipsoid, geo.latitude, geo.longitude, geo.altitude, 0.0, p)
            val t = rows[3 * k] * (p.x - sx) + rows[3 * k + 1] * (p.y - sy) + rows[3 * k + 2] * (p.z - sz)
            return t - when (k) { 0 -> e; 1 -> n; else -> 0.0 }
        }
        val h = QUAD_STEP
        val h2 = h * h
        for (k in 0..2) {
            val c = excess(0.0, 0.0, k)
            quad[3 * k] = (excess(h, 0.0, k) + excess(-h, 0.0, k) - 2 * c) / (2 * h2)
            quad[3 * k + 1] = (excess(h, h, k) + excess(-h, -h, k) - excess(h, -h, k) - excess(-h, h, k)) / (4 * h2)
            quad[3 * k + 2] = (excess(0.0, h, k) + excess(0.0, -h, k) - 2 * c) / (2 * h2)
        }
        hasQuad = quad.all { it.isFinite() }
        if (!hasQuad) quad.fill(0.0)
    }

    private val wgs = Wgs84Projection()
    private val rows = DoubleArray(9)
    private val scratchP = Vec3()
    private val scratchQ = Vec3()
    private val scratchGeo = Position()

    /** The [quad] term at linear metres ([e], [n]) for [axis]. */
    fun quadAt(axis: Int, e: Double, n: Double) = quad[3 * axis] * e * e + quad[3 * axis + 1] * e * n + quad[3 * axis + 2] * n * n

    private companion object {
        /** Geodetic east, north and up at ([lat], [lon]) radians, as the rows of [out], in WorldWind's Cartesian frame (Y towards the north pole). */
        fun enuRows(lat: Double, lon: Double, out: DoubleArray) {
            val sinLat = sin(lat); val cosLat = cos(lat); val sinLon = sin(lon); val cosLon = cos(lon)
            out[0] = cosLon; out[1] = 0.0; out[2] = -sinLon
            out[3] = -sinLat * sinLon; out[4] = cosLat; out[5] = -sinLat * cosLon
            out[6] = cosLat * sinLon; out[7] = sinLat; out[8] = cosLat * cosLon
        }

        /** Metres of the central differences of a 2D projection's derivative. */
        const val STEP = 1.0
        /** Metres of the second differences of [quad]: long enough for double precision, short against the frame. */
        const val QUAD_STEP = 2000.0

        /** [m] inverted into [out], both row-major 3×3; false when [m] is singular. */
        fun invert3(m: DoubleArray, out: DoubleArray): Boolean {
            val c00 = m[4] * m[8] - m[5] * m[7]
            val c01 = m[5] * m[6] - m[3] * m[8]
            val c02 = m[3] * m[7] - m[4] * m[6]
            val det = m[0] * c00 + m[1] * c01 + m[2] * c02
            if (!det.isFinite() || abs(det) < 1e-30) return false
            val s = 1.0 / det
            out[0] = c00 * s; out[1] = (m[2] * m[7] - m[1] * m[8]) * s; out[2] = (m[1] * m[5] - m[2] * m[4]) * s
            out[3] = c01 * s; out[4] = (m[0] * m[8] - m[2] * m[6]) * s; out[5] = (m[2] * m[3] - m[0] * m[5]) * s
            out[6] = c02 * s; out[7] = (m[1] * m[6] - m[0] * m[7]) * s; out[8] = (m[0] * m[4] - m[1] * m[3]) * s
            return out.all { it.isFinite() }
        }
    }
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
 * On a 3D globe the sensor's axes are taken from the geodetic east/north/up at its position, and
 * the transform assumes no vertical exaggeration. On a 2D globe it is the projection made linear
 * at the sensor: exact there, and short of a second-order term away from it (the Earth's
 * curvature and the projection's change of scale), which the frame overlay adds back per vertex
 * (see [SensorLocalFrame]).
 */
fun Sensor.frameTransform(globe: Globe, result: Matrix4, near: Double = frameNear(), far: Double = frameFar()): Matrix4 {
    val local = SensorLocalFrame()
    if (!local.set(globe, position)) return result.set(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, -1.0)
    return frameTransform(local, result, near, far, null, 0)
}

/**
 * [frameTransform] in [local], and where [bendClip] is given, at [offset] in it, twelve floats:
 * the frame coordinates' change per `e²`, `e·n` and `n²` of the linear metres from the sensor,
 * a vec4 each, which puts the [SensorLocalFrame.quad] term back on a 2D globe:
 * `clip = frame · p + e² · A + e·n · B + n² · C`. Zero on a 3D globe.
 */
internal fun Sensor.frameTransform(
    local: SensorLocalFrame, result: Matrix4, near: Double, far: Double, bendClip: FloatArray?, offset: Int,
): Matrix4 {
    val f = DoubleArray(3)
    val r = DoubleArray(3)
    val u = DoubleArray(3)
    sensorFrameAxes(azimuth.inRadians, depression.inRadians, roll.inRadians, f, r, u)
    val origin = local.origin
    val m = local.toLocal
    // A row of the frame in east/north/up metres, as a row of the world transform: v · toLocal
    fun world(v: DoubleArray, axis: Int) = v[0] * m[axis] + v[1] * m[3 + axis] + v[2] * m[6 + axis]
    val tanH = tan(horizontalFov.inRadians / 2)
    val tanV = tan(verticalFov.inRadians / 2)
    val rx = world(r, 0) / tanH; val ry = world(r, 1) / tanH; val rz = world(r, 2) / tanH
    val ux = world(u, 0) / tanV; val uy = world(u, 1) / tanV; val uz = world(u, 2) / tanV
    val fx = world(f, 0); val fy = world(f, 1); val fz = world(f, 2)
    val fw = -(fx * origin.x + fy * origin.y + fz * origin.z)
    // OpenGL perspective depth of the distance ahead w: z = a·w + b, so z/w runs -1..1 over near..far
    val a = (far + near) / (far - near)
    val b = -2.0 * far * near / (far - near)
    if (bendClip != null) {
        // Per monomial, the frame's columns for a true metre east, north and up, weighted by the quad term
        val q = local.quad
        for (mono in 0..2) {
            var cx = 0.0; var cy = 0.0; var cw = 0.0
            for (axis in 0..2) {
                val c = q[3 * axis + mono]
                cx += c * r[axis] / tanH; cy += c * u[axis] / tanV; cw += c * f[axis]
            }
            val o = offset + 4 * mono
            bendClip[o] = cx.toFloat(); bendClip[o + 1] = cy.toFloat(); bendClip[o + 2] = (a * cw).toFloat(); bendClip[o + 3] = cw.toFloat()
        }
    }
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
    val local = SensorLocalFrame()
    if (!local.set(globe, position)) return false
    return groundScreenRect(globe, local, modelviewProjection, viewport, lowAltitude, highAltitude, result, offset, marginPixels)
}

/** [groundScreenRect] with the sensor's [local] frame already set. */
internal fun Sensor.groundScreenRect(
    globe: Globe, local: SensorLocalFrame, modelviewProjection: Matrix4, viewport: earth.worldwind.geom.Viewport,
    lowAltitude: Double, highAltitude: Double, result: IntArray, offset: Int, marginPixels: Int = 4,
): Boolean {
    val f = DoubleArray(3)
    val r = DoubleArray(3)
    val u = DoubleArray(3)
    sensorFrameAxes(azimuth.inRadians, depression.inRadians, roll.inRadians, f, r, u)
    // The shells are spheres about the Earth's centre. On a 3D globe they are worked out in its
    // Cartesian frame; on a 2D globe in east/north/up about a centre `radius + altitude` below the
    // sensor, each end then carried onto the map through the local frame, curvature and all
    val is2D = globe.is2D
    val radius: Double
    val ox: Double; val oy: Double; val oz: Double
    val e: DoubleArray; val n: DoubleArray; val up: DoubleArray
    if (is2D) {
        radius = local.radius
        ox = 0.0; oy = 0.0; oz = radius + position.altitude
        e = doubleArrayOf(1.0, 0.0, 0.0); n = doubleArrayOf(0.0, 1.0, 0.0); up = doubleArrayOf(0.0, 0.0, 1.0)
    } else {
        val ground = globe.geographicToCartesian(position.latitude, position.longitude, 0.0, Vec3())
        radius = sqrt(ground.x * ground.x + ground.y * ground.y + ground.z * ground.z)
        ox = local.origin.x; oy = local.origin.y; oz = local.origin.z
        val w = local.toWorld
        e = doubleArrayOf(w[0], w[3], w[6]); n = doubleArrayOf(w[1], w[4], w[7]); up = doubleArrayOf(w[2], w[5], w[8])
    }
    val tanH = tan(horizontalFov.inRadians / 2)
    val tanV = tan(verticalFov.inRadians / 2)
    val range = maxRange * 1.05
    val oo = ox * ox + oy * oy + oz * oz
    var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
    val m = modelviewProjection.m
    val tw = local.toWorld
    val lo = local.origin
    var bounded = true
    fun add(t: Double, dx: Double, dy: Double, dz: Double) {
        var px = ox + t * dx; var py = oy + t * dy; var pz = oz + t * dz
        if (is2D) {
            // True east/north/up from the sensor to linear metres, less the quad term the overlay adds again
            val te = px; val tn = py; val tu = pz - oz
            val le = te - local.quadAt(0, te, tn)
            val ln = tn - local.quadAt(1, te, tn)
            val lu = tu - local.quadAt(2, te, tn)
            px = lo.x + tw[0] * le + tw[1] * ln + tw[2] * lu
            py = lo.y + tw[3] * le + tw[4] * ln + tw[5] * lu
            pz = lo.z + tw[6] * le + tw[7] * ln + tw[8] * lu
        }
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
        // Direction in east/north/up, then in the shells' coordinates, unit length
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
