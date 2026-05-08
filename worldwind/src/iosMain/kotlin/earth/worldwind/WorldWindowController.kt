package earth.worldwind

import earth.worldwind.gesture.TouchEvent

interface WorldWindowController {
    fun onTouchEvent(event: TouchEvent): Boolean = false

    /** Cancels any in-progress inertial pan ("fling"). The host calls this on every touch
     *  start before routing the event downstream. */
    fun cancelFling() {}

    /** Releases any platform resources held by the controller. Called when the host
     *  WorldWindow is detached/destroyed. */
    fun release() {}
}
