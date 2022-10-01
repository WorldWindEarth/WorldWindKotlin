package earth.worldwind.tutorials

import earth.worldwind.BasicWorldWindowController
import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Camera
import earth.worldwind.gesture.GestureRecognizer
import earth.worldwind.gesture.GestureState.*
import earth.worldwind.gesture.PinchRecognizer
import earth.worldwind.gesture.RotationRecognizer
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

open class CameraControlFragment: BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow object with a custom WorldWindowController.
     */
    override fun createWorldWindow(): WorldWindow {
        // Let the super class (BasicGlobeFragment) do the creation
        val wwd = super.createWorldWindow()

        // Override the default "look at" gesture behavior with a camera centric gesture controller
        wwd.controller = CameraController(wwd)

        // Apply camera position above KOXR airport, Oxnard, CA
        wwd.engine.camera.setDegrees(
            latitudeDegrees = 34.2,
            longitudeDegrees = -119.2,
            altitudeMeters = 10000.0,
            altitudeMode = AltitudeMode.ABSOLUTE,
            headingDegrees = 90.0,
            tiltDegrees = 70.0,
            rollDegrees = 0.0
        )
        return wwd
    }

    /**
     * A custom WorldWindController that uses gestures to control the camera directly via the setAsCamera interface
     * instead of the default setAsLookAt interface.
     */
    private open class CameraController(wwd: WorldWindow): BasicWorldWindowController(wwd) {
        protected var beginAltitude = 0.0
        protected var beginHeading = ZERO
        protected var beginTilt = ZERO
        protected var beginRoll = ZERO

        override fun handlePan(recognizer: GestureRecognizer) {
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
                    val camera = wwd.engine.camera

                    // Get the camera's current position.
                    var lat = camera.position.latitude
                    var lon = camera.position.longitude
                    val alt = camera.position.altitude

                    // Convert the translation from screen coordinates to degrees. Use the camera's range as a metric for
                    // converting screen pixels to meters, and use the globe's radius for converting from meters to arc degrees.
                    val metersPerPixel = wwd.engine.pixelSizeAtDistance(alt)
                    val forwardMeters = (dy - lastY) * metersPerPixel
                    val sideMeters = -(dx - lastX) * metersPerPixel
                    lastX = dx
                    lastY = dy
                    val globeRadius = wwd.engine.globe.getRadiusAt(lat, lon)
                    val forwardRadians = forwardMeters / globeRadius
                    val sideRadians = sideMeters / globeRadius

                    // Adjust the change in latitude and longitude based on the camera's heading.
                    val heading = camera.heading
                    val sinHeading = sin(heading.radians)
                    val cosHeading = cos(heading.radians)
                    lat = lat.plusRadians(forwardRadians * cosHeading - sideRadians * sinHeading)
                    lon = lon.plusRadians(forwardRadians * sinHeading + sideRadians * cosHeading)

                    // If the camera has panned over either pole, compensate by adjusting the longitude and heading to move
                    // the camera to the appropriate spot on the other side of the pole.
                    if (lat.degrees < -90 || lat.degrees > 90) {
                        camera.position.latitude = lat.normalizeLatitude()
                        camera.position.longitude = lon.plusDegrees(180.0).normalizeLongitude()
                    } else if (lon.degrees < -180 || lon.degrees > 180) {
                        camera.position.latitude = lat
                        camera.position.longitude = lon.normalizeLongitude()
                    } else {
                        camera.position.latitude = lat
                        camera.position.longitude = lon
                    }
                    applyLimits(camera)
                    wwd.requestRedraw()
                }
                ENDED, CANCELLED -> gestureDidEnd()
                else -> {}
            }
        }

        override fun handlePinch(recognizer: GestureRecognizer) {
            val state = recognizer.state
            val scale = (recognizer as PinchRecognizer).scaleWithOffset
            when (state) {
                BEGAN -> gestureDidBegin()
                CHANGED -> {
                    if (scale != 0f) {
                        // Apply the change in scale to the camera, relative to when the gesture began.
                        val camera = wwd.engine.camera
                        camera.position.altitude = beginAltitude / scale
                        applyLimits(camera)
                        wwd.requestRedraw()
                    }
                }
                ENDED, CANCELLED -> gestureDidEnd()
                else -> {}
            }
        }

        override fun handleRotate(recognizer: GestureRecognizer) {
            val state = recognizer.state
            val rotation = (recognizer as RotationRecognizer).rotationWithOffset
            when (state) {
                BEGAN -> {
                    gestureDidBegin()
                    lastRotation = 0f
                }
                CHANGED -> {
                    // Apply the change in rotation to the camera, relative to the camera's current values.
                    val rollDegrees = (lastRotation - rotation).toDouble()
                    val camera = wwd.engine.camera
                    camera.roll = camera.roll.minusDegrees(rollDegrees).normalize360()
                    lastRotation = rotation
                    applyLimits(camera)
                    wwd.requestRedraw()
                }
                ENDED, CANCELLED -> gestureDidEnd()
                else -> {}
            }
        }

        override fun handleTilt(recognizer: GestureRecognizer) {
            val state = recognizer.state
            val dx = recognizer.translationX
            val dy = recognizer.translationY
            when (state) {
                BEGAN -> {
                    gestureDidBegin()
                    lastRotation = 0f
                }
                CHANGED -> {
                    // Apply the change in tilt to the camera, relative to when the gesture began.
                    val headingDegrees = (180 * dx / wwd.width).toDouble()
                    val tiltDegrees = (-180 * dy / wwd.height).toDouble()
                    val camera = wwd.engine.camera
                    camera.heading = beginHeading.minusDegrees(headingDegrees).normalize360()
                    camera.tilt = beginTilt.minusDegrees(tiltDegrees)
                    applyLimits(camera)
                    wwd.requestRedraw()
                }
                ENDED, CANCELLED -> gestureDidEnd()
                else -> {}
            }
        }

        override fun gestureDidBegin() {
            if (activeGestures++ == 0) {
                val camera = wwd.engine.camera
                beginAltitude = camera.position.altitude
                beginHeading = camera.heading
                beginTilt = camera.tilt
                beginRoll = camera.roll
            }
        }

        protected open fun applyLimits(camera: Camera) {
            val position = camera.position
            val distanceToExtents = wwd.engine.distanceToViewGlobeExtents
            val minAltitude = 100.0
            position.altitude = position.altitude.coerceIn(minAltitude, distanceToExtents)

            // Check if camera altitude is not under the surface
            val ve = wwd.engine.verticalExaggeration
            if (position.altitude < COLLISION_CHECK_LIMIT * ve + COLLISION_THRESHOLD) {
                val elevation = wwd.engine.globe.getElevationAtLocation(
                    position.latitude, position.longitude
                ) * ve + COLLISION_THRESHOLD
                if (elevation > position.altitude) position.altitude = elevation
            }

            // Limit the tilt to between nadir and the horizon (roughly)
            val r = wwd.engine.globe.getRadiusAt(position.latitude, position.longitude)
            val maxTilt = Math.toDegrees(asin(r / (r + position.altitude)))
            val minTilt = 0.0
            camera.tilt = fromDegrees(camera.tilt.degrees.coerceIn(minTilt, maxTilt))
        }

        companion object {
            private const val COLLISION_CHECK_LIMIT = 8848.86 // Everest mountain altitude
            private const val COLLISION_THRESHOLD = 20.0 // 20m above surface
        }
    }
}