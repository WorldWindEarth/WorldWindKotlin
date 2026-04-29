package earth.worldwind

import earth.worldwind.geom.Angle
import earth.worldwind.geom.LookAt
import kotlinx.browser.window
import org.w3c.dom.events.EventListener
import org.w3c.dom.events.KeyboardEvent
import kotlin.math.min

/**
 * Keyboard navigation for the globe. Multiple navigation keys may be held at the same time —
 * pan in two directions, zoom and tilt can all be active simultaneously, and each key has its
 * own acceleration ramp. The canvas is made focusable in the constructor and gains focus on a
 * click so the controls work as soon as the user clicks the globe.
 */
open class KeyboardControls(protected val wwd: WorldWindow) {
    var isEnabled = true
    var zoomIncrement = 0.01f
    var panIncrement = 0.0000000005f
    var tiltIncrement = 0.5
    /** Upper bound on the per-key speed multiplier. */
    var maxSpeedMultiplier = 5.0
    /** Per-tick growth of the speed multiplier (50 ms tick × 0.05 ≈ 5x in 4 s). */
    var accelerationRate = 0.05

    protected val lookAt = LookAt()
    /** Currently pressed navigation keys mapped to their per-key tick counters. */
    protected val heldKeys = mutableMapOf<Int, Int>()
    /** Active setInterval handle, or -1 when no tick is scheduled. */
    protected var tickIntervalId = -1

    protected val handleKeyDown = EventListener { event ->
        if (!isEnabled || event !is KeyboardEvent) return@EventListener
        when (val code = event.keyCode) {
            in NAVIGATION_KEYS -> {
                if (code !in heldKeys) startHold(code)
                event.preventDefault()
            }
            78 -> { resetView(includeTilt = false); event.preventDefault() } // N
            82 -> { resetView(includeTilt = true); event.preventDefault() }  // R
        }
    }

    protected val handleKeyUp = EventListener { event ->
        if (event !is KeyboardEvent) return@EventListener
        if (heldKeys.remove(event.keyCode) != null) {
            if (heldKeys.isEmpty()) stopTicking()
            event.preventDefault()
        }
    }

    init {
        // The tabIndex must be set for the keyboard controls to work
        if (wwd.canvas.tabIndex < 0) wwd.canvas.tabIndex = 0
        wwd.addEventListener("keydown", handleKeyDown)
        wwd.addEventListener("keyup", handleKeyUp)
        // Click anywhere on the globe to give the canvas focus.
        wwd.addEventListener("click", EventListener { if (isEnabled) wwd.canvas.focus() })
    }

    protected open fun startHold(keyCode: Int) {
        if (heldKeys.isEmpty()) wwd.engine.cameraAsLookAt(lookAt)
        heldKeys[keyCode] = 0
        applyHeldKeys()
        if (tickIntervalId == -1) tickIntervalId = window.setInterval({ tick() }, TICK_INTERVAL_MS)
    }

    protected open fun tick() {
        if (heldKeys.isEmpty()) stopTicking() else applyHeldKeys()
    }

    private fun stopTicking() {
        if (tickIntervalId != -1) {
            window.clearInterval(tickIntervalId)
            tickIntervalId = -1
        }
    }

    /**
     * Advances each held key's tick counter, dispatches the corresponding action with its current
     * speed multiplier, and pushes the resulting [lookAt] to the camera in a single update.
     */
    private fun applyHeldKeys() {
        for (keyCode in heldKeys.keys.toList()) {
            val ticks = heldKeys[keyCode] ?: continue
            heldKeys[keyCode] = ticks + 1
            performAction(keyCode, min(maxSpeedMultiplier, 1.0 + ticks * accelerationRate))
        }
        wwd.engine.cameraFromLookAt(lookAt)
        wwd.requestRedraw()
    }

    /** Mutates [lookAt] for one tick of the action bound to [keyCode]. Override to remap keys. */
    protected open fun performAction(keyCode: Int, multiplier: Double) = when (keyCode) {
        187, 61 -> handleZoom(-1, multiplier)              // + key || +/= key
        189, 173 -> handleZoom(+1, multiplier)             // - key || _/- key
        33 -> handleTilt(-1, multiplier)                   // Page Up
        34 -> handleTilt(+1, multiplier)                   // Page Down
        37 -> handlePan(lookAt.heading - Angle.POS90, multiplier)  // Left
        38 -> handlePan(lookAt.heading, multiplier)                // Up
        39 -> handlePan(lookAt.heading + Angle.POS90, multiplier)  // Right
        40 -> handlePan(lookAt.heading + Angle.POS180, multiplier) // Down
        else -> Unit
    }

    /** [direction] = -1 zooms in, +1 zooms out. */
    protected open fun handleZoom(direction: Int, multiplier: Double) {
        lookAt.range *= 1 + direction * zoomIncrement * multiplier
    }

    /** [direction] = -1 tilts up, +1 tilts down. */
    protected open fun handleTilt(direction: Int, multiplier: Double) {
        lookAt.tilt = lookAt.tilt.plusDegrees(direction * tiltIncrement * multiplier)
    }

    protected open fun handlePan(heading: Angle, multiplier: Double) {
        val distance = panIncrement * lookAt.range * multiplier
        lookAt.position.greatCircleLocation(heading, distance, lookAt.position)
    }

    private fun resetView(includeTilt: Boolean) {
        wwd.engine.cameraAsLookAt(lookAt)
        lookAt.heading = Angle.ZERO
        if (includeTilt) lookAt.tilt = Angle.ZERO
        wwd.engine.cameraFromLookAt(lookAt)
        wwd.requestRedraw()
    }

    companion object {
        private const val TICK_INTERVAL_MS = 50
        // JS keyCodes for + (=) and - (_), Page Up/Down, Arrow keys.
        private val NAVIGATION_KEYS = setOf(187, 61, 189, 173, 33, 34, 37, 38, 39, 40)
    }
}
