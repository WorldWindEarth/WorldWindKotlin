package earth.worldwind.geom

import earth.worldwind.geom.Angle.Companion.fromRadians
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rigid rotation on a unit sphere that takes [from] to [to], applied via Rodrigues' formula.
 *
 * Preserves angular distances between all rotated points, so dragging a shape by moving its
 * reference position with this transform preserves its geodesic shape — including near the poles
 * where geographic-azimuth-based translations distort.
 */
class SphericalRotation(from: Location, to: Location) {
    private val axisX: Double
    private val axisY: Double
    private val axisZ: Double
    private val cosAngle: Double
    private val sinAngle: Double
    private val isIdentity: Boolean

    init {
        val fLat = from.latitude.inRadians
        val fLon = from.longitude.inRadians
        val tLat = to.latitude.inRadians
        val tLon = to.longitude.inRadians
        val fx = cos(fLat) * cos(fLon)
        val fy = cos(fLat) * sin(fLon)
        val fz = sin(fLat)
        val tx = cos(tLat) * cos(tLon)
        val ty = cos(tLat) * sin(tLon)
        val tz = sin(tLat)
        // Rotation axis: f × t. Its magnitude equals sin(angle) between the unit vectors.
        val ax = fy * tz - fz * ty
        val ay = fz * tx - fx * tz
        val az = fx * ty - fy * tx
        sinAngle = sqrt(ax * ax + ay * ay + az * az)
        cosAngle = (fx * tx + fy * ty + fz * tz).coerceIn(-1.0, 1.0)
        isIdentity = sinAngle < EPS
        if (!isIdentity) {
            axisX = ax / sinAngle; axisY = ay / sinAngle; axisZ = az / sinAngle
        } else {
            // from == to (dot=1) — identity. Antipodal (dot=-1) is undefined; treat as identity
            // since it only occurs when the user drags to the opposite side of the globe, which
            // doesn't have a canonical rigid motion from a single pair of points anyway.
            axisX = 0.0; axisY = 0.0; axisZ = 1.0
        }
    }

    /** Rotates [pos] in-place so the geodesic relationship between [from] and [pos] is preserved around [to]. */
    fun apply(pos: Location) {
        if (isIdentity) return
        val lat = pos.latitude.inRadians
        val lon = pos.longitude.inRadians
        val px = cos(lat) * cos(lon)
        val py = cos(lat) * sin(lon)
        val pz = sin(lat)
        // Rodrigues: p' = p cosθ + (k × p) sinθ + k (k·p) (1 − cosθ)
        val kDotP = axisX * px + axisY * py + axisZ * pz
        val cpx = axisY * pz - axisZ * py
        val cpy = axisZ * px - axisX * pz
        val cpz = axisX * py - axisY * px
        val oneMinusCos = 1.0 - cosAngle
        val nx = px * cosAngle + cpx * sinAngle + axisX * kDotP * oneMinusCos
        val ny = py * cosAngle + cpy * sinAngle + axisY * kDotP * oneMinusCos
        val nz = pz * cosAngle + cpz * sinAngle + axisZ * kDotP * oneMinusCos
        pos.latitude = fromRadians(asin(nz.coerceIn(-1.0, 1.0)))
        pos.longitude = fromRadians(atan2(ny, nx))
    }

    companion object {
        private const val EPS = 1.0e-10
    }
}
