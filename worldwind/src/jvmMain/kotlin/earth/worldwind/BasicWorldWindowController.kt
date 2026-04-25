package earth.worldwind

import earth.worldwind.gesture.FrameScheduler
import earth.worldwind.layer.ViewControlsLayer
import earth.worldwind.layer.WorldMapLayer
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

open class BasicWorldWindowController(
    protected val wwd: WorldWindow,
) : AbstractWorldWindowController(), WorldWindowController {
    // Read through wwd because its engine is lateinit (initialized on the first GL init callback,
    // after this controller has been constructed). Property-getter form defers resolution.
    override val engine get() = wwd.engine
    protected val viewControlsLayer get() = engine.layers.filterIsInstance<ViewControlsLayer>().firstOrNull()
    protected val worldMapLayer get() = engine.layers.filterIsInstance<WorldMapLayer>().firstOrNull()
    protected var vcRepeatTimer: javax.swing.Timer? = null
    private var vcCurrentX = 0.0
    private var vcCurrentY = 0.0

    protected var activeButton = MouseEvent.NOBUTTON
    protected var isDragging = false
    protected var beginX = 0
    protected var beginY = 0
    protected var lastX = 0
    protected var lastY = 0

    private var lastDragNanos = 0L
    private var lastWheelEventNanos = 0L

    /** JVM gesture coords are in Swing pixels; the GL viewport is in physical pixels. */
    override val gestureToViewportPixels get() = engine.densityFactor.toDouble()

    override fun createFlingScheduler(): FrameScheduler = SwingTimerScheduler()

    override fun requestRedraw() = wwd.requestRedraw()

    private fun stopVcRepeat() {
        vcRepeatTimer?.stop()
        vcRepeatTimer = null
    }

    private fun handleViewControls(event: MouseEvent): Boolean {
        val vcl = viewControlsLayer ?: return false
        when (event.id) {
            MouseEvent.MOUSE_PRESSED -> if (event.button == MouseEvent.BUTTON1) {
                // Defensively cancel any prior repeat whose mouse-up we never saw — without
                // this, a missed release orphans the timer and leaves it firing forever.
                stopVcRepeat()
                val p = wwd.viewportCoordinates(event.x, event.y)
                if (vcl.handleClick(p.x, p.y, wwd.engine.viewport.height, wwd.engine)) {
                    wwd.requestRedraw()
                    vcCurrentX = p.x
                    vcCurrentY = p.y
                    vcRepeatTimer = javax.swing.Timer(50) {
                        // Re-dispatch with the live cursor position: drag within the pan
                        // button updates direction/velocity, drag-off auto-releases.
                        if (vcl.handleClick(vcCurrentX, vcCurrentY, wwd.engine.viewport.height, wwd.engine))
                            wwd.requestRedraw()
                        else stopVcRepeat()
                    }.apply { initialDelay = 400; start() }
                    return true
                }
            }
            MouseEvent.MOUSE_DRAGGED -> if (vcRepeatTimer != null) {
                val p = wwd.viewportCoordinates(event.x, event.y)
                vcCurrentX = p.x
                vcCurrentY = p.y
                return true
            }
            MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_EXITED -> if (vcRepeatTimer != null) {
                stopVcRepeat()
                return true
            }
        }
        return false
    }

    override fun onMouseEvent(event: MouseEvent): Boolean {
        if (handleViewControls(event)) return true
        when (event.id) {
            MouseEvent.MOUSE_PRESSED -> {
                if (event.button != MouseEvent.BUTTON1 && event.button != MouseEvent.BUTTON3) return false
                fling.cancel() // a new press interrupts any in-progress fling
                activeButton = event.button
                isDragging = true
                beginX = event.x
                beginY = event.y
                lastX = event.x
                lastY = event.y
                velocitySampler.reset()
                lastDragNanos = System.nanoTime()
                gestureDidBegin()
                return true
            }

            MouseEvent.MOUSE_DRAGGED -> {
                if (!isDragging) return false
                when (activeButton) {
                    MouseEvent.BUTTON1 -> {
                        val now = System.nanoTime()
                        val deltaPxX = (event.x - lastX).toDouble()
                        val deltaPxY = (event.y - lastY).toDouble()
                        handlePan(event.x, event.y)
                        velocitySampler.record(deltaPxX, deltaPxY, (now - lastDragNanos) / 1_000_000.0)
                        lastDragNanos = now
                    }
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
                if (prevButton == MouseEvent.BUTTON1 && event.id == MouseEvent.MOUSE_RELEASED) {
                    val (vx, vy) = velocitySampler.computeReleaseVelocity()
                    fling.start(vx, vy)
                }
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
        fling.cancel()
        // Treat events more than 500ms apart as the start of a new wheel-zoom sequence: re-snapshot
        // the camera AND freeze a new anchor under the cursor. Holding the anchor fixed for the rest
        // of the sequence is the whole point — re-picking each event (especially during touchpad
        // pinch streams) makes the map drift as the cursor jitters and the camera moves.
        val now = System.nanoTime()
        if ((now - lastWheelEventNanos) / 1_000_000L > 500L) {
            wwd.engine.cameraAsLookAt(lookAt)
            val p = wwd.viewportCoordinates(event.x, event.y)
            if (!wwd.engine.globe.is2D) zoomAnchor.capture(p.x, p.y) else zoomAnchor.invalidate()
        }
        lastWheelEventNanos = now
        lookAt.range *= scale
        zoomAnchor.apply()
        applyChanges()
        return true
    }

    protected open fun handlePan(x: Int, y: Int) {
        if (engine.globe.is2D) handlePan2D(x, y) else handlePan3D(x, y)
    }

    protected open fun handlePan3D(x: Int, y: Int) {
        applyPanDelta3D((x - lastX).toDouble(), (y - lastY).toDouble())
    }

    protected open fun handlePan2D(x: Int, y: Int) {
        val density = engine.densityFactor.toDouble()
        val tx = (x - beginX) * density
        val ty = (y - beginY) * density

        val metersPerPixel = engine.pixelSizeAtDistance(max(1.0, lookAt.range))
        val forwardMeters = ty * metersPerPixel
        val sideMeters = -tx * metersPerPixel

        val heading = lookAt.heading
        val sinHeading = sin(heading.inRadians)
        val cosHeading = cos(heading.inRadians)
        val cartX = beginLookAtPoint.x + forwardMeters * sinHeading + sideMeters * cosHeading
        val cartY = beginLookAtPoint.y + forwardMeters * cosHeading - sideMeters * sinHeading
        engine.globe.cartesianToGeographic(cartX, cartY, beginLookAtPoint.z, lookAt.position)
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

    override fun release() {
        super<AbstractWorldWindowController>.release()
        stopVcRepeat()
    }

    /**
     * Swing Timer-backed scheduler driving the [fling] animator. 8 ms (~120 Hz) is over-sampling
     * vs. typical 60 Hz display refresh, but Swing Timer has 1–4 ms scheduling jitter, so the
     * margin keeps each rendered frame fed and avoids visible stutter when a tick fires late.
     */
    private inner class SwingTimerScheduler : FrameScheduler {
        private var timer: javax.swing.Timer? = null
        private var lastNanos = 0L
        override fun start(tick: (dtMs: Double) -> Unit) {
            stop()
            lastNanos = System.nanoTime()
            timer = javax.swing.Timer(8) {
                val now = System.nanoTime()
                val dtMs = ((now - lastNanos) / 1_000_000.0).coerceAtMost(64.0)
                lastNanos = now
                tick(dtMs)
            }.also { it.start() }
        }
        override fun stop() {
            timer?.stop()
            timer = null
        }
    }
}
