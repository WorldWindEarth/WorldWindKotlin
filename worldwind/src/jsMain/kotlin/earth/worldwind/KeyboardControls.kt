package earth.worldwind

import earth.worldwind.geom.Angle
import earth.worldwind.geom.LookAt
import kotlinx.browser.window
import org.w3c.dom.events.EventListener
import org.w3c.dom.events.KeyboardEvent

/**
 * The KeyboardControls module provides keyboard controls for the globe.
 * Note: the canvas must be focusable this can be accomplished by establishing the "tabindex"
 * on the canvas element.
 */
open class KeyboardControls(
    /**
     * The WorldWindow associated with these controls.
     */
    protected val wwd: WorldWindow
) {
    /**
     * Enable/disable keyboard events processing
     */
    var isEnabled = true
    /**
     * The incremental amount to increase or decrease the eye distance (for zoom) each cycle.
     */
    var zoomIncrement = 0.01f
    /**
     * The scale factor governing the pan speed. Increased values cause faster panning.
     */
    var panIncrement = 0.0000000005f
    /**
     * The incremental amount to increase or decrease the tilt (in degrees) each cycle.
     */
    var tiltIncrement = 0.5
    /**
     * The current state of the viewing parameters during an operation as a look at view.
     */
    protected val lookAt = LookAt()
    /**
     * Is key down?
     */
    protected var isKeyDown = false
    /**
     * Controls the globe with the keyboard.
     */
    protected val handleKeyDown = EventListener { event ->
        if (!isEnabled || event !is KeyboardEvent) return@EventListener

        isKeyDown = true
        wwd.engine.cameraAsLookAt(lookAt)

        // TODO: find a way to make this code portable for different keyboard layouts
        when (event.keyCode) {
            187, 61 -> { // + key || +/= key
                handleZoom("zoomIn")
                event.preventDefault()
            }
            189, 173 -> { // - key || _/- key
                handleZoom("zoomOut")
                event.preventDefault()
            }
            33 -> { // Page Up
                handleTilt("tiltUp")
                event.preventDefault()
            }
            34 -> { // Page down
                handleTilt("tiltDown")
                event.preventDefault()
            }
            37 -> { // Left arrow
                handlePan("panLeft")
                event.preventDefault()
            }
            38 -> { // Up arrow
                handlePan("panUp")
                event.preventDefault()
            }
            39 -> { // Right arrow
                handlePan("panRight")
                event.preventDefault()
            }
            40 -> { // Down arrow
                handlePan("panDown")
                event.preventDefault()
            }
            78 -> { // N key
                resetHeading()
                event.preventDefault()
            }
            82 -> { // R key
                resetHeadingAndTilt()
                event.preventDefault()
            }
        }
    }
    /**
     * On keyboard event finished.
     */
    protected val handleKeyUp = EventListener { event->
        if (isKeyDown) {
            isKeyDown = false
            event.preventDefault()
        }
    }

    init {
        // The tabIndex must be set for the keyboard controls to work
        val tabIndex = wwd.canvas.tabIndex
        if (tabIndex < 0) wwd.canvas.tabIndex = 0
        // Add keyboard listeners
        wwd.addEventListener("keydown", handleKeyDown)
        wwd.addEventListener("keyup", handleKeyUp)
        // Ensure keyboard controls are operational by setting the focus to the canvas
        wwd.addEventListener("click", EventListener { if (isEnabled) wwd.canvas.focus() })
    }

    /**
     * Reset the view to North up.
     */
    protected open fun resetHeading() {
        lookAt.heading = Angle.ZERO
        wwd.engine.cameraFromLookAt(lookAt)
        wwd.requestRedraw()
    }

    /**
     * Reset the view to North up and nadir.
     */
    protected open fun resetHeadingAndTilt() {
        lookAt.heading = Angle.ZERO
        lookAt.tilt = Angle.ZERO
        wwd.engine.cameraFromLookAt(lookAt)
        wwd.requestRedraw()
    }

    /**
     * This function is called by the timer to perform the Pan operation.
     */
    protected open fun handlePan(operation: String) {
        if (isKeyDown) {
            var heading = lookAt.heading
            val distance = panIncrement * lookAt.range
            when (operation) {
                "panDown" -> heading -= Angle.POS180
                "panLeft" -> heading -= Angle.POS90
                "panRight" -> heading += Angle.POS90
            }
            lookAt.position.greatCircleLocation(heading, distance, lookAt.position)
            wwd.engine.cameraFromLookAt(lookAt)
            wwd.requestRedraw()
            window.setTimeout(::handlePan, 50, operation)
        }
    }

    /**
     * This function is called by the timer to perform the Range operation.
     */
    protected open fun handleZoom(operation: String) {
        if (isKeyDown) {
            if (operation == "zoomIn") lookAt.range *= (1 - zoomIncrement)
            else if (operation == "zoomOut") lookAt.range *= (1 + zoomIncrement)
            lookAt.range = lookAt.range.coerceIn(10.0, wwd.engine.distanceToViewGlobeExtents * 2)
            wwd.engine.cameraFromLookAt(lookAt)
            wwd.requestRedraw()
            window.setTimeout(::handleZoom, 50, operation)
        }
    }

    /**
     * This function is called by the timer to perform the Tilt operation.
     */
    protected open fun handleTilt(operation: String) {
        if (isKeyDown) {
            if (operation == "tiltUp") lookAt.tilt = lookAt.tilt.minusDegrees(tiltIncrement)
            else if (operation == "tiltDown") lookAt.tilt = lookAt.tilt.plusDegrees(tiltIncrement)
            lookAt.tilt = lookAt.tilt.coerceIn(Angle.ZERO, Angle.POS90)
            wwd.engine.cameraFromLookAt(lookAt)
            wwd.requestRedraw()
            window.setTimeout(::handleTilt, 50, operation)
        }
    }
}