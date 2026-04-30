package earth.worldwind

import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

interface WorldWindowController {
    fun onMouseEvent(event: MouseEvent) = false
    fun onMouseWheelEvent(event: MouseWheelEvent) = false

    /** Cancels any in-progress inertial pan ("fling"). The host calls this on every press before
     *  routing the event downstream, so the user can abort the animation by clicking even when a
     *  select/drag detector or app listener consumes the press. */
    fun cancelFling() {}

    /** Releases any platform resources held by the controller (timers, frame callbacks, etc.).
     *  Called when the host WorldWindow is disposed. */
    fun release() {}
}