package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Offset
import earth.worldwind.geom.Position
import earth.worldwind.globe.Globe
import earth.worldwind.globe.terrain.firstHitFrom
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.Path
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.Polygon
import earth.worldwind.shape.ShapeAttributes
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Where a camera's frame actually lands, traced against terrain.
 *
 * A camera stands off the north face of Mount St. Helens and looks at it. Each corner of its
 * frame is a ray; [firstHitFrom] follows one and answers where the ground first rises to meet
 * it. The quad joining those answers is the footprint — what the camera sees.
 *
 * Two things this is meant to show.
 *
 * **The first surface wins.** The rays are drawn as lines from the camera to where they
 * stopped. Aimed across a ridge, a ray stops on the near face rather than carrying on to the
 * valley behind it, and the footprint is pulled in with it. A projection onto a level plane
 * would put that corner kilometres further out, on ground the camera cannot see.
 *
 * **It traces the ground the map drew.** The rays meet the terrain the renderer tessellated last
 * frame, so the answer sharpens as you zoom in, and ground the map is not drawing has nothing
 * to meet.
 *
 * Before the first frame, and on cold tiles, a trace may decline — the footprint stays hidden
 * rather than guessing. Trace again once the terrain has drawn.
 */
class TerrainRayTutorial(engine: WorldWind) : AbstractTutorial(engine) {

    /** Standing off the north face, looking south at the crater. */
    private val cameraPosition = Position.fromDegrees(46.320, -122.190, 3000.0)
    private val bearingDeg = 180.0
    private val verticalFovDeg = 30.0
    private val horizontalFovDeg = 45.0

    /**
     * Below horizontal, down the middle of the frame. Steep enough that the whole frame lands
     * compactly: the top edge sits [verticalFovDeg] / 2 shallower, and near the horizontal a
     * corner skims for tens of kilometres and the footprint stops being a shape at all.
     */
    private val depressionDeg = 45.0

    private val footprintAttributes = ShapeAttributes().apply {
        interiorColor = Color(0f, 0.8f, 1f, 0.35f)
        outlineColor = Color(0f, 0.8f, 1f, 1f)
        outlineWidth = 3f
    }
    private val rayAttributes = ShapeAttributes().apply {
        outlineColor = Color(1f, 0.85f, 0.2f, 0.9f)
        outlineWidth = 2f
    }

    private val layer = RenderableLayer("Terrain ray")
    private val marker by lazy { cameraMarker() }
    private val scope = MainScope()
    private var retries: Job? = null

    override val actions = arrayListOf(ACTION_TRACE)

    override fun runAction(actionName: String) {
        if (actionName == ACTION_TRACE) trace()
    }

    override fun start() {
        super.start()
        // There is nothing to trace against otherwise: the Android host adds its elevation
        // coverage disabled and enables it later, once its cache has attached.
        engine.globe.elevationModel.forEach { it.isEnabled = true }
        engine.layers.addLayer(layer)
        engine.cameraFromLookAt(
            LookAt(
                position = Position(46.270.degrees, (-122.190).degrees, 1500.0),
                altitudeMode = AltitudeMode.ABSOLUTE,
                range = 2.2e4, heading = 0.0.degrees, tilt = 72.0.degrees, roll = 0.0.degrees,
            )
        )
        // The map has not drawn yet when this runs, so the first trace comes too early; it is
        // retried until the ground is there to meet. The action re-traces on demand, but only
        // the web and iOS hosts offer it — on Android this is the only way the footprint appears.
        retries = scope.launch {
            repeat(TRACE_ATTEMPTS) {
                if (trace()) return@launch
                delay(TRACE_RETRY_MS)
            }
        }
    }

    override fun stop() {
        super.stop()
        retries?.cancel()
        retries = null
        engine.layers.removeLayer(layer)
    }

    /**
     * Casts the frame's five rays and redraws. Everything is rebuilt rather than moved: this
     * runs on an action, not per frame, and a trace that declines must leave nothing behind.
     */
    private fun trace(): Boolean {
        // The marker outlives each trace: it is what the drag is holding, and rebuilding it
        // mid-gesture would pull it out from under the finger.
        layer.clearRenderables()
        layer.addRenderable(marker)

        // What the map drew last frame; nothing to trace against before the first one.
        val terrain = engine.frameController.lastTerrains[Globe.Offset.Center] ?: run {
            WorldWind.requestRedraw()
            return false
        }
        val hit = Position()
        val landed = corners().mapNotNull { (bearing, depression) ->
            val found = terrain.firstHitFrom(
                globe = engine.globe,
                latitude = cameraPosition.latitude,
                longitude = cameraPosition.longitude,
                altitude = cameraPosition.altitude,
                bearing = bearing.degrees,
                depression = depression.degrees,
                result = hit,
            )
            if (found) Position(hit.latitude, hit.longitude, hit.altitude) else null
        }

        // All four corners or none: a quad with one corner traced and three missing is not a
        // shape the camera ever saw.
        if (landed.size == CORNERS) {
            layer.addRenderable(
                Polygon(landed).apply {
                    attributes = footprintAttributes
                    altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                    isFollowTerrain = true
                }
            )
            landed.forEach { corner ->
                layer.addRenderable(
                    Path(listOf(cameraPosition, corner)).apply {
                        attributes = rayAttributes
                        altitudeMode = AltitudeMode.ABSOLUTE
                    }
                )
            }
        }
        WorldWind.requestRedraw()
        return landed.size == CORNERS
    }

    /**
     * The aircraft, draggable. Moving it re-traces from where it now stands, which is the point
     * of dragging it: the footprint is a question about terrain, and the answer changes as the
     * camera does. Its height is held, so a drag chooses what to look at rather than landing it.
     */
    private fun cameraMarker() = object : Placemark(Position(cameraPosition)) {
        override fun moveTo(globe: Globe, position: Position) {
            super.moveTo(globe, position)
            this.position.altitude = cameraPosition.altitude
            cameraPosition.copy(this.position)
            trace()
        }
    }.apply {
        attributes.apply {
            imageSource = ImageSource.fromResource(MR.images.aircraft_fixwing)
            imageOffset = Offset.bottomCenter()
            imageScale = 2.0
            isDrawLeader = true
        }
        altitudeMode = AltitudeMode.ABSOLUTE
    }

    /**
     * The four corner directions, as a bearing and an angle below horizontal.
     *
     * Built here from the frame's half-angles rather than taken from a projector, so the
     * tutorial shows the whole of what a caller has to supply: the ray is all
     * [firstHitFrom] wants.
     */
    private fun corners(): List<Pair<Double, Double>> {
        val yaw = bearingDeg * RADIANS
        // Measured from straight down, which is how the camera's own basis is built below.
        val tilt = (90.0 - depressionDeg) * RADIANS
        val sinYaw = sin(yaw)
        val cosYaw = cos(yaw)
        val sinTilt = sin(tilt)
        val cosTilt = cos(tilt)

        // The camera's axes in east-north-up: where it looks, what is up in the picture, and
        // what is right. Right is level, so only the up term can tilt a ray.
        val viewE = sinYaw * sinTilt; val viewN = cosYaw * sinTilt; val viewU = -cosTilt
        val upE = sinYaw * cosTilt; val upN = cosYaw * cosTilt; val upU = sinTilt
        val rightE = cosYaw; val rightN = -sinYaw

        val tanH = tan(horizontalFovDeg / 2 * RADIANS)
        val tanV = tan(verticalFovDeg / 2 * RADIANS)
        return listOf(-tanH to tanV, tanH to tanV, tanH to -tanV, -tanH to -tanV).map { (x, y) ->
            val e = viewE + x * rightE + y * upE
            val n = viewN + x * rightN + y * upN
            val u = viewU + y * upU
            val length = sqrt(e * e + n * n + u * u)
            atan2(e, n) / RADIANS to asin(-u / length) / RADIANS
        }
    }

    private companion object {
        const val RADIANS = 0.017453292519943295
        const val CORNERS = 4
        const val ACTION_TRACE = "Trace again"
        const val TRACE_ATTEMPTS = 30
        const val TRACE_RETRY_MS = 1_500L
    }
}
