package earth.worldwind.tutorials

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import earth.worldwind.WorldWindow
import earth.worldwind.geom.Camera
import earth.worldwind.geom.LookAt
import earth.worldwind.navigator.NavigatorAction
import earth.worldwind.navigator.NavigatorEvent
import earth.worldwind.navigator.NavigatorListener
import kotlin.math.sign

class NavigatorEventFragment : BasicGlobeFragment() {
    // UI elements
    private lateinit var latView: TextView
    private lateinit var lonView: TextView
    private lateinit var altView: TextView
    private lateinit var crosshairs: ImageView
    private lateinit var overlay: ViewGroup
    // Use pre-allocated lookAt state object to avoid per-event memory allocations
    private val lookAt = LookAt()
    // Track the navigation event time so the overlay refresh rate can be throttled
    private var lastEventTime: Long = 0
    // Animation object used to fade the overlays
    private lateinit var animatorSet: AnimatorSet
    private var crosshairsActive = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        // Let the super class (BasicGlobeFragment) create the view and the WorldWindow
        val rootView = super.onCreateView(inflater, container, savedInstanceState)!!

        // Initialize the UI elements that we'll update upon the navigation events
        crosshairs = rootView.findViewById(R.id.globe_crosshairs)
        overlay = rootView.findViewById(R.id.globe_status)
        crosshairs.visibility = View.VISIBLE
        overlay.visibility = View.VISIBLE
        latView = rootView.findViewById(R.id.lat_value)
        lonView = rootView.findViewById(R.id.lon_value)
        altView = rootView.findViewById(R.id.alt_value)
        val fadeOut = ObjectAnimator.ofFloat(crosshairs, "alpha", 0f).setDuration(1500)
        fadeOut.startDelay = 500
        animatorSet = AnimatorSet()
        animatorSet.play(fadeOut)

        // Create a simple Navigator Listener that logs navigator events emitted by the WorldWindow.
        val listener = object : NavigatorListener {
            override fun onNavigatorEvent(wwd: WorldWindow, event: NavigatorEvent) {
                val currentTime = System.currentTimeMillis()
                val elapsedTime = currentTime - lastEventTime
                val eventAction = event.action
                val receivedUserInput = eventAction == NavigatorAction.MOVED && event.lastInputEvent != null

                // Update the status overlay views whenever the navigator stops moving,
                // and also it is moving but at an (arbitrary) maximum refresh rate of 20 Hz.
                if (eventAction == NavigatorAction.STOPPED || elapsedTime > 50) {
                    // Get the current camera state to apply to the overlays
                    wwd.engine.cameraAsLookAt(lookAt)

                    // Update the overlays
                    updateOverlayContents(lookAt, event.camera!!)
                    updateOverlayColor(eventAction)
                    lastEventTime = currentTime
                }

                // Show the crosshairs while the user is gesturing and fade them out after the user stops
                if (receivedUserInput) showCrosshairs() else fadeCrosshairs()
            }
        }

        // Register the Navigator Listener with the activity's WorldWindow.
        wwd.navigatorEvents.addNavigatorListener(listener)
        return rootView
    }

    /**
     * Makes the crosshairs visible.
     */
    private fun showCrosshairs() {
        if (animatorSet.isStarted) animatorSet.cancel()
        crosshairs.alpha = 1.0f
        crosshairsActive = true
    }

    /**
     * Fades the crosshairs using animation.
     */
    private fun fadeCrosshairs() {
        if (crosshairsActive) {
            crosshairsActive = false
            if (!animatorSet.isStarted) animatorSet.start()
        }
    }

    /**
     * Displays camera state information in the status overlay views.
     *
     * @param lookAt Where the camera is looking
     * @param camera Where the camera is positioned
     */
    private fun updateOverlayContents(lookAt: LookAt, camera: Camera) {
        latView.text = formatLatitude(lookAt.position.latitude.inDegrees)
        lonView.text = formatLongitude(lookAt.position.longitude.inDegrees)
        altView.text = formatAltitude(camera.position.altitude)
    }

    /**
     * Brightens the colors of the overlay views when when user input occurs.
     *
     * @param eventAction The action associated with this navigator event
     */
    private fun updateOverlayColor(eventAction: NavigatorAction) {
        val color = if (eventAction == NavigatorAction.STOPPED) -0x5f000100 /*semi-transparent yellow*/ else Color.YELLOW
        latView.setTextColor(color)
        lonView.setTextColor(color)
        altView.setTextColor(color)
    }

    private fun formatLatitude(latitude: Double): String {
        val sign = sign(latitude)
        return "%6.3f°%s".format(latitude * sign, if (sign >= 0.0) "N" else "S")
    }

    private fun formatLongitude(longitude: Double): String {
        val sign = sign(longitude)
        return "%7.3f°%s".format(longitude * sign, if (sign >= 0.0) "E" else "W")
    }

    private fun formatAltitude(altitude: Double): String {
        return "Eye: %,.0f %s".format(
            if (altitude < 100000) altitude else altitude / 1000,
            if (altitude < 100000) "m" else "km"
        )
    }
}