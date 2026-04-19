package earth.worldwind

import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.ViewControlsLayer
import earth.worldwind.layer.WorldMapLayer
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

open class BasicWorldWindowController(protected val wwd: WorldWindow) : WorldWindowController {
    protected val viewControlsLayer get() = wwd.engine.layers.filterIsInstance<ViewControlsLayer>().firstOrNull()
    protected val worldMapLayer get() = wwd.engine.layers.filterIsInstance<WorldMapLayer>().firstOrNull()
    protected var vcRepeatTimer: javax.swing.Timer? = null

    protected val beginLookAt = LookAt()
    protected val beginLookAtPoint = Vec3()
    protected val lookAt = LookAt()

    protected var activeButton = MouseEvent.NOBUTTON
    protected var isDragging = false
    protected var beginX = 0
    protected var beginY = 0
    protected var lastX = 0
    protected var lastY = 0
    protected var activeGestures = 0

    private fun handleViewControls(event: MouseEvent): Boolean {
        val vcl = viewControlsLayer ?: return false
        when (event.id) {
            MouseEvent.MOUSE_PRESSED -> if (event.button == MouseEvent.BUTTON1) {
                val p = wwd.viewportCoordinates(event.x, event.y)
                if (vcl.handleClick(p.x, p.y, wwd.engine.viewport.height, wwd.engine)) {
                    wwd.requestRedraw()
                    vcRepeatTimer = javax.swing.Timer(50) {
                        if (vcl.handleClick(p.x, p.y, wwd.engine.viewport.height, wwd.engine))
                            wwd.requestRedraw()
                    }.apply { initialDelay = 400; start() }
                    return true
                }
            }
            MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_EXITED -> {
                vcRepeatTimer?.stop(); vcRepeatTimer = null
            }
        }
        return false
    }

    override fun onMouseEvent(event: MouseEvent): Boolean {
        if (handleViewControls(event)) return true
        when (event.id) {
            MouseEvent.MOUSE_PRESSED -> {
                if (event.button != MouseEvent.BUTTON1 && event.button != MouseEvent.BUTTON3) return false
                activeButton = event.button
                isDragging = true
                beginX = event.x
                beginY = event.y
                lastX = event.x
                lastY = event.y
                gestureDidBegin()
                return true
            }

            MouseEvent.MOUSE_DRAGGED -> {
                if (!isDragging) return false
                when (activeButton) {
                    MouseEvent.BUTTON1 -> handlePan(event.x, event.y)
                    MouseEvent.BUTTON3 -> handleRotateTilt(event.x, event.y)
                }
                lastX = event.x
                lastY = event.y
                return true
            }

            MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_EXITED -> {
                if (!isDragging) return false
                val prevButton = activeButton
                val dx = event.x - beginX; val dy = event.y - beginY
                isDragging = false
                activeButton = MouseEvent.NOBUTTON
                gestureDidEnd()
                // Tap detection: short left-click navigates the minimap
                if (event.id == MouseEvent.MOUSE_RELEASED && prevButton == MouseEvent.BUTTON1 && dx * dx + dy * dy < 25) {
                    val p = wwd.viewportCoordinates(event.x, event.y)
                    worldMapLayer?.handleClick(p.x, p.y, wwd.engine.viewport.height, wwd.engine)
                }
                return true
            }
        }
        return false
    }

    override fun onMouseWheelEvent(event: MouseWheelEvent): Boolean {
        val scale = 1.0 + event.preciseWheelRotation / 10.0
        if (scale <= 0.0) return false
        wwd.engine.cameraAsLookAt(lookAt)
        lookAt.range *= scale
        applyChanges()
        return true
    }

    protected open fun handlePan(x: Int, y: Int) {
        if (wwd.engine.globe.is2D) handlePan2D(x, y) else handlePan3D(x, y)
    }

    protected open fun handlePan3D(x: Int, y: Int) {
        var lat = lookAt.position.latitude
        var lon = lookAt.position.longitude

        val density = wwd.engine.densityFactor.toDouble()
        val dx = (x - lastX) * density
        val dy = (y - lastY) * density
        val metersPerPixel = wwd.engine.pixelSizeAtDistance(max(1.0, lookAt.range))
        val forwardMeters = dy * metersPerPixel
        val sideMeters = -dx * metersPerPixel
        val globeRadius = wwd.engine.globe.getRadiusAt(lat, lon)
        val forwardRadians = forwardMeters / globeRadius
        val sideRadians = sideMeters / globeRadius

        val heading = lookAt.heading
        val headingRadians = heading.inRadians
        val sinHeading = sin(headingRadians)
        val cosHeading = cos(headingRadians)
        lat = lat.plusRadians(forwardRadians * cosHeading - sideRadians * sinHeading)
        lon = lon.plusRadians(forwardRadians * sinHeading + sideRadians * cosHeading)

        if (lat.inDegrees < -90.0 || lat.inDegrees > 90.0) {
            lookAt.position.latitude = lat.normalizeLatitude()
            lookAt.position.longitude = lon.plusDegrees(180.0).normalizeLongitude()
            lookAt.heading = heading.plusDegrees(180.0).normalize360()
        } else if (lon.inDegrees < -180.0 || lon.inDegrees > 180.0) {
            lookAt.position.latitude = lat
            lookAt.position.longitude = lon.normalizeLongitude()
        } else {
            lookAt.position.latitude = lat
            lookAt.position.longitude = lon
        }
        applyChanges()
    }

    protected open fun handlePan2D(x: Int, y: Int) {
        val density = wwd.engine.densityFactor.toDouble()
        val tx = (x - beginX) * density
        val ty = (y - beginY) * density

        val metersPerPixel = wwd.engine.pixelSizeAtDistance(max(1.0, lookAt.range))
        val forwardMeters = ty * metersPerPixel
        val sideMeters = -tx * metersPerPixel

        val heading = lookAt.heading
        val sinHeading = sin(heading.inRadians)
        val cosHeading = cos(heading.inRadians)
        val cartX = beginLookAtPoint.x + forwardMeters * sinHeading + sideMeters * cosHeading
        val cartY = beginLookAtPoint.y + forwardMeters * cosHeading - sideMeters * sinHeading
        wwd.engine.globe.cartesianToGeographic(cartX, cartY, beginLookAtPoint.z, lookAt.position)
        applyChanges()
    }

    protected open fun handleRotateTilt(x: Int, y: Int) {
        val tx = x - beginX
        val ty = y - beginY

        val width = max(1, wwd.width)
        val height = max(1, wwd.height)
        val headingDegrees = 180.0 * tx / width
        val tiltDegrees = 90.0 * ty / height

        lookAt.heading = beginLookAt.heading.plusDegrees(headingDegrees)
        lookAt.tilt = beginLookAt.tilt.plusDegrees(tiltDegrees)
        applyChanges()
    }

    protected open fun applyChanges() {
        wwd.engine.cameraFromLookAt(lookAt)
        wwd.requestRedraw()
    }

    protected open fun gestureDidBegin() {
        if (activeGestures++ == 0) {
            wwd.engine.cameraAsLookAt(beginLookAt)
            lookAt.copy(beginLookAt)
            if (wwd.engine.globe.is2D) {
                wwd.engine.globe.geographicToCartesian(
                    beginLookAt.position.latitude,
                    beginLookAt.position.longitude,
                    beginLookAt.position.altitude,
                    beginLookAtPoint
                )
            }
        }
    }

    protected open fun gestureDidEnd() {
        if (activeGestures > 0) activeGestures--
    }
}
