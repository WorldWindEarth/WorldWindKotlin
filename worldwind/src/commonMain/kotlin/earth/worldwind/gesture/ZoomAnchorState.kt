package earth.worldwind.gesture

import earth.worldwind.WorldWind
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3

/**
 * Implements "zoom around a fixed screen point" by scaling the lookAt position relative to the
 * anchor in 3D Cartesian: `L_new = A + (R_new / R_begin) · (L_begin − A)`. Because the camera's
 * position is also scaled by the same factor relative to A, the anchor projects to the same screen
 * pixel after the zoom — exact regardless of tilt, heading, or where the anchor is on screen.
 *
 * The anchor is captured once at gesture/sequence start via [capture] and held fixed; subsequent
 * range changes are applied with [apply] until the next [capture] (or [invalidate]).
 *
 * Thread-affinity: caller serializes — typically used on the input/UI thread.
 */
class ZoomAnchorState(private val engine: WorldWind, private val lookAt: LookAt) {
    private val anchorPos = Position()
    private val anchorCart = Vec3()
    private val beginLookAtCart = Vec3()
    private var beginRange = 0.0
    private var beginAlt = 0.0
    var isValid = false
        private set

    /**
     * Snapshots the geographic point under ([screenX], [screenY]) plus the current lookAt position
     * in Cartesian and the current range. Returns true if a usable anchor was found (terrain pick or
     * globe-surface fallback). Off-globe screen points (sky) leave the state invalid.
     */
    fun capture(screenX: Double, screenY: Double): Boolean {
        isValid = engine.pickTerrainPosition(screenX, screenY, anchorPos)
                || engine.screenPointToGroundPosition(screenX, screenY, anchorPos)
        if (!isValid) return false
        engine.globe.geographicToCartesian(
            anchorPos.latitude, anchorPos.longitude, anchorPos.altitude, anchorCart
        )
        engine.globe.geographicToCartesian(
            lookAt.position.latitude, lookAt.position.longitude, lookAt.position.altitude, beginLookAtCart
        )
        beginRange = lookAt.range
        beginAlt = lookAt.position.altitude
        return true
    }

    /** Marks the captured anchor unusable; subsequent [apply] calls become a no-op until next [capture]. */
    fun invalidate() { isValid = false }

    /**
     * Updates `lookAt.position` so the captured anchor stays at its original screen location for
     * the current `lookAt.range`. The original lookAt altitude is preserved so the lookAt doesn't
     * tunnel under the surface as the straight Cartesian chord between anchor and lookAt cuts
     * inside the curved globe over many events.
     */
    fun apply() {
        if (!isValid || beginRange <= 0.0) return
        val s = lookAt.range / beginRange
        val nx = anchorCart.x + s * (beginLookAtCart.x - anchorCart.x)
        val ny = anchorCart.y + s * (beginLookAtCart.y - anchorCart.y)
        val nz = anchorCart.z + s * (beginLookAtCart.z - anchorCart.z)
        engine.globe.cartesianToGeographic(nx, ny, nz, lookAt.position)
        lookAt.position.altitude = beginAlt
    }
}
