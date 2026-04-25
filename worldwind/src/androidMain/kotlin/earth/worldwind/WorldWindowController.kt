package earth.worldwind

import android.view.MotionEvent

interface WorldWindowController {
    fun onTouchEvent(event: MotionEvent) = false

    /** Releases any platform resources held by the controller (timers, frame callbacks, etc.).
     *  Called when the host WorldWindow is detached/destroyed. */
    fun release() {}
}