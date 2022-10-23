package earth.worldwind.examples

import android.os.Bundle
import android.view.Choreographer
import android.view.Choreographer.FrameCallback
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.layer.ShowTessellationLayer

open class BasicStressTestActivity: GeneralGlobeActivity(), FrameCallback {
    protected var cameraDegreesPerSecond = 0.1
    protected var lastFrameTimeNanos: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aboutBoxTitle = "About the " + resources.getText(R.string.title_basic_stress_test)
        aboutBoxText = "Continuously moves the camera in an Easterly direction from a low altitude."

        // Add the ShowTessellation layer to provide some visual feedback regardless of texture details
        wwd.engine.layers.addLayer(ShowTessellationLayer())

        // Initialize the Camera so that it's looking in the direction of movement and the horizon is visible.
        wwd.engine.camera.apply {
            position.altitude = 1e3 // 1 km
            heading = 90.0.degrees // looking east
            tilt = 75.0.degrees // looking at the horizon
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (lastFrameTimeNanos != 0L) {
            // Compute the frame duration in seconds.
            val frameDurationSeconds = (frameTimeNanos - lastFrameTimeNanos) * 1.0e-9
            val cameraDegrees = frameDurationSeconds * cameraDegreesPerSecond

            // Move the camera to continuously bring new tiles into view.
            val camera = wwd.engine.camera
            camera.position.longitude = camera.position.longitude.plusDegrees(cameraDegrees)

            // Redraw the WorldWindow to display the above changes.
            wwd.requestRedraw()
        }
        Choreographer.getInstance().postFrameCallback(this)
        lastFrameTimeNanos = frameTimeNanos
    }

    override fun onPause() {
        super.onPause()
        // Stop running the animation when this activity is paused.
        Choreographer.getInstance().removeFrameCallback(this)
        lastFrameTimeNanos = 0
    }

    override fun onResume() {
        super.onResume()
        // Use this Activity's Choreographer to animate the Camera.
        Choreographer.getInstance().postFrameCallback(this)
        lastFrameTimeNanos = 0
    }
}