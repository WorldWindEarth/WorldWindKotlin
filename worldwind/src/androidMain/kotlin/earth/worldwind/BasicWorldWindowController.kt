package earth.worldwind

import android.view.MotionEvent
import earth.worldwind.geom.Angle.Companion.NEG180
import earth.worldwind.geom.Angle.Companion.NEG90
import earth.worldwind.geom.Angle.Companion.POS180
import earth.worldwind.geom.Angle.Companion.POS90
import earth.worldwind.geom.LookAt
import earth.worldwind.gesture.*
import earth.worldwind.gesture.GestureState.*
import kotlin.math.cos
import kotlin.math.sin

open class BasicWorldWindowController(protected val wwd: WorldWindow): WorldWindowController, GestureListener {

    protected var lastX = 0f
    protected var lastY = 0f
    protected var lastRotation = 0f
    protected val lookAt = LookAt()
    protected val beginLookAt = LookAt()
    protected var activeGestures = 0
    protected val panRecognizer = PanRecognizer()
    protected val pinchRecognizer = PinchRecognizer()
    protected val rotationRecognizer = RotationRecognizer()
    protected val tiltRecognizer = PanRecognizer()
    protected val mouseTiltRecognizer = MousePanRecognizer()
    protected val allRecognizers = listOf(
        panRecognizer, pinchRecognizer, rotationRecognizer, tiltRecognizer, mouseTiltRecognizer
    )

    init {
        panRecognizer.addListener(this)
        pinchRecognizer.addListener(this)
        rotationRecognizer.addListener(this)
        tiltRecognizer.addListener(this)
        mouseTiltRecognizer.addListener(this)
        panRecognizer.maxNumberOfPointers = 1 // Do not pan during tilt
        tiltRecognizer.minNumberOfPointers = 2 // Use two fingers for tilt gesture
        mouseTiltRecognizer.buttonState = MotionEvent.BUTTON_SECONDARY

        // Set interpret distance based on screen density
        panRecognizer.interpretDistance = wwd.context.resources.getDimension(R.dimen.pan_interpret_distance)
        pinchRecognizer.interpretDistance = wwd.context.resources.getDimension(R.dimen.pinch_interpret_distance)
        rotationRecognizer.interpretAngle = 20f
        tiltRecognizer.interpretDistance = wwd.context.resources.getDimension(R.dimen.tilt_interpret_distance)
        mouseTiltRecognizer.interpretDistance = wwd.context.resources.getDimension(R.dimen.tilt_interpret_distance)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
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
        val state = recognizer.state
        val dx = recognizer.translationX
        val dy = recognizer.translationY
        when (state) {
            BEGAN -> {
                gestureDidBegin()
                lastX = 0f
                lastY = 0f
            }
            CHANGED -> {
                // Get observation point position.
                var lat = lookAt.position.latitude
                var lon = lookAt.position.longitude
                val rng = lookAt.range

                // Convert the translation from screen coordinates to degrees. Use observation point range as a metric for
                // converting screen pixels to meters, and use the globe's radius for converting from meters to arc degrees.
                val metersPerPixel = wwd.engine.pixelSizeAtDistance(rng)
                val forwardMeters = (dy - lastY) * metersPerPixel
                val sideMeters = -(dx - lastX) * metersPerPixel
                lastX = dx
                lastY = dy
                val globeRadius = wwd.engine.globe.getRadiusAt(lat, lon)
                val forwardRadians = forwardMeters / globeRadius
                val sideRadians = sideMeters / globeRadius

                // Adjust the change in latitude and longitude based on observation point heading.
                val heading = lookAt.heading
                val sinHeading = sin(heading.inRadians)
                val cosHeading = cos(heading.inRadians)
                lat = lat.plusRadians(forwardRadians * cosHeading - sideRadians * sinHeading)
                lon = lon.plusRadians(forwardRadians * sinHeading + sideRadians * cosHeading)

                // If the camera has panned over either pole, compensate by adjusting the longitude and heading to move
                // the camera to the appropriate spot on the other side of the pole.
                if (lat < NEG90 || lat > POS90) {
                    lookAt.position.latitude = lat.normalizeLatitude()
                    lookAt.position.longitude = (lon + POS180).normalizeLongitude()
                    lookAt.heading = (heading + POS180).normalize360()
                } else if (lon < NEG180 || lon > POS180) {
                    lookAt.position.latitude = lat
                    lookAt.position.longitude = lon.normalizeLongitude()
                } else {
                    lookAt.position.latitude = lat
                    lookAt.position.longitude = lon
                }
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
            BEGAN -> gestureDidBegin()
            CHANGED -> if (scale != 0f) {
                // Apply the change in range to observation point, relative to when the gesture began.
                lookAt.range = beginLookAt.range / scale
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

    protected open fun applyChanges() {
        // Update camera view
        wwd.engine.cameraFromLookAt(lookAt)
        wwd.requestRedraw()
    }

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

    protected open fun isInProcess(recognizer: GestureRecognizer) = recognizer.state == BEGAN || recognizer.state == CHANGED
}