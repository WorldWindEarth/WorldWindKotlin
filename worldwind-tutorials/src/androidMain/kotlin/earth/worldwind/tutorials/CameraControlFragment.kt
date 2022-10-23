package earth.worldwind.tutorials

import earth.worldwind.BasicWorldWindowController
import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Camera
import earth.worldwind.gesture.GestureRecognizer
import earth.worldwind.gesture.GestureState.*
import earth.worldwind.gesture.PinchRecognizer
import earth.worldwind.gesture.RotationRecognizer
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

class CameraControlFragment: BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow object with a custom WorldWindowController.
     */
    override fun createWorldWindow() = super.createWorldWindow().apply {
        // Override the default "look at" gesture behavior with a camera centric gesture controller
        controller = CameraController(wwd)

        // Apply camera position above KOXR airport, Oxnard, CA
        engine.camera.set(
            latitude = 34.2.degrees,
            longitude = (-119.2).degrees,
            altitude = 10000.0,
            altitudeMode = AltitudeMode.ABSOLUTE,
            heading = 90.0.degrees,
            tilt = 70.0.degrees,
            roll = 0.0.degrees
        )
    }

    /**
     * A custom WorldWindController that uses gestures to control the camera directly via the setAsCamera interface
     * instead of the default setAsLookAt interface.
     */
    private class CameraController(wwd: WorldWindow): BasicWorldWindowController(wwd) {
        private var beginAltitude = 0.0
        private var beginHeading = ZERO
        private var beginTilt = ZERO
        private var beginRoll = ZERO

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
                    wwd.engine.camera.run {
                        // Get the camera's current position.
                        var lat = position.latitude
                        var lon = position.longitude
                        val alt = position.altitude

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
                        val sinHeading = sin(heading.inRadians)
                        val cosHeading = cos(heading.inRadians)
                        lat = lat.plusRadians(forwardRadians * cosHeading - sideRadians * sinHeading)
                        lon = lon.plusRadians(forwardRadians * sinHeading + sideRadians * cosHeading)

                        // If the camera has panned over either pole, compensate by adjusting the longitude and heading to move
                        // the camera to the appropriate spot on the other side of the pole.
                        if (lat.inDegrees < -90 || lat.inDegrees > 90) {
                            position.latitude = lat.normalizeLatitude()
                            position.longitude = lon.plusDegrees(180.0).normalizeLongitude()
                        } else if (lon.inDegrees < -180 || lon.inDegrees > 180) {
                            position.latitude = lat
                            position.longitude = lon.normalizeLongitude()
                        } else {
                            position.latitude = lat
                            position.longitude = lon
                        }
                        applyLimits(this)
                        wwd.requestRedraw()
                    }
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
                        wwd.engine.camera.run {
                            position.altitude = beginAltitude / scale
                            applyLimits(this)
                            wwd.requestRedraw()
                        }
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
                    wwd.engine.camera.run {
                        // Apply the change in rotation to the camera, relative to the camera's current values.
                        val rollDegrees = (lastRotation - rotation).toDouble()
                        roll = roll.minusDegrees(rollDegrees).normalize360()
                        lastRotation = rotation
                        applyLimits(this)
                        wwd.requestRedraw()
                    }
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
                    wwd.engine.camera.run {
                        // Apply the change in tilt to the camera, relative to when the gesture began.
                        val headingDegrees = (180 * dx / wwd.width).toDouble()
                        val tiltDegrees = (-180 * dy / wwd.height).toDouble()
                        heading = beginHeading.minusDegrees(headingDegrees).normalize360()
                        tilt = beginTilt.minusDegrees(tiltDegrees)
                        applyLimits(this)
                        wwd.requestRedraw()
                    }
                }
                ENDED, CANCELLED -> gestureDidEnd()
                else -> {}
            }
        }

        override fun gestureDidBegin() {
            if (activeGestures++ == 0) {
                wwd.engine.camera.run {
                    beginAltitude = position.altitude
                    beginHeading = heading
                    beginTilt = tilt
                    beginRoll = roll
                }
            }
        }

        private fun applyLimits(camera: Camera) {
            val position = camera.position
            val distanceToExtents = wwd.engine.distanceToViewGlobeExtents * 1.1
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
            camera.tilt = camera.tilt.inDegrees.coerceIn(minTilt, maxTilt).degrees
        }

        companion object {
            private const val COLLISION_CHECK_LIMIT = 8848.86 // Everest mountain altitude
            private const val COLLISION_THRESHOLD = 20.0 // 20m above surface
        }
    }
}