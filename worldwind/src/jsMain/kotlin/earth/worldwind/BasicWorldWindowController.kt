package earth.worldwind

import earth.worldwind.geom.Angle
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Vec2
import earth.worldwind.gesture.*
import earth.worldwind.gesture.GestureState.*
import org.w3c.dom.events.Event
import org.w3c.dom.events.WheelEvent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * This class provides the default window controller for WorldWind for controlling the globe via user interaction.
 */
open class BasicWorldWindowController(wwd: WorldWindow): WorldWindowController(wwd) {
    val primaryDragRecognizer = DragRecognizer(wwd.canvas).also { it.addListener(this) }
    val secondaryDragRecognizer = DragRecognizer(wwd.canvas).also {
        it.addListener(this)
        it.button = 2 // secondary mouse button
    }
    val panRecognizer = PanRecognizer(wwd.canvas).also { it.addListener(this) }
    val pinchRecognizer = PinchRecognizer(wwd.canvas).also { it.addListener(this) }
    val rotationRecognizer = RotationRecognizer(wwd.canvas).also { it.addListener(this) }
    val tiltRecognizer = TiltRecognizer(wwd.canvas).also { it.addListener(this) }
//    val tapRecognizer = TapRecognizer(wwd.canvas).also { it.addListener(this) }
//    val clickRecognizer = ClickRecognizer(wwd.canvas).also { it.addListener(this) }
    /**
     * A copy of the viewing parameters at the start of a gesture as a look at view.
     */
    protected val beginLookAt = LookAt()
    /**
     * The current state of the viewing parameters during a gesture as a look at view.
     */
    protected val lookAt = LookAt()
    protected val beginPoint = Vec2()
    protected val lastPoint = Vec2()
    protected var lastRotation = 0.0
    protected var lastWheelEvent = 0
    protected var activeGestures = 0

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

    override fun handleEvent(event: Event) {
        super.handleEvent(event)
        if (!event.defaultPrevented) {
            if (event.type == "wheel") {
                event.preventDefault()
                handleWheelEvent(event as WheelEvent)
            } else GestureRecognizer.allRecognizers.forEach { r -> if (r.target == wwd.canvas) r.handleEvent(event) }
        }
    }

    override fun gestureStateChanged(recognizer: GestureRecognizer) {
        when(recognizer) {
            primaryDragRecognizer, panRecognizer -> handlePanOrDrag(recognizer)
            secondaryDragRecognizer -> handleSecondaryDrag(recognizer)
            pinchRecognizer -> handlePinch(recognizer as PinchRecognizer)
            rotationRecognizer -> handleRotation(recognizer as RotationRecognizer)
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
            }
            CHANGED -> {
                // Convert the translation from screen coordinates to arc degrees. Use the view's range as a
                // metric for converting screen pixels to meters, and use the globe's radius for converting from meters
                // to arc degrees. Transform viewport pixel size to canvas client pixel size.
                val globe = wwd.engine.globe
                val globeRadius = max(globe.equatorialRadius, globe.polarRadius)
                val distance = max(1.0, lookAt.range)
                val metersPerPixel = wwd.engine.pixelSizeAtDistance(distance) * wwd.engine.densityFactor
                val forwardMeters = (ty - lastPoint.y) * metersPerPixel
                val sideMeters = -(tx - lastPoint.x) * metersPerPixel
                val forwardRadians = forwardMeters / globeRadius
                val sideRadians = sideMeters / globeRadius

                // Apply the change in latitude and longitude to the view, relative to the current heading.
                val sinHeading = sin(lookAt.heading.inRadians)
                val cosHeading = cos(lookAt.heading.inRadians)
                lookAt.position.apply {
                    latitude = latitude.plusRadians(forwardRadians * cosHeading - sideRadians * sinHeading)
                    longitude = longitude.plusRadians(forwardRadians * sinHeading + sideRadians * cosHeading)
                }
                lastPoint.set(tx, ty)
                applyChanges()
            }
            ENDED, CANCELLED -> gestureDidEnd()
            else -> {}
        }
    }

    protected open fun handlePanOrDrag2D(recognizer: GestureRecognizer) {
        val state = recognizer.state
        val x = recognizer.clientX.toDouble()
        val y = recognizer.clientY.toDouble()
        val tx = recognizer.translationX
        val ty = recognizer.translationY

        when (state) {
            BEGAN -> {
                gestureDidBegin()
                beginPoint.set(x, y)
                lastPoint.set(x, y)
            }
            CHANGED -> {
                val x1 = lastPoint.x
                val y1 = lastPoint.y
                val x2 = beginPoint.x + tx
                val y2 = beginPoint.y + ty
                lastPoint.set(x2, y2)
                // Transform the original view's modelview matrix to account for the gesture's change.
                wwd.engine.moveLookAt(lookAt, wwd.canvasCoordinates(x1, y1), wwd.canvasCoordinates(x2, y2))
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

    protected open fun handlePinch(recognizer: PinchRecognizer) {
        val state = recognizer.state
        val scale = recognizer.scaleWithOffset

        when(state) {
            BEGAN -> gestureDidBegin()
            CHANGED -> if (scale != 0.0) {
                // Apply the change in pinch scale to this view's range, relative to the range when the gesture began.
                lookAt.range = beginLookAt.range / scale
                applyChanges()
            }
            ENDED, CANCELLED -> gestureDidEnd()
            else -> {}
        }
    }

    protected open fun handleRotation(recognizer: RotationRecognizer) {
        val state = recognizer.state
        val rotation = recognizer.rotationWithOffset

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
        val timeStamp = event.timeStamp.toInt()
        if (timeStamp - lastWheelEvent > 500) {
            wwd.engine.cameraAsLookAt(lookAt)
            lastWheelEvent = timeStamp
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

        // Apply the scale to this view's properties.
        lookAt.range *= scale
        applyChanges()
    }

    /**
     * Limits the properties of a look at view to prevent unwanted navigation behaviour and update camera view.
     */
    protected open fun applyChanges() {
        // Clamp latitude to between -90 and +90, and normalize longitude to between -180 and +180.
        lookAt.position.latitude = lookAt.position.latitude.clampLatitude()
        lookAt.position.longitude = lookAt.position.longitude.normalizeLongitude()

        // Clamp range to values greater than 1 in order to prevent degenerating to a first-person lookAt when
        // range is zero.
        lookAt.range = lookAt.range.coerceIn(10.0, wwd.engine.distanceToViewGlobeExtents * 2)

        // Normalize heading to between -180 and +180.
        lookAt.heading = lookAt.heading.normalize180()

        // Clamp tilt to between 0 and +90 to prevent the viewer from going upside down.
        lookAt.tilt = lookAt.tilt.coerceIn(Angle.ZERO, Angle.POS90)

        // Normalize heading to between -180 and +180.
        lookAt.roll = lookAt.roll.normalize180()

        // Apply 2D limits when the globe is 2D.
        if (wwd.engine.globe.is2D) {
            // Clamp range to prevent more than 360 degrees of visible longitude. Assumes a 45 degree horizontal
            // field of view.
            lookAt.range = lookAt.range.coerceIn(1.0, 2.0 * PI * wwd.engine.globe.equatorialRadius)

            // Force tilt to 0 when in 2D mode to keep the viewer looking straight down.
            lookAt.tilt = Angle.ZERO
        }

        // Update camera view
        wwd.engine.cameraFromLookAt(lookAt)
        wwd.requestRedraw()
    }

    /**
     * Sets common variables at the beginning of gesture.
     */
    protected open fun gestureDidBegin() {
        if (activeGestures++ == 0) {
            wwd.engine.cameraAsLookAt(beginLookAt)
            lookAt.copy(beginLookAt)
        }
    }

    protected open fun gestureDidEnd() {
        // this should always be the case, but we check anyway
        if (activeGestures > 0) activeGestures--
    }
}