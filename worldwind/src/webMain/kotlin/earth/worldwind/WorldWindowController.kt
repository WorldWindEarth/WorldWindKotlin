package earth.worldwind

import earth.worldwind.gesture.GestureListener
import org.w3c.dom.events.Event

/**
 * This class provides a base window controller with required properties and methods which subclasses may
 * inherit from to create custom window controllers for controlling the globe via user interaction.
 *
 * Event listeners are plain `(Event) -> Unit` functions: Kotlin/Wasm cannot implement the external
 * [org.w3c.dom.events.EventListener], so the whole input pipeline uses function values instead.
 */
abstract class WorldWindowController(
    /**
     * The WorldWindow associated with this controller.
     */
    protected val wwd: WorldWindow
): AbstractWorldWindowController(), GestureListener {
    override val engine get() = wwd.engine
    protected val gestureEventListeners = mutableListOf<(Event) -> Unit>()

    open fun handleEvent(event: Event) {
        for (i in gestureEventListeners.indices) {
            gestureEventListeners[i](event)
            if (event.defaultPrevented) break
        }
    }

    /**
     * Registers a gesture event listener on this controller. Registering event listeners using this function
     * enables applications to prevent the controller's default behavior.
     *
     * When an event occurs, application event listeners are called before WorldWindowController event listeners.
     *
     * @param listener The function to call when the event occurs.
     */
    protected open fun addGestureListener(listener: (Event) -> Unit) { gestureEventListeners += listener }

    /**
     * Removes a gesture event listener from this controller. The listener must be the same object passed to
     * addGestureListener. Calling removeGestureListener with arguments that do not identify a currently registered
     * listener has no effect.
     *
     * @param listener The listener to remove. Must be the same object passed to addGestureListener.
     */
    protected open fun removeGestureListener(listener: (Event) -> Unit) { gestureEventListeners -= listener }
}