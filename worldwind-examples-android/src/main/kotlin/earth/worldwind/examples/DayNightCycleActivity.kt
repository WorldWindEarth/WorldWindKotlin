package earth.worldwind.examples

import android.os.Bundle
import android.view.Choreographer
import android.view.Choreographer.FrameCallback
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Location.Companion.fromDegrees
import earth.worldwind.layer.atmosphere.AtmosphereLayer

open class DayNightCycleActivity : BasicGlobeActivity(), FrameCallback {
    protected val sunLocation = fromDegrees(0.0, -100.0)
    protected lateinit var atmosphereLayer: AtmosphereLayer

    // Animation settings
    protected var cameraDegreesPerSecond = 2.0
    protected var lightDegreesPerSecond = 6.0
    protected var lastFrameTimeNanos: Long = 0
    protected var activityPaused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aboutBoxTitle = "About the " + resources.getText(R.string.title_day_night_cycle)
        aboutBoxText = """
    Demonstrates how to display a continuous day-night cycle on the WorldWind globe.
    This gradually changes both the Camera's location and the AtmosphereLayer's light location.
    """.trimIndent()

        // Initialize the Atmosphere layer's light location to our custom location. By default, the light location is
        // always behind the viewer.
        val layers = wwd.engine.layers
        atmosphereLayer = layers.getLayer(layers.indexOfLayerNamed("Atmosphere")) as AtmosphereLayer
        atmosphereLayer.lightLocation = sunLocation

        // Initialize the Camera so that the sun is behind the viewer.
        val camera = wwd.engine.camera
        camera.position.latitude = fromDegrees(20.0)
        camera.position.longitude = sunLocation.longitude

        // Use this Activity's Choreographer to animate the day-night cycle.
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (lastFrameTimeNanos != 0L) {
            // Compute the frame duration in seconds.
            val frameDurationSeconds = (frameTimeNanos - lastFrameTimeNanos) * 1.0e-9
            val cameraDegrees = frameDurationSeconds * cameraDegreesPerSecond
            val lightDegrees = frameDurationSeconds * lightDegreesPerSecond

            // Move the camera to simulate the Earth's rotation about its axis.
            val camera = wwd.engine.camera
            camera.position.longitude = camera.position.longitude.minusDegrees(cameraDegrees)

            // Move the sun location to simulate the Sun's rotation about the Earth.
            sunLocation.set(
                sunLocation.latitude,
                sunLocation.longitude.minusDegrees(lightDegrees)
            )
            atmosphereLayer.lightLocation = sunLocation

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