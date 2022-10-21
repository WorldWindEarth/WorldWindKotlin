package earth.worldwind

import android.view.MotionEvent
import earth.worldwind.gesture.SelectDragCallback

interface WorldWindowController {
    fun onTouchEvent(event: MotionEvent) = false
    fun setSelectDragCallback(callback: SelectDragCallback) {
        error("Select and drag is not supported by this controller!")
    }
}