@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package earth.worldwind

import earth.worldwind.gesture.FrameScheduler
import earth.worldwind.gesture.GestureListener
import earth.worldwind.gesture.GestureRecognizer
import earth.worldwind.gesture.GestureState
import earth.worldwind.gesture.GestureState.BEGAN
import earth.worldwind.gesture.GestureState.CANCELLED
import earth.worldwind.gesture.GestureState.CHANGED
import earth.worldwind.gesture.GestureState.ENDED
import earth.worldwind.gesture.PanRecognizer
import earth.worldwind.gesture.PinchRecognizer
import earth.worldwind.gesture.RotationRecognizer
import earth.worldwind.gesture.TouchEvent
import earth.worldwind.gesture.TouchEvent.Companion.ACTION_CANCEL
import earth.worldwind.gesture.TouchEvent.Companion.ACTION_DOWN
import earth.worldwind.gesture.TouchEvent.Companion.ACTION_MOVE
import earth.worldwind.gesture.TouchEvent.Companion.ACTION_UP
import earth.worldwind.layer.ViewControlsLayer
import earth.worldwind.layer.WorldMapLayer
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ExportObjCClass
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSTimer
import platform.QuartzCore.CACurrentMediaTime
import platform.QuartzCore.CADisplayLink
import platform.darwin.NSObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** iOS port of the gesture-driven camera controller. Mirrors the Android shape — own a set of
 *  recognizers, dispatch [TouchEvent]s, translate `gestureStateChanged` into camera updates. */
open class BasicWorldWindowController(
    protected val wwd: WorldWindow,
) : AbstractWorldWindowController(), WorldWindowController, GestureListener {

    override val engine get() = wwd.engine

    protected var lastX = 0f
    protected var lastY = 0f
    protected var lastRotation = 0f
    private var lastPanEventNanos: Long = 0L

    /** [WorldWindow.dispatchTouches] pre-multiplies touch coords by contentScaleFactor so they
     *  already match GL viewport pixels. */
    override val gestureToViewportPixels = 1.0

    override fun createFlingScheduler(): FrameScheduler = CADisplayLinkFrameScheduler()

    override fun requestRedraw() = wwd.requestRedraw()

    override fun cancelFling() = fling.cancel()

    /** Depth-buffer surface point under the touch via a synchronous pick; null over terrain/sky. */
    override fun pickSurfaceCartesian(viewportX: Double, viewportY: Double) =
        wwd.pick(viewportX.toFloat(), viewportY.toFloat()).topPickedObject?.cartesianPoint

    protected val panRecognizer: GestureRecognizer = PanRecognizer().also {
        it.addListener(this)
        it.maxNumberOfPointers = 1
        // 60 px ≈ 20 pt at @3x devices.
        it.interpretDistance = 60f
    }
    protected val pinchRecognizer: GestureRecognizer = PinchRecognizer().also {
        it.addListener(this)
        it.interpretDistance = 60f
    }
    protected val rotationRecognizer: GestureRecognizer = RotationRecognizer().also {
        it.addListener(this)
        it.interpretAngle = 20f
    }
    protected val tiltRecognizer: GestureRecognizer = PanRecognizer().also {
        it.addListener(this)
        it.minNumberOfPointers = 2
        it.interpretDistance = 90f
    }
    protected val allRecognizers = listOf(panRecognizer, pinchRecognizer, rotationRecognizer, tiltRecognizer)

    /** First [ViewControlsLayer] / [WorldMapLayer] in the engine's layer list, or null if absent.
     *  Scanned lazily once per touch event - the layer list mutates rarely, so a single linear
     *  scan beats two `filterIsInstance` allocations on the per-touch hot path. */
    private fun findHudLayers(): Pair<ViewControlsLayer?, WorldMapLayer?> {
        var vcl: ViewControlsLayer? = null
        var wml: WorldMapLayer? = null
        for (layer in engine.layers) {
            if (vcl == null && layer is ViewControlsLayer) vcl = layer
            if (wml == null && layer is WorldMapLayer) wml = layer
            if (vcl != null && wml != null) break
        }
        return vcl to wml
    }
    private var hudViewControlsLayer: ViewControlsLayer? = null
    private var hudWorldMapLayer: WorldMapLayer? = null

    /** ViewControlsLayer's continuous-press repeat: holding a pan/zoom/tilt control fires
     *  [ViewControlsLayer.handleClick] every 50 ms so the camera moves while the finger is down. */
    private var vcRepeatTimer: NSTimer? = null
    private var vcCurrentX = 0.0
    private var vcCurrentY = 0.0

    private fun stopVcRepeat() {
        vcRepeatTimer?.invalidate()
        vcRepeatTimer = null
    }

    /** Try to handle a [ViewControlsLayer] press / hold. Returns true if a control was hit. */
    private fun handleViewControls(event: TouchEvent): Boolean {
        val vcl = hudViewControlsLayer ?: return false
        val x = event.getX(event.actionIndex).toDouble()
        val y = event.getY(event.actionIndex).toDouble()
        val viewportH = engine.viewport.height
        when (event.actionMasked) {
            ACTION_DOWN -> {
                stopVcRepeat()
                if (vcl.handleClick(x, y, viewportH, engine)) {
                    wwd.requestRedraw()
                    vcCurrentX = x; vcCurrentY = y
                    vcRepeatTimer = NSTimer.scheduledTimerWithTimeInterval(
                        interval = 0.05, repeats = true,
                    ) {
                        if (vcl.handleClick(vcCurrentX, vcCurrentY, viewportH, engine))
                            wwd.requestRedraw()
                        else stopVcRepeat()
                    }
                    return true
                }
            }
            ACTION_MOVE -> if (vcRepeatTimer != null) {
                vcCurrentX = x; vcCurrentY = y
                return true
            }
            ACTION_UP, ACTION_CANCEL -> if (vcRepeatTimer != null) {
                stopVcRepeat()
                return true
            }
        }
        return false
    }

    /** Single-tap on the [WorldMapLayer] mini-map jumps the camera to that geographic point. */
    private fun handleWorldMap(event: TouchEvent): Boolean {
        if (event.actionMasked != ACTION_UP) return false
        val wml = hudWorldMapLayer ?: return false
        val x = event.getX(event.actionIndex).toDouble()
        val y = event.getY(event.actionIndex).toDouble()
        return wml.handleClick(x, y, engine.viewport.height, engine).also {
            if (it) wwd.requestRedraw()
        }
    }

    override fun onTouchEvent(event: TouchEvent): Boolean {
        if (event.actionMasked == ACTION_DOWN) {
            val (vcl, wml) = findHudLayers()
            hudViewControlsLayer = vcl
            hudWorldMapLayer = wml
        }
        if (handleViewControls(event)) return true
        if (handleWorldMap(event)) return true
        var handled = false
        for (recognizer in allRecognizers) handled = handled or recognizer.onTouchEvent(event)
        if (handled) {
            tiltRecognizer.isEnabled = !isInProcess(rotationRecognizer) || !rotationRecognizer.isEnabled
            rotationRecognizer.isEnabled = !isInProcess(tiltRecognizer) || !tiltRecognizer.isEnabled
        }
        return handled
    }

    override fun gestureStateChanged(event: TouchEvent, recognizer: GestureRecognizer) {
        when (recognizer) {
            panRecognizer -> handlePan(recognizer)
            pinchRecognizer -> handlePinch(recognizer)
            rotationRecognizer -> handleRotate(recognizer)
            tiltRecognizer -> handleTilt(recognizer)
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
                capturePanAnchorDistance(recognizer.x.toDouble(), recognizer.y.toDouble())
                lastX = 0f
                lastY = 0f
                velocitySampler.reset()
                lastPanEventNanos = nowNanos()
            }
            CHANGED -> {
                val now = nowNanos()
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
            BEGAN -> {
                gestureDidBegin()
                captureBeginLookAtPoint()
                lastX = 0f
                lastY = 0f
                velocitySampler.reset()
                lastPanEventNanos = nowNanos()
            }
            CHANGED -> {
                val metersPerPixel = engine.pixelSizeAtDistance(lookAt.range)
                val forwardMeters = ty * metersPerPixel
                val sideMeters = -tx * metersPerPixel
                val heading = lookAt.heading
                val sinHeading = sin(heading.inRadians)
                val cosHeading = cos(heading.inRadians)
                val x = beginLookAtPoint.x + forwardMeters * sinHeading + sideMeters * cosHeading
                val y = beginLookAtPoint.y + forwardMeters * cosHeading - sideMeters * sinHeading
                engine.globe.cartesianToGeographic(x, y, beginLookAtPoint.z, lookAt.position)
                applyChanges()

                val now = nowNanos()
                velocitySampler.record(
                    (tx - lastX).toDouble(), (ty - lastY).toDouble(),
                    (now - lastPanEventNanos) / 1_000_000.0
                )
                lastX = tx
                lastY = ty
                lastPanEventNanos = now
            }
            ENDED -> {
                val (vx, vy) = velocitySampler.computeReleaseVelocity()
                fling.start(vx, vy)
                gestureDidEnd()
            }
            CANCELLED -> {
                lookAt.copy(beginLookAt)
                applyChanges()
                gestureDidEnd()
            }
            else -> {}
        }
    }

    protected open fun handlePinch(recognizer: GestureRecognizer) {
        val state = recognizer.state
        val scale = (recognizer as PinchRecognizer).scaleWithOffset
        when (state) {
            BEGAN -> {
                gestureDidBegin()
                capturePivotAnchor(recognizer.x.toDouble(), recognizer.y.toDouble())
            }
            CHANGED -> if (scale != 0f) {
                lookAt.range = beginLookAt.range / scale
                pivotAnchor.apply()
                applyChanges()
            }
            ENDED, CANCELLED -> gestureDidEnd()
            else -> {}
        }
    }

    /** Programmatic pinch entry for Mac trackpad's native `UIPinchGestureRecognizer` -
     *  UITouch-based [PinchRecognizer] doesn't see indirect (trackpad) pinches. */
    fun handleIndirectPinch(state: GestureState, scale: Float, screenX: Float, screenY: Float) {
        when (state) {
            BEGAN -> {
                gestureDidBegin()
                capturePivotAnchor(screenX.toDouble(), screenY.toDouble())
            }
            CHANGED -> if (scale != 0f) {
                lookAt.range = beginLookAt.range / scale
                pivotAnchor.apply()
                applyChanges()
            }
            ENDED, CANCELLED -> gestureDidEnd()
            else -> {}
        }
    }

    /** Programmatic rotation entry for Mac trackpad's `UIRotationGestureRecognizer` -
     *  UITouch-based [RotationRecognizer] doesn't see indirect (trackpad) rotations. */
    fun handleIndirectRotate(state: GestureState, rotationRadians: Float, screenX: Float, screenY: Float) {
        val degrees = (rotationRadians.toDouble() * (180.0 / PI)).toFloat()
        when (state) {
            BEGAN -> {
                gestureDidBegin()
                lastRotation = 0f
                capturePivotAnchor(screenX.toDouble(), screenY.toDouble())
            }
            CHANGED -> {
                lookAt.heading = lookAt.heading.plusDegrees((lastRotation - degrees).toDouble()).normalize360()
                lastRotation = degrees
                pivotAnchor.apply()
                applyChanges()
            }
            ENDED, CANCELLED -> gestureDidEnd()
            else -> {}
        }
    }

    /** Programmatic tilt entry for Mac trackpad two-finger pan. */
    fun handleIndirectTilt(state: GestureState, translationY: Float) {
        val viewHeightPt = wwd.frame.useContents { size.height }.let { if (it > 0.0) it else 360.0 }
        when (state) {
            BEGAN -> { gestureDidBegin(); lastRotation = 0f }
            CHANGED -> {
                lookAt.tilt = beginLookAt.tilt.plusDegrees(-180.0 * translationY / viewHeightPt)
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
                capturePivotAnchor(recognizer.x.toDouble(), recognizer.y.toDouble())
            }
            CHANGED -> {
                val headingDegrees = lastRotation - rotation
                lookAt.heading = lookAt.heading.plusDegrees(headingDegrees.toDouble()).normalize360()
                lastRotation = rotation
                pivotAnchor.apply()
                applyChanges()
            }
            ENDED, CANCELLED -> gestureDidEnd()
            else -> {}
        }
    }

    protected open fun handleTilt(recognizer: GestureRecognizer) {
        val state = recognizer.state
        val dy = recognizer.translationY
        // Use the actual UIView height (in device pixels — matches our pre-scaled gesture coords)
        // for the tilt-by-screen-fraction normalization. Falls back to 1080 if the view isn't
        // sized yet so we never divide by zero.
        val viewHeightPx = (wwd.frame.useContents { size.height } * wwd.contentScaleFactor)
            .let { if (it > 0.0) it else 1080.0 }
        when (state) {
            BEGAN -> { gestureDidBegin(); lastRotation = 0f }
            CHANGED -> {
                val tiltDegrees = -180.0 * dy / viewHeightPx
                lookAt.tilt = beginLookAt.tilt.plusDegrees(tiltDegrees)
                applyChanges()
            }
            ENDED, CANCELLED -> gestureDidEnd()
            else -> {}
        }
    }

    protected open fun isInProcess(recognizer: GestureRecognizer) =
        recognizer.state == BEGAN || recognizer.state == CHANGED

    override fun release() {
        stopVcRepeat()
        cancelFling()
        super<AbstractWorldWindowController>.release()
    }

    private fun nowNanos(): Long = (CACurrentMediaTime() * 1_000_000_000.0).toLong()
}

/** Vsync-aligned scheduler driving [earth.worldwind.gesture.FlingAnimator] off CADisplayLink.
 *  iOS counterpart of the Android Choreographer scheduler. */
private class CADisplayLinkFrameScheduler : FrameScheduler {
    private val target = DisplayLinkTarget()
    private var displayLink: CADisplayLink? = null
    private var tickCallback: ((Double) -> Unit)? = null
    private var lastTime: Double = 0.0

    init {
        target.onTick = ::handleTick
    }

    private fun handleTick(link: CADisplayLink) {
        val cb = tickCallback ?: return
        val now = link.timestamp
        val dtMs = ((now - lastTime) * 1000.0).coerceAtMost(64.0)
        lastTime = now
        cb(dtMs)
    }

    override fun start(tick: (dtMs: Double) -> Unit) {
        tickCallback = tick
        lastTime = CACurrentMediaTime()
        val link = CADisplayLink.displayLinkWithTarget(target, NSSelectorFromString("tick:"))
        link.addToRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
        displayLink = link
    }

    override fun stop() {
        displayLink?.invalidate()
        displayLink = null
        tickCallback = null
    }
}

/** Holds the @ObjCAction selector that CADisplayLink targets. Must extend NSObject so it can be
 *  used as a target for an Obj-C selector dispatch; can't share with [BasicWorldWindowController]
 *  because mixing Kotlin interfaces and Obj-C supertypes is forbidden. */
@ExportObjCClass
private class DisplayLinkTarget : NSObject() {
    var onTick: ((CADisplayLink) -> Unit)? = null

    @ObjCAction
    fun tick(sender: CADisplayLink) {
        onTick?.invoke(sender)
    }
}
