package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.shape.Placemark
import earth.worldwind.util.format.format
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Marker for tutorials that expose a [PickResultIndicator]. Lets the platform plumbing fetch
 * the picker uniformly without enumerating every concrete tutorial class.
 */
interface PickIndicatorTutorial {
    val picker: PickResultIndicator
}

/**
 * Drops a single leader-line placemark at the surface point reported by the depth-based pick
 * pass, labelled with the WGS-84 coordinate. Tutorials with 3D shapes attach one of these in
 * `start`, detach in `stop`, and let platform code call [showPick] from a tap handler.
 */
class PickResultIndicator(
    private val color: Color = Color(0f, 1f, 0f, 1f),
    private val sizePx: Int = 10,
) {
    val layer = RenderableLayer("Pick Result")
    /**
     * Guards against a stale click handler from a different tutorial showing a marker after
     * this tutorial has stopped — would otherwise pop back up on next start.
     */
    var isActive = false
        private set

    fun attach(engine: WorldWind) {
        if (engine.layers.indexOfLayer(layer) < 0) engine.layers.addLayer(layer)
        isActive = true
    }

    fun detach(engine: WorldWind) {
        engine.layers.removeLayer(layer)
        layer.clearRenderables()
        isActive = false
    }

    fun clear() {
        if (!isActive) return
        layer.clearRenderables()
    }

    fun show(engine: WorldWind, cartesianPoint: Vec3) {
        if (!isActive) return
        val position = engine.globe.cartesianToGeographic(
            cartesianPoint.x, cartesianPoint.y, cartesianPoint.z, Position()
        )
        layer.clearRenderables()
        layer.addRenderable(
            Placemark.createWithColorAndSize(position, color, sizePx).apply {
                altitudeMode = AltitudeMode.ABSOLUTE
                isAlwaysOnTop = true
                label = formatPosition(position)
                attributes.apply {
                    isDrawLeader = true
                    leaderAttributes.outlineColor.copy(color)
                }
            }
        )
    }

    /**
     * Show / clear convenience used by every platform's tap handler: non-null → drop a marker,
     * null → wipe any previous marker.
     */
    fun showPick(engine: WorldWind, cartesianPoint: Vec3?) {
        if (cartesianPoint != null) show(engine, cartesianPoint) else clear()
    }

    private fun formatPosition(p: Position): String {
        val latDeg = abs(p.latitude.inDegrees)
        val latSuffix = if (p.latitude.inDegrees < 0) "S" else "N"
        val lonDeg = abs(p.longitude.inDegrees)
        val lonSuffix = if (p.longitude.inDegrees < 0) "W" else "E"
        val alt = if (abs(p.altitude) >= 1000.0) "${"%.2f".format(p.altitude / 1000.0)} km"
        else "${p.altitude.roundToInt()} m"
        return "${"%.5f".format(latDeg)}°$latSuffix, ${"%.5f".format(lonDeg)}°$lonSuffix, $alt"
    }
}
