package earth.worldwind.layer

import earth.worldwind.MR
import earth.worldwind.WorldWind
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Offset
import earth.worldwind.geom.OffsetMode
import earth.worldwind.render.RenderContext
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.ScreenImage
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class ViewControlsLayer(
    private val panControl: ScreenImage = ScreenImage(ImageSource.fromResource(MR.images.view_pan)),
    private val zoomInControl: ScreenImage = ScreenImage(ImageSource.fromResource(MR.images.view_zoom_in)),
    private val zoomOutControl: ScreenImage = ScreenImage(ImageSource.fromResource(MR.images.view_zoom_out)),
    private val headingLeftControl: ScreenImage = ScreenImage(ImageSource.fromResource(MR.images.view_heading_left)),
    private val headingRightControl: ScreenImage = ScreenImage(ImageSource.fromResource(MR.images.view_heading_right)),
    private val tiltUpControl: ScreenImage = ScreenImage(ImageSource.fromResource(MR.images.view_pitch_up)),
    private val tiltDownControl: ScreenImage = ScreenImage(ImageSource.fromResource(MR.images.view_pitch_down)),
    private val exaggerationUpControl: ScreenImage = ScreenImage(ImageSource.fromResource(MR.images.view_elevation_up)),
    private val exaggerationDownControl: ScreenImage = ScreenImage(ImageSource.fromResource(MR.images.view_elevation_down)),
    private val fovNarrowControl: ScreenImage = ScreenImage(ImageSource.fromResource(MR.images.view_fov_narrow)),
    private val fovWideControl: ScreenImage = ScreenImage(ImageSource.fromResource(MR.images.view_fov_wide))
) : AbstractLayer("View Controls") {
    override var isPickEnabled = false

    var showPanControl = true
    var showZoomControl = true
    var showHeadingControl = true
    var showTiltControl = true
    var showExaggerationControl = true
    var showFovControl = false

    var panIncrement = 0.001
    var zoomIncrement = 0.04
    var headingIncrement = 1.0
    var tiltIncrement = 1.0
    var exaggerationIncrement = 0.01
    var fovIncrement = 0.1

    enum class Action { PAN, ZOOM_IN, ZOOM_OUT, HEADING_LEFT, HEADING_RIGHT, TILT_UP, TILT_DOWN, EXAGGERATION_UP, EXAGGERATION_DOWN, FOV_NARROW, FOV_WIDE }

    private data class Bounds(val x: Double, val y: Double, val w: Double, val h: Double, val action: Action)
    private val controlBounds = mutableListOf<Bounds>()
    private var panCenterX = 0.0
    private var panCenterY = 0.0

    override fun doRender(rc: RenderContext) {
        controlBounds.clear()
        val d = rc.densityFactor.toDouble()
        val s = 32.0 * d
        val baseY = 11.0 * d
        var x = 11.0 * d

        if (showPanControl) {
            place(panControl, x, baseY, s * 2, s * 2, rc)
            panCenterX = x + s
            panCenterY = baseY + s
            controlBounds += Bounds(x, baseY, s * 2, s * 2, Action.PAN)
            x += s * 2
        }
        if (showZoomControl) {
            place(zoomOutControl, x, baseY, s, s, rc)
            place(zoomInControl, x, baseY + s, s, s, rc)
            controlBounds += Bounds(x, baseY, s, s, Action.ZOOM_OUT)
            controlBounds += Bounds(x, baseY + s, s, s, Action.ZOOM_IN)
            x += s
        }
        if (showHeadingControl) {
            place(headingRightControl, x, baseY, s, s, rc)
            place(headingLeftControl, x, baseY + s, s, s, rc)
            controlBounds += Bounds(x, baseY, s, s, Action.HEADING_RIGHT)
            controlBounds += Bounds(x, baseY + s, s, s, Action.HEADING_LEFT)
            x += s
        }
        if (showTiltControl && !rc.globe.is2D) {
            place(tiltDownControl, x, baseY, s, s, rc)
            place(tiltUpControl, x, baseY + s, s, s, rc)
            controlBounds += Bounds(x, baseY, s, s, Action.TILT_DOWN)
            controlBounds += Bounds(x, baseY + s, s, s, Action.TILT_UP)
            x += s
        }
        if (showExaggerationControl && !rc.globe.is2D) {
            place(exaggerationDownControl, x, baseY, s, s, rc)
            place(exaggerationUpControl, x, baseY + s, s, s, rc)
            controlBounds += Bounds(x, baseY, s, s, Action.EXAGGERATION_DOWN)
            controlBounds += Bounds(x, baseY + s, s, s, Action.EXAGGERATION_UP)
            x += s
        }
        if (showFovControl) {
            place(fovNarrowControl, x, baseY, s, s, rc)
            place(fovWideControl, x, baseY + s, s, s, rc)
            controlBounds += Bounds(x, baseY, s, s, Action.FOV_NARROW)
            controlBounds += Bounds(x, baseY + s, s, s, Action.FOV_WIDE)
        }
    }

    private fun place(img: ScreenImage, x: Double, y: Double, w: Double, h: Double, rc: RenderContext) {
        img.screenOffset = Offset(OffsetMode.PIXELS, x, OffsetMode.PIXELS, y)
        img.imageOffset = Offset(OffsetMode.PIXELS, 0.0, OffsetMode.PIXELS, 0.0)
        val texture = rc.getTexture(img.imageSource)
        img.imageScale = if (texture != null) w / texture.width else 1.0
        img.render(rc)
    }

    /**
     * Handle a tap/click event. x and y are in top-left (screen/canvas) coordinate convention.
     * viewportHeight is used to convert y to OpenGL convention internally.
     * Returns true if a control was activated.
     */
    fun handleClick(x: Double, y: Double, viewportHeight: Int, engine: WorldWind): Boolean {
        val glY = viewportHeight - y  // convert to OpenGL y (bottom-left origin)
        val hit = controlBounds.firstOrNull { b ->
            x >= b.x && x <= b.x + b.w && glY >= b.y && glY <= b.y + b.h
        } ?: return false

        when (hit.action) {
            Action.EXAGGERATION_UP -> engine.globe.verticalExaggeration += exaggerationIncrement
            Action.EXAGGERATION_DOWN -> engine.globe.verticalExaggeration = max(0.0, engine.globe.verticalExaggeration - exaggerationIncrement)
            Action.FOV_NARROW -> engine.camera.fieldOfView = max(1.0, engine.camera.fieldOfView.inDegrees - fovIncrement).degrees
            Action.FOV_WIDE -> engine.camera.fieldOfView = min(120.0, engine.camera.fieldOfView.inDegrees + fovIncrement).degrees
            else -> {
                val lookAt = LookAt()
                engine.cameraAsLookAt(lookAt)
                when (hit.action) {
                    Action.PAN -> {
                        val dx = panCenterX - x
                        val dy = panCenterY - glY
                        val heading = lookAt.heading.inDegrees + atan2(dx, dy) * (180.0 / PI)
                        val globeRadius = engine.globe.getRadiusAt(lookAt.position.latitude, lookAt.position.longitude)
                        val distance = panIncrement * (lookAt.range / globeRadius) * sqrt(dx * dx + dy * dy)
                        lookAt.position.greatCircleLocation(heading.degrees, -distance, lookAt.position)
                    }
                    Action.ZOOM_IN -> lookAt.range *= (1.0 - zoomIncrement)
                    Action.ZOOM_OUT -> lookAt.range *= (1.0 + zoomIncrement)
                    Action.HEADING_LEFT -> lookAt.heading = (lookAt.heading.inDegrees - headingIncrement).degrees
                    Action.HEADING_RIGHT -> lookAt.heading = (lookAt.heading.inDegrees + headingIncrement).degrees
                    Action.TILT_UP -> lookAt.tilt = min(90.0, lookAt.tilt.inDegrees + tiltIncrement).degrees
                    Action.TILT_DOWN -> lookAt.tilt = max(0.0, lookAt.tilt.inDegrees - tiltIncrement).degrees
                }
                engine.cameraFromLookAt(lookAt)
            }
        }
        return true
    }
}
