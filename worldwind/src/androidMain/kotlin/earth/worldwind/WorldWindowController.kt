package earth.worldwind

import android.view.MotionEvent

interface WorldWindowController {
    fun onTouchEvent(event: MotionEvent) = false

    /** Cancels any in-progress inertial pan ("fling"). The host calls this on every press before
     *  routing the event downstream, so the user can abort the animation by touching the screen
     *  even when a select/drag detector, view-controls overlay, or app listener consumes it. */
    fun cancelFling() {}

    /** Releases any platform resources held by the controller (timers, frame callbacks, etc.).
     *  Called when the host WorldWindow is detached/destroyed. */
    fun release() {}
}