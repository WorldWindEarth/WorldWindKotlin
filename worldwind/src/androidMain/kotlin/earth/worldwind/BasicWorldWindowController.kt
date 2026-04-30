package earth.worldwind

import android.view.Choreographer
import android.view.MotionEvent
import earth.worldwind.gesture.*
import earth.worldwind.gesture.GestureState.*
import earth.worldwind.layer.ViewControlsLayer
import earth.worldwind.layer.WorldMapLayer

open class BasicWorldWindowController(
    protected val wwd: WorldWindow,
) : AbstractWorldWindowController(), WorldWindowController, GestureListener {

    override val engine get() = wwd.engine

    protected val viewControlsLayer get() = engine.layers.filterIsInstance<ViewControlsLayer>().firstOrNull()
    protected val worldMapLayer get() = engine.layers.filterIsInstance<WorldMapLayer>().firstOrNull()
    private val vcRepeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var vcRepeatAction: Runnable? = null
    private var vcCurrentX = 0.0
    private var vcCurrentY = 0.0
    private var tapDownX = 0f
    private var tapDownY = 0f

    protected var lastX = 0f
    protected var lastY = 0f
    protected var lastRotation = 0f

    private var lastPanEventNanos = 0L

    /** Android gesture coords are physical pixels matching the GL viewport directly — no scaling. */
    override val gestureToViewportPixels = 1.0

    override fun createFlingScheduler(): FrameScheduler = ChoreographerFrameScheduler()

    override fun requestRedraw() = wwd.requestRedraw()

    override fun cancelFling() = fling.cancel()

    protected val panRecognizer: GestureRecognizer = PanRecognizer().also {
        it.addListener(this)
        it.maxNumberOfPointers = 1 // Do not pan during tilt
        it.interpretDistance = wwd.context.resources.getDimension(R.dimen.pan_interpret_distance)
    }
    protected val pinchRecognizer: GestureRecognizer = PinchRecognizer().also {
        it.addListener(this)
        it.interpretDistance = wwd.context.resources.getDimension(R.dimen.pinch_interpret_distance)
    }
    protected val rotationRecognizer: GestureRecognizer = RotationRecognizer().also {
        it.addListener(this)
        it.interpretAngle = 20f
    }
    protected val tiltRecognizer: GestureRecognizer = PanRecognizer().also {
        it.addListener(this)
        it.minNumberOfPointers = 2 // Use two fingers for tilt gesture
        it.interpretDistance = wwd.context.resources.getDimension(R.dimen.tilt_interpret_distance)
    }
    protected val mouseTiltRecognizer: GestureRecognizer = MousePanRecognizer().also {
        it.addListener(this)
        it.buttonState = MotionEvent.BUTTON_SECONDARY
        it.interpretDistance = wwd.context.resources.getDimension(R.dimen.tilt_interpret_distance)
    }
    protected val allRecognizers = listOf(
        panRecognizer, pinchRecognizer, rotationRecognizer, tiltRecognizer, mouseTiltRecognizer
    )

    private fun stopVcRepeat() {
        vcRepeatAction?.let { vcRepeatHandler.removeCallbacks(it) }
        vcRepeatAction = null
    }

    private fun handleViewControls(event: MotionEvent): Boolean {
        val vcl = viewControlsLayer ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Defensively cancel any prior repeat whose ACTION_UP we never saw — without
                // this, a missed lift orphans the runnable and leaves it firing forever.
                stopVcRepeat()
                val x = event.x.toDouble(); val y = event.y.toDouble()
                if (vcl.handleClick(x, y, wwd.engine.viewport.height, wwd.engine)) {
                    wwd.requestRedraw()
                    vcCurrentX = x
                    vcCurrentY = y
                    // Cancel any in-progress gesture so recognizers reset to POSSIBLE state,
                    // preventing stale startX/startY from causing phantom pans after the button tap.
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    for (recognizer in allRecognizers) recognizer.onTouchEvent(cancel)
                    cancel.recycle()
                    vcRepeatAction = object : Runnable {
                        override fun run() {
                            // Re-dispatch with the live finger position: drag within the pan
                            // button updates direction/velocity, drag-off auto-releases.
                            if (vcl.handleClick(vcCurrentX, vcCurrentY, wwd.engine.viewport.height, wwd.engine)) {
                                wwd.requestRedraw()
                                vcRepeatHandler.postDelayed(this, 50)
                            } else stopVcRepeat()
                        }
                    }.also { vcRepeatHandler.postDelayed(it, 400) }
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> if (vcRepeatAction != null) {
                vcCurrentX = event.x.toDouble()
                vcCurrentY = event.y.toDouble()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                if (vcRepeatAction != null) {
                    stopVcRepeat()
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handleViewControls(event)) return true

        // Track first-finger position for tap detection
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            tapDownX = event.x; tapDownY = event.y
        }
        // Single-finger tap: navigate minimap
        if (event.actionMasked == MotionEvent.ACTION_UP && event.pointerCount == 1) {
            val dx = event.x - tapDownX; val dy = event.y - tapDownY
            if (dx * dx + dy * dy < 100f) {
                worldMapLayer?.handleClick(event.x.toDouble(), event.y.toDouble(), wwd.engine.viewport.height, wwd.engine)
            }
        }

        var handled = false

        // Pass on the event on to the default globe navigation handlers
        // use or-assignment to indicate if any recognizer handled the event
        for (recognizer in allRecognizers) handled = handled or recognizer.onTouchEvent(event)

        // Handle dependent gestures lock
        if (handled) {
            tiltRecognizer.isEnabled = !isInProcess(rotationRecognizer) || !rotationRecognizer.isEnabled
            rotationRecognizer.isEnabled = !isInProcess(tiltRecognizer) || !tiltRecognizer.isEnabled
        }

        return handled
    }

    override fun gestureStateChanged(event: MotionEvent, recognizer: GestureRecognizer) {
        when(recognizer) {
            panRecognizer -> handlePan(recognizer)
            pinchRecognizer -> handlePinch(recognizer)
            rotationRecognizer -> handleRotate(recognizer)
            tiltRecognizer, mouseTiltRecognizer -> handleTilt(recognizer)
        }
    }

    protected open fun handlePan(recognizer: GestureRecognizer) {
        if (wwd.engine.globe.is2D) handlePan2D(recognizer) else handlePan3D(recognizer)
    }

    protected open fun handlePan3D(recognizer: GestureRecognizer) {
        val state = recognizer.state
        val dx = recognizer.translationX
        val dy = recognizer.translationY
        when (state) {
            BEGAN -> {
                gestureDidBegin()
                lastX = 0f
                lastY = 0f
                velocitySampler.reset()
                lastPanEventNanos = System.nanoTime()
            }
            CHANGED -> {
                val now = System.nanoTime()
                val deltaPxX = (dx - lastX).toDouble()
                val deltaPxY = (dy - lastY).toDouble()
                applyPanDelta3D(deltaPxX, deltaPxY)
                lastX = dx
                lastY = dy
                velocitySampler.record(deltaPxX, deltaPxY, (now - lastPanEventNanos) / 1_000_000.0)
                lastPanEventNanos = now
            }
            ENDED -> {
                val (vx, vy) = velocitySampler.computeReleaseVelocity()
                fling.start(vx, vy)
                gestureDidEnd()
            }
            CANCELLED -> gestureDidEnd()
            else -> {}
        }
    }

    protected open fun handlePan2D(recognizer: GestureRecognizer) {
        val state = recognizer.state
        val tx = recognizer.translationX
        val ty = recognizer.translationY

        when (state) {
            BEGAN -> gestureDidBegin()
            CHANGED -> {
                val metersPerPixel = engine.pixelSizeAtDistance(lookAt.range)
                val forwardMeters = ty * metersPerPixel
                val sideMeters = -tx * metersPerPixel

                val heading = lookAt.heading
                val sinHeading = kotlin.math.sin(heading.inRadians)
                val cosHeading = kotlin.math.cos(heading.inRadians)
                val x = beginLookAtPoint.x + forwardMeters * sinHeading + sideMeters * cosHeading
                val y = beginLookAtPoint.y + forwardMeters * cosHeading - sideMeters * sinHeading
                engine.globe.cartesianToGeographic(x, y, beginLookAtPoint.z, lookAt.position)
                applyChanges()
            }
            ENDED, CANCELLED -> gestureDidEnd()
            else -> {}
        }
    }

    protected open fun handlePinch(recognizer: GestureRecognizer) {
        val state = recognizer.state
        val scale = (recognizer as PinchRecognizer).scaleWithOffset
        when (state) {
            BEGAN -> {
                gestureDidBegin()
                zoomAnchor.capture(recognizer.x.toDouble(), recognizer.y.toDouble())
            }
            CHANGED -> if (scale != 0f) {
                lookAt.range = beginLookAt.range / scale
                if (!engine.globe.is2D) zoomAnchor.apply()
                applyChanges()
            }
            ENDED, CANCELLED -> gestureDidEnd()
            else -> {}
        }
    }

    protected open fun handleRotate(recognizer: GestureRecognizer) {
        val state = recognizer.state
        val rotation = (recognizer as RotationRecognizer).rotation
        when (state) {
            BEGAN -> {
                gestureDidBegin()
                lastRotation = 0f
            }
            CHANGED -> {
                // Apply the change in rotation to the camera, relative to the camera's current values.
                val headingDegrees = lastRotation - rotation
                lookAt.heading = lookAt.heading.plusDegrees(headingDegrees.toDouble()).normalize360()
                lastRotation = rotation
                applyChanges()
            }
            ENDED, CANCELLED -> gestureDidEnd()
            else -> {}
        }
    }

    protected open fun handleTilt(recognizer: GestureRecognizer) {
        val state = recognizer.state
        //val dx = recognizer.translationX
        val dy = recognizer.translationY
        when (state) {
            BEGAN -> {
                gestureDidBegin()
                lastRotation = 0f
            }
            CHANGED -> {
                // Apply the change in tilt to the camera, relative to when the gesture began.
                //val headingDegrees = 180 * dx / wwd.width
                val tiltDegrees = -180 * dy / wwd.height
                //lookAt.heading = beginLookAt.heading.plusDegrees(headingDegrees.toDouble()).normalize360()
                lookAt.tilt = beginLookAt.tilt.plusDegrees(tiltDegrees.toDouble())
                applyChanges()
            }
            ENDED, CANCELLED -> gestureDidEnd()
            else -> {}
        }
    }

    protected open fun isInProcess(recognizer: GestureRecognizer) = recognizer.state == BEGAN || recognizer.state == CHANGED

    override fun release() {
        super<AbstractWorldWindowController>.release()
        stopVcRepeat()
    }

    /** Vsync-aligned scheduler driving the [fling] animator on Android's render frame clock. */
    private class ChoreographerFrameScheduler : FrameScheduler {
        private val choreographer = Choreographer.getInstance()
        private var tick: ((Double) -> Unit)? = null
        private var lastNanos = 0L
        private val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val cb = tick ?: return
                val dtMs = ((frameTimeNanos - lastNanos) / 1_000_000L).coerceAtMost(64L).toDouble()
                lastNanos = frameTimeNanos
                cb(dtMs)
                if (tick != null) choreographer.postFrameCallback(this)
            }
        }
        override fun start(tick: (dtMs: Double) -> Unit) {
            this.tick = tick
            lastNanos = System.nanoTime()
            choreographer.postFrameCallback(callback)
        }
        override fun stop() {
            tick = null
            choreographer.removeFrameCallback(callback)
        }
    }
}