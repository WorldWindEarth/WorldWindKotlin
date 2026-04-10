package earth.worldwind

import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

interface WorldWindowController {
    fun onMouseEvent(event: MouseEvent) = false
    fun onMouseWheelEvent(event: MouseWheelEvent) = false
}