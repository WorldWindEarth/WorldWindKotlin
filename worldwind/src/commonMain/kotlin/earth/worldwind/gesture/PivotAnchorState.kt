package earth.worldwind.gesture

import earth.worldwind.WorldWind
import earth.worldwind.geom.Angle
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Keeps a captured geographic anchor fixed under its original screen pixel while [lookAt]'s range
 * and/or heading change. Per-frame [apply] solves for `lookAt.position` via
 *
 *     L_new = A + s · Rotate(L_begin − A, axis = up_A, angle = −(H_new − H_begin)), s = R_new / R_begin
 *
 * Pure zoom → identity rotation; pure rotation → s = 1. Sharing one instance across pinch / rotate
 * / wheel handlers means simultaneous gestures compose into one self-consistent transform instead
 * of fighting two separate anchors.
 *
 * Sign of the rotation: heading is clockwise-from-north (right-hand rule axis = −up), so an
 * increase in heading is a negative rotation around +up. Verified on the tangent-plane case
 * L_begin=(0,0), A=(E,0), Δh=+90° → L_new=(E,−E).
 */
class PivotAnchorState(private val engine: WorldWind, private val lookAt: LookAt) {
    private val anchorPos = Position()
    private val anchorCart = Vec3()
    private val beginLookAtCart = Vec3()
    private val upAtAnchor = Vec3()
    private var beginRange = 0.0
    private var beginAlt = 0.0
    private var beginHeading: Angle = Angle.ZERO
    var isValid = false
        private set

    /** Snapshots the anchor and the current lookAt state. Returns false (and leaves the state
     *  invalid, making [apply] a no-op) for off-globe screen points like the sky.
     *
     *  [anchorCartesian], when non-null, is a pre-resolved Cartesian anchor from the rendered depth
     *  buffer (e.g. a 3D Tiles surface) and takes precedence over the terrain / ellipsoid pick —
     *  letting the pivot stay fixed on visible tile geometry even with no elevation coverage. When
     *  null the original terrain-then-ellipsoid fallback applies. */
    fun capture(screenX: Double, screenY: Double, anchorCartesian: Vec3? = null): Boolean {
        isValid = if (anchorCartesian != null) {
            engine.globe.cartesianToGeographic(anchorCartesian.x, anchorCartesian.y, anchorCartesian.z, anchorPos)
            true
        } else {
            engine.pickTerrainPosition(screenX, screenY, anchorPos)
                    || engine.screenPointToGroundPosition(screenX, screenY, anchorPos)
        }
        if (!isValid) return false
        engine.globe.geographicToCartesian(
            anchorPos.latitude, anchorPos.longitude, anchorPos.altitude, anchorCart
        )
        engine.globe.geographicToCartesian(
            lookAt.position.latitude, lookAt.position.longitude, lookAt.position.altitude, beginLookAtCart
        )
        engine.globe.geographicToCartesianNormal(anchorPos.latitude, anchorPos.longitude, upAtAnchor)
        beginRange = lookAt.range
        beginAlt = lookAt.position.altitude
        beginHeading = lookAt.heading
        return true
    }

    fun invalidate() { isValid = false }

    fun apply() {
        if (!isValid || beginRange <= 0.0) return
        val s = lookAt.range / beginRange
        val theta = -(lookAt.heading - beginHeading).inRadians
        val cosT = cos(theta)
        val sinT = sin(theta)
        val oneMinusCos = 1.0 - cosT

        val vx = beginLookAtCart.x - anchorCart.x
        val vy = beginLookAtCart.y - anchorCart.y
        val vz = beginLookAtCart.z - anchorCart.z

        // Rodrigues: v' = v·cosθ + (k × v)·sinθ + k·(k·v)·(1 − cosθ), k = upAtAnchor.
        val kx = upAtAnchor.x
        val ky = upAtAnchor.y
        val kz = upAtAnchor.z
        val kDotV = kx * vx + ky * vy + kz * vz
        val crossX = ky * vz - kz * vy
        val crossY = kz * vx - kx * vz
        val crossZ = kx * vy - ky * vx
        val rx = vx * cosT + crossX * sinT + kx * kDotV * oneMinusCos
        val ry = vy * cosT + crossY * sinT + ky * kDotV * oneMinusCos
        val rz = vz * cosT + crossZ * sinT + kz * kDotV * oneMinusCos

        engine.globe.cartesianToGeographic(
            anchorCart.x + s * rx,
            anchorCart.y + s * ry,
            anchorCart.z + s * rz,
            lookAt.position
        )
        // Preserve the original lookAt altitude — the straight Cartesian chord between anchor and
        // lookAt would otherwise cut inside the curved globe and let lookAt tunnel underground.
        lookAt.position.altitude = beginAlt
    }
}
