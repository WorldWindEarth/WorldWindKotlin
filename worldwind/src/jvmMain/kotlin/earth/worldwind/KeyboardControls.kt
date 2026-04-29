package earth.worldwind

import earth.worldwind.geom.Angle
import earth.worldwind.geom.LookAt
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Timer
import kotlin.math.min

/**
 * Keyboard navigation for the globe. Multiple navigation keys may be held at the same time —
 * pan in two directions, zoom and tilt can all be active simultaneously, and each key has its
 * own acceleration ramp. The constructor makes the host component focusable and requests focus
 * on a mouse press so the controls work as soon as the user clicks the globe.
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

    protected val repeatTimer: Timer = Timer(TICK_INTERVAL_MS) { _ -> tick() }
        .apply { isRepeats = true }

    protected val keyListener = object : KeyAdapter() {
        override fun keyPressed(event: KeyEvent) {
            if (!isEnabled) return
            when (val code = event.keyCode) {
                in NAVIGATION_KEYS -> {
                    if (code !in heldKeys) startHold(code)
                    event.consume()
                }
                KeyEvent.VK_N -> { resetView(includeTilt = false); event.consume() }
                KeyEvent.VK_R -> { resetView(includeTilt = true); event.consume() }
            }
        }

        override fun keyReleased(event: KeyEvent) {
            if (!isEnabled) return
            if (heldKeys.remove(event.keyCode) != null) {
                if (heldKeys.isEmpty()) repeatTimer.stop()
                event.consume()
            }
        }
    }

    protected val focusListener = object : MouseAdapter() {
        override fun mousePressed(event: MouseEvent) {
            if (isEnabled) wwd.glPanel.requestFocusInWindow()
        }
    }

    init {
        wwd.glPanel.isFocusable = true
        wwd.glPanel.addKeyListener(keyListener)
        wwd.glPanel.addMouseListener(focusListener)
    }

    /**
     * Detach all listeners installed by this controller. Call when the WorldWindow is no longer
     * in use to break references that would otherwise keep it (and the Swing Timer) retained.
     */
    open fun release() {
        repeatTimer.stop()
        heldKeys.clear()
        wwd.glPanel.removeKeyListener(keyListener)
        wwd.glPanel.removeMouseListener(focusListener)
    }

    protected open fun startHold(keyCode: Int) {
        if (heldKeys.isEmpty()) wwd.engine.cameraAsLookAt(lookAt)
        heldKeys[keyCode] = 0
        applyHeldKeys()
        if (!repeatTimer.isRunning) repeatTimer.start()
    }

    protected open fun tick() {
        if (heldKeys.isEmpty()) repeatTimer.stop() else applyHeldKeys()
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
        KeyEvent.VK_PLUS, KeyEvent.VK_EQUALS, KeyEvent.VK_ADD -> handleZoom(-1, multiplier)
        KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> handleZoom(+1, multiplier)
        KeyEvent.VK_PAGE_UP -> handleTilt(-1, multiplier)
        KeyEvent.VK_PAGE_DOWN -> handleTilt(+1, multiplier)
        KeyEvent.VK_LEFT -> handlePan(lookAt.heading - Angle.POS90, multiplier)
        KeyEvent.VK_UP -> handlePan(lookAt.heading, multiplier)
        KeyEvent.VK_RIGHT -> handlePan(lookAt.heading + Angle.POS90, multiplier)
        KeyEvent.VK_DOWN -> handlePan(lookAt.heading + Angle.POS180, multiplier)
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
        private val NAVIGATION_KEYS = setOf(
            KeyEvent.VK_PLUS, KeyEvent.VK_EQUALS, KeyEvent.VK_ADD,
            KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT,
            KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN,
            KeyEvent.VK_LEFT, KeyEvent.VK_UP, KeyEvent.VK_RIGHT, KeyEvent.VK_DOWN,
        )
    }
}
