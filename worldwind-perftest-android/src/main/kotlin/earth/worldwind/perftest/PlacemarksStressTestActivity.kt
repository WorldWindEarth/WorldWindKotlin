package earth.worldwind.perftest

import android.os.Bundle
import android.view.Choreographer
import android.view.Choreographer.FrameCallback
import earth.worldwind.geom.Angle.Companion.toDegrees
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.layer.ShowTessellationLayer
import earth.worldwind.render.image.ImageSource.Companion.fromResource
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkAttributes
import earth.worldwind.shape.PlacemarkAttributes.Companion.createWithImage
import earth.worldwind.shape.PlacemarkAttributes.Companion.createWithImageAndLeader
import kotlin.math.asin
import kotlin.random.Random

open class PlacemarksStressTestActivity: GeneralGlobeActivity(), FrameCallback {
    protected var activityPaused = false
    protected var cameraDegreesPerSecond = 2.0
    protected var lastFrameTimeNanos = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aboutBoxTitle = "About the " + resources.getText(R.string.title_placemarks_stress_test)
        aboutBoxText = "Demonstrates a LOT of Placemarks."

        // Turn off all layers while debugging/profiling memory allocations...
        for (l in wwd.engine.layers) l.isEnabled = false
        // ... and add the tessellation layer instead
        wwd.engine.layers.addLayer(ShowTessellationLayer())

        // Create a Renderable layer for the placemarks and add it to the WorldWindow
        val placemarksLayer = RenderableLayer("Placemarks")
        wwd.engine.layers.addLayer(placemarksLayer)

        // Create some placemarks at a known locations
        val origin = Placemark(
            fromDegrees(0.0, 0.0, 1e5),
            createWithImageAndLeader(fromResource(R.drawable.airport)), "Origin"
        )
        val northPole = Placemark(
            fromDegrees(90.0, 0.0, 1e5),
            createWithImageAndLeader(fromResource(R.drawable.airport_terminal)), "North Pole"
        )
        val southPole = Placemark(
            fromDegrees(-90.0, 0.0, 0.0),
            createWithImage(fromResource(R.drawable.airplane)), "South Pole"
        )
        val antiMeridian = Placemark(
            fromDegrees(0.0, 180.0, 0.0),
            createWithImage(fromResource(R.drawable.ic_menu_home)), "Anti-meridian"
        )
        placemarksLayer.addRenderable(origin)
        placemarksLayer.addRenderable(northPole)
        placemarksLayer.addRenderable(southPole)
        placemarksLayer.addRenderable(antiMeridian)

        // Create a random number generator with an arbitrary seed
        // that will generate the same numbers between runs.
        val random = Random(123)

        // Create pushpins anchored at the "pinpoints" with eye distance scaling
        val attributes = createWithImage(fromResource(R.drawable.aircraft_fixwing))
        for (i in 0 until NUM_PLACEMARKS) {
            // Create an even distribution of latitude and longitudes across the globe.
            // Use a random sin value to generate latitudes without clustering at the poles.
            val lat = toDegrees(asin(random.nextDouble())) * if (random.nextBoolean()) 1 else -1
            val lon = 180.0 - random.nextDouble() * 360
            val pos = fromDegrees(lat, lon, 0.0)
            val placemarkAttributes = PlacemarkAttributes(attributes)
            placemarkAttributes.minimumImageScale = 0.5
            val placemark = Placemark(pos, placemarkAttributes)
            placemark.isEyeDistanceScaling = true
            placemark.displayName = placemark.position.toString()
            placemarksLayer.addRenderable(placemark)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (lastFrameTimeNanos != 0L) {
            // Compute the frame duration in seconds.
            val frameDurationSeconds = (frameTimeNanos - lastFrameTimeNanos) * 1.0e-9
            val cameraDegrees = frameDurationSeconds * cameraDegreesPerSecond

            // Move the camera to simulate the Earth's rotation about its axis.
            val camera = wwd.engine.camera
            camera.position.longitude = camera.position.longitude.minusDegrees(cameraDegrees)

            // Redraw the WorldWindow to display the above changes.
            wwd.requestRedraw()
        }
        // stop animating when this Activity is paused
        if (!activityPaused) Choreographer.getInstance().postFrameCallback(this)
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
        // Resume the earth rotation animation
        activityPaused = false
        lastFrameTimeNanos = 0
        Choreographer.getInstance().postFrameCallback(this)
    }

    companion object {
        protected const val NUM_PLACEMARKS = 10000
    }
}