package earth.worldwind

import android.view.MotionEvent

interface WorldWindowController {
    fun onTouchEvent(event: MotionEvent) = false
}