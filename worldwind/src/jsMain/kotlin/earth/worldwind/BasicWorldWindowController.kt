package earth.worldwind

import earth.worldwind.geom.Vec2
import earth.worldwind.gesture.*
import earth.worldwind.gesture.GestureState.*
import earth.worldwind.layer.ViewControlsLayer
import earth.worldwind.layer.WorldMapLayer
import kotlinx.browser.window
import org.w3c.dom.TouchEvent
import org.w3c.dom.events.Event
import org.w3c.dom.events.WheelEvent
import org.w3c.dom.pointerevents.PointerEvent
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * This class provides the default window controller for WorldWind for controlling the globe via user interaction.
 */
open class BasicWorldWindowController(wwd: WorldWindow): WorldWindowController(wwd) {
    protected val viewControlsLayer get() = engine.layers.filterIsInstance<ViewControlsLayer>().firstOrNull()
    protected val worldMapLayer get() = engine.layers.filterIsInstance<WorldMapLayer>().firstOrNull()
    private var vcRepeatTimeout = -1
    private var vcRepeatInterval = -1
    private var vcCurrentX = 0.0
    private var vcCurrentY = 0.0
    private var tapDownX = 0.0
    private var tapDownY = 0.0

    private val isVcRepeatActive get() = vcRepeatTimeout != -1 || vcRepeatInterval != -1

    val primaryDragRecognizer: GestureRecognizer = DragRecognizer(wwd.canvas).also { it.addListener(this) }
    val secondaryDragRecognizer: GestureRecognizer = DragRecognizer(wwd.canvas).also {
        it.addListener(this)
        it.button = 2 // secondary mouse button
    }
    val panRecognizer: GestureRecognizer = PanRecognizer(wwd.canvas).also { it.addListener(this) }
    val pinchRecognizer: GestureRecognizer = PinchRecognizer(wwd.canvas).also { it.addListener(this) }
    val rotationRecognizer: GestureRecognizer = RotationRecognizer(wwd.canvas).also { it.addListener(this) }
    val tiltRecognizer: GestureRecognizer = TiltRecognizer(wwd.canvas).also { it.addListener(this) }
//    val tapRecognizer: GestureRecognizer = TapRecognizer(wwd.canvas).also { it.addListener(this) }
//    val clickRecognizer: GestureRecognizer = ClickRecognizer(wwd.canvas).also { it.addListener(this) }
    protected val lastPoint = Vec2()
    protected var lastRotation = 0.0
    protected var lastWheelEvent = 0

    private var lastPanEventMs = 0.0

    /** JS gesture coords are in CSS pixels; the GL viewport is in physical pixels. */
    override val gestureToViewportPixels get() = wwd.engine.densityFactor.toDouble()

    override fun createFlingScheduler(): FrameScheduler = AnimationFrameScheduler()

    override fun requestRedraw() = wwd.requestRedraw()

    init {
        // Establish the dependencies between gesture recognizers. The pan, pinch and rotate gesture may recognize
        // simultaneously with each other.
        panRecognizer.recognizeSimultaneouslyWith(pinchRecognizer)
        panRecognizer.recognizeSimultaneouslyWith(rotationRecognizer)
        pinchRecognizer.recognizeSimultaneouslyWith(rotationRecognizer)

        // Since the tilt gesture is a subset of the pan gesture, pan will typically recognize before tilt,
        // effectively suppressing tilt. Establish a dependency between the other touch gestures and tilt to provide
        // tilt an opportunity to recognize.
        panRecognizer.requireRecognizerToFail(tiltRecognizer)
        pinchRecognizer.requireRecognizerToFail(tiltRecognizer)
        rotationRecognizer.requireRecognizerToFail(tiltRecognizer)
    }

    private fun stopVcRepeat() {
        if (vcRepeatTimeout != -1) { window.clearTimeout(vcRepeatTimeout); vcRepeatTimeout = -1 }
        if (vcRepeatInterval != -1) { window.clearInterval(vcRepeatInterval); vcRepeatInterval = -1 }
    }

    override fun handleEvent(event: Event) {
        super.handleEvent(event)
        if (event.defaultPrevented) return

        // Classify once. WorldWindow registers either Pointer or Touch events based on
        // navigator.maxTouchPoints (never both), so each phase has to accept either family.
        val type = event.type
        val isPress = type == "pointerdown" || type == "touchstart"
        val isMove = type == "pointermove" || type == "touchmove"
        val isRelease = type == "pointerup" || type == "touchend"
        val isCancel = type == "pointercancel" || type == "touchcancel"

        if (isPress) {
            // Cancel any in-progress fling so the user is in control immediately, not after
            // the pan-recognizer's interpretDistance threshold trips. Capture the press point
            // for tap detection so minimap-tap and VC-click can both consume it below.
            fling.cancel()
            eventCanvasCoords(event)?.let { p -> tapDownX = p.x; tapDownY = p.y }
        }

        val vcl = viewControlsLayer
        if (vcl != null && isPress) {
            // Defensively cancel any prior repeat whose release we never saw - without this,
            // a missed lift orphans the timer/interval and leaves it firing forever.
            stopVcRepeat()
            if (vcl.handleClick(tapDownX, tapDownY, wwd.engine.viewport.height, wwd.engine)) {
                wwd.requestRedraw()
                event.preventDefault()
                vcCurrentX = tapDownX
                vcCurrentY = tapDownY
                vcRepeatTimeout = window.setTimeout({
                    vcRepeatInterval = window.setInterval({
                        // Re-dispatch with the live cursor position: drag within the pan
                        // button updates direction/velocity, drag-off auto-releases.
                        if (vcl.handleClick(vcCurrentX, vcCurrentY, wwd.engine.viewport.height, wwd.engine))
                            wwd.requestRedraw()
                        else stopVcRepeat()
                    }, 50)
                }, 400)
                return
            }
        }

        // Swallow moves while a VC repeat is active so pan/drag recognizers don't engage
        // simultaneously; refresh the live coordinate so finger-drag inside a held button
        // keeps updating direction/velocity instead of freezing.
        if (isMove && isVcRepeatActive) {
            eventCanvasCoords(event)?.let { p -> vcCurrentX = p.x; vcCurrentY = p.y }
            event.preventDefault()
            return
        }

        if (isRelease || isCancel) {
            stopVcRepeat()
            // Single-tap: navigate minimap. Cancel does not fire taps.
            if (isRelease) eventCanvasCoords(event)?.let { p ->
                val dx = p.x - tapDownX; val dy = p.y - tapDownY
                if (dx * dx + dy * dy < 25) {
                    worldMapLayer?.handleClick(p.x, p.y, wwd.engine.viewport.height, wwd.engine)
                }
            }
        }

        if (type == "wheel") {
            event.preventDefault()
            handleWheelEvent(event as WheelEvent)
        } else GestureRecognizer.allRecognizers.forEach { r -> if (r.target == wwd.canvas) r.handleEvent(event) }
    }

    override fun gestureStateChanged(recognizer: GestureRecognizer) {
        when(recognizer) {
            primaryDragRecognizer, panRecognizer -> handlePanOrDrag(recognizer)
            secondaryDragRecognizer -> handleSecondaryDrag(recognizer)
            pinchRecognizer -> handlePinch(recognizer)
            rotationRecognizer -> handleRotation(recognizer)
            tiltRecognizer -> handleTilt(recognizer)
//            clickRecognizer, tapRecognizer -> handleClickOrTap(recognizer)
        }
    }

//     protected open fun handleClickOrTap(recognizer: GestureRecognizer) {
//         if (recognizer.state == RECOGNIZED) {
//             val pickPoint = wwd.canvasCoordinates(recognizer.clientX, recognizer.clientY)
//
//             // Identify if the top picked object contains a URL for hyperlinking
//             val pickList = wwd.pick(pickPoint)
//             val userObject = pickList.topPickedObject?.userObject
//             // If the url object was appended, open the hyperlink
//             if (userObject is Renderable && userObject.hasUserProperty("url")) {
//                 window.open(userObject.getUserProperty("url") as String, "_blank")
//             }
//         }
//     }

    protected open fun handlePanOrDrag(recognizer: GestureRecognizer) {
        if (wwd.engine.globe.is2D) handlePanOrDrag2D(recognizer) else handlePanOrDrag3D(recognizer)
    }

    protected open fun handlePanOrDrag3D(recognizer: GestureRecognizer) {
        val state = recognizer.state
        val tx = recognizer.translationX
        val ty = recognizer.translationY

        when (state) {
            BEGAN -> {
                gestureDidBegin()
                lastPoint.set(0.0, 0.0)
                velocitySampler.reset()
                lastPanEventMs = window.performance.now()
            }
            CHANGED -> {
                val now = window.performance.now()
                val deltaPxX = tx - lastPoint.x
                val deltaPxY = ty - lastPoint.y
                applyPanDelta3D(deltaPxX, deltaPxY)
                lastPoint.set(tx, ty)
                velocitySampler.record(deltaPxX, deltaPxY, now - lastPanEventMs)
                lastPanEventMs = now
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

    protected open fun handlePanOrDrag2D(recognizer: GestureRecognizer) {
        val state = recognizer.state
        val tx = recognizer.translationX
        val ty = recognizer.translationY

        when (state) {
            BEGAN -> gestureDidBegin()
            CHANGED -> {
                val metersPerPixel = engine.pixelSizeAtDistance(lookAt.range) * engine.densityFactor
                val forwardMeters = ty * metersPerPixel
                val sideMeters = -tx * metersPerPixel

                val heading = lookAt.heading
                val sinHeading = sin(heading.inRadians)
                val cosHeading = cos(heading.inRadians)
                val x = beginLookAtPoint.x + forwardMeters * sinHeading + sideMeters * cosHeading
                val y = beginLookAtPoint.y + forwardMeters * cosHeading - sideMeters * sinHeading
                engine.globe.cartesianToGeographic(x, y, beginLookAtPoint.z, lookAt.position)
                applyChanges()
            }
            ENDED, CANCELLED -> gestureDidEnd()
            else -> {}
        }
    }

    protected open fun handleSecondaryDrag(recognizer: GestureRecognizer) {
        val state = recognizer.state
        val tx = recognizer.translationX
        val ty = recognizer.translationY

        when (state) {
            BEGAN -> gestureDidBegin()
            CHANGED -> {
                // Compute the current translation from screen coordinates to degrees. Use the canvas dimensions as a
                // metric for converting the gesture translation to a fraction of an angle.
                val headingDegrees = 180.0 * tx / wwd.canvas.clientWidth
                val tiltDegrees = 90.0 * ty / wwd.canvas.clientHeight

                // Apply the change in heading and tilt to this view's corresponding properties.
                lookAt.heading = beginLookAt.heading.plusDegrees(headingDegrees)
                lookAt.tilt = beginLookAt.tilt.plusDegrees(tiltDegrees)
                applyChanges()
            }
            ENDED, CANCELLED -> gestureDidEnd()
            else -> {}
        }
    }

    protected open fun handlePinch(recognizer: GestureRecognizer) {
        val state = recognizer.state
        val scale = (recognizer as PinchRecognizer).scaleWithOffset

        when(state) {
            BEGAN -> {
                gestureDidBegin()
                val cp = wwd.canvasCoordinates(recognizer.clientX, recognizer.clientY)
                zoomAnchor.capture(cp.x, cp.y)
            }
            CHANGED -> if (scale != 0.0) {
                lookAt.range = beginLookAt.range / scale
                if (!wwd.engine.globe.is2D) zoomAnchor.apply()
                applyChanges()
            }
            ENDED, CANCELLED -> gestureDidEnd()
            else -> {}
        }
    }

    protected open fun handleRotation(recognizer: GestureRecognizer) {
        val state = recognizer.state
        val rotation = (recognizer as RotationRecognizer).rotationWithOffset

        when (state) {
            BEGAN -> {
                gestureDidBegin()
                lastRotation = 0.0
            }
            CHANGED -> {
                // Apply the change in gesture rotation to this view's current heading. We apply relative to the
                // current heading rather than the heading when the gesture began in order to work simultaneously with
                // pan operations that also modify the current heading.
                lookAt.heading = lookAt.heading.minusDegrees(rotation - lastRotation)
                lastRotation = rotation
                applyChanges()
            }
            ENDED, CANCELLED -> gestureDidEnd()
            else -> {}
        }
    }

    protected open fun handleTilt(recognizer: GestureRecognizer) {
        val state = recognizer.state
        val ty = recognizer.translationY

        when (state) {
            BEGAN -> gestureDidBegin()
            CHANGED -> {
                // Compute the gesture translation from screen coordinates to degrees. Use the canvas dimensions as a
                // metric for converting the translation to a fraction of an angle.
                val tiltDegrees = -90.0 * ty / wwd.canvas.clientHeight
                // Apply the change in heading and tilt to this view's corresponding properties.
                lookAt.tilt = beginLookAt.tilt.plusDegrees(tiltDegrees)
                applyChanges()
            }
            ENDED, CANCELLED -> gestureDidEnd()
            else -> {}
        }
    }

    protected open fun handleWheelEvent(event: WheelEvent) {
        // Wheel zoom should not survive into a fresh interaction; flush any pending fling.
        fling.cancel()
        val timeStamp = event.timeStamp.toInt()
        // Treat events more than 500ms apart as the start of a new wheel-zoom sequence: re-snapshot
        // the camera AND freeze a new anchor under the cursor. Holding the anchor fixed for the rest
        // of the sequence is the whole point — re-picking each event makes the map drift as the
        // cursor jitters and the camera moves underneath it.
        if (timeStamp - lastWheelEvent > 500) {
            wwd.engine.cameraAsLookAt(lookAt)
            lastWheelEvent = timeStamp
            val cp = wwd.canvasCoordinates(event.clientX, event.clientY)
            if (!wwd.engine.globe.is2D) zoomAnchor.capture(cp.x, cp.y) else zoomAnchor.invalidate()
        }

        // Normalize the wheel delta based on the wheel delta mode. This produces a roughly consistent delta across
        // browsers and input devices.
        val normalizedDelta = when(event.deltaMode) {
            WheelEvent.DOM_DELTA_PIXEL -> event.deltaY
            WheelEvent.DOM_DELTA_LINE -> event.deltaY * 20.0
            WheelEvent.DOM_DELTA_PAGE -> event.deltaY * 200.0
            else -> event.deltaY
        }

        // Compute a zoom scale factor by adding a fraction of the normalized delta to 1. When multiplied by the
        // view's range, this has the effect of zooming out or zooming in depending on whether the delta is
        // positive or negative, respectfully.
        val scale = 1.0 + (normalizedDelta / 1000.0)

        lookAt.range *= scale
        zoomAnchor.apply()
        applyChanges()
    }

    override fun release() {
        super.release()
        stopVcRepeat()
    }

    /**
     * Returns the press point of either a Pointer or Touch event in canvas coordinates
     * (already converted from client/CSS pixels). WorldWindow registers Pointer events
     * on mouse-only platforms and Touch events on touch-capable platforms based on
     * `navigator.maxTouchPoints`. Returns `null` for any other event type or for a touch
     * event with no changed touches.
     */
    private fun eventCanvasCoords(event: Event) = when (event) {
        is PointerEvent -> wwd.canvasCoordinates(event.clientX, event.clientY)
        is TouchEvent -> event.changedTouches.item(0)?.let { wwd.canvasCoordinates(it.clientX, it.clientY) }
        else -> null
    }

    /** requestAnimationFrame-backed scheduler driving the [fling] animator on the browser's vsync. */
    private class AnimationFrameScheduler : FrameScheduler {
        private var tick: ((Double) -> Unit)? = null
        private var handle = -1
        private var lastMs = 0.0
        override fun start(tick: (dtMs: Double) -> Unit) {
            this.tick = tick
            lastMs = window.performance.now()
            schedule()
        }
        override fun stop() {
            tick = null
            if (handle != -1) {
                window.cancelAnimationFrame(handle)
                handle = -1
            }
        }
        private fun schedule() {
            handle = window.requestAnimationFrame {
                handle = -1
                val cb = tick ?: return@requestAnimationFrame
                val now = window.performance.now()
                val dtMs = (now - lastMs).coerceAtMost(64.0)
                lastMs = now
                cb(dtMs)
                if (tick != null) schedule()
            }
        }
    }
}