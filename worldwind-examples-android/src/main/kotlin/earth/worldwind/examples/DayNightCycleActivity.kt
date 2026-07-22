package earth.worldwind.examples

import android.os.Bundle
import android.view.Choreographer
import android.view.Choreographer.FrameCallback
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.util.SunPosition
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

open class DayNightCycleActivity : BasicGlobeActivity(), FrameCallback {
    // Animation settings
    protected var cameraDegreesPerSecond = 2.0
    protected var timeFactor = 3600 // One hour per second
    protected var lastFrameTimeNanos: Long = 0
    protected var activityPaused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aboutBoxTitle = "About the " + resources.getText(R.string.title_day_night_cycle)
        aboutBoxText = """
    Demonstrates how to display a continuous day-night cycle on the WorldWind globe.
    This gradually changes both the Camera's location and the engine's scene time.
    """.trimIndent()

        // Initialize the engine's scene time so the light, terminator and star field follow the real sun.
        // By default, the light location is always behind the viewer.
        val time = Clock.System.now()
        wwd.engine.time = time

        // Initialize the Camera so that the sun is behind the viewer.
        wwd.engine.camera.position.apply {
            latitude = 20.0.degrees
            longitude = SunPosition.getAsGeographicLocation(time).longitude
        }

        // Use this Activity's Choreographer to animate the day-night cycle.
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (lastFrameTimeNanos != 0L) {
            // Compute the frame duration in seconds.
            val frameDurationSeconds = (frameTimeNanos - lastFrameTimeNanos) * 1.0e-9
            val cameraDegrees = frameDurationSeconds * cameraDegreesPerSecond
            val timePassed = frameDurationSeconds * timeFactor

            // Move the camera to simulate the Earth's rotation about its axis.
            val camera = wwd.engine.camera
            camera.position.longitude = camera.position.longitude.minusDegrees(cameraDegrees)

            // Move the sun location to simulate the Sun's rotation about the Earth.
            wwd.engine.time = wwd.engine.time?.plus(timePassed.seconds)

            // Redraw the WorldWindow to display the above changes.
            wwd.requestRedraw()
        }
        if (!activityPaused) { // stop animating when this Activity is paused
            Choreographer.getInstance().postFrameCallback(this)
        }
        lastFrameTimeNanos = frameTimeNanos
    }

    override fun onPause() {
        super.onPause()
        // Stop running the animation when this activity is paused.
        activityPaused = true
        lastFrameTimeNanos = 0
    }

    override fun onResume() {
        super.onResume()
        // Resume the day-night cycle animation.
        activityPaused = false
        lastFrameTimeNanos = 0
        Choreographer.getInstance().postFrameCallback(this)
    }
}