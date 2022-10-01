package earth.worldwind

import android.view.MotionEvent

interface WorldWindowController {
    val wwd: WorldWindow
    fun onTouchEvent(event: MotionEvent): Boolean
}