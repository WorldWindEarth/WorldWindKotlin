package earth.worldwind

import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

interface WorldWindowController {
    fun onMouseEvent(event: MouseEvent) = false
    fun onMouseWheelEvent(event: MouseWheelEvent) = false

    /** Releases any platform resources held by the controller (timers, frame callbacks, etc.).
     *  Called when the host WorldWindow is disposed. */
    fun release() {}
}