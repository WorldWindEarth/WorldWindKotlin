package earth.worldwind.gesture

import kotlinx.browser.window
import org.w3c.dom.events.EventTarget
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A concrete gesture recognizer subclass that looks for two finger tilt gestures.
 */
open class TiltRecognizer(
    target: EventTarget, callback: ((GestureRecognizer)->Unit)? = null
) : PanRecognizer(target, callback) {
    var maxTouchDistance = 300
    var maxTouchDivergence = 50

    companion object {
        const val LEFT = 1 shl 0
        const val RIGHT = 1 shl 1
        const val UP = 1 shl 2
        const val DOWN = 1 shl 3
    }

    override fun shouldInterpret(): Boolean {
        for (i in 0 until  touchCount) {
            val touch = touch(i)
            val dx = touch.translationX
            val dy = touch.translationY
            val distance = sqrt((dx * dx + dy * dy).toDouble())
            if (distance > interpretDistance) return true // interpret touches when any touch moves far enough
        }
        return false
    }

    override fun shouldRecognize(): Boolean {
        if (touchCount < 2) return false
        val touch0 = touch(0)
        val touch1 = touch(1)
        val dx = touch0.clientX - touch1.clientX
        val dy = touch0.clientY - touch1.clientY
        val distance = sqrt((dx * dx + dy * dy).toDouble())
        if (distance > maxTouchDistance * window.devicePixelRatio) return false // touches must be close together
        val tx = touch0.translationX - touch1.translationX
        val ty = touch0.translationY - touch1.translationY
        val divergence = sqrt((tx * tx + ty * ty).toDouble())
        if (divergence > maxTouchDivergence * window.devicePixelRatio) return false // touches must be moving in a mostly parallel direction

        val verticalMask = UP or DOWN
        val dirMask0 = touchDirection(touch0) and verticalMask
        val dirMask1 = touchDirection(touch1) and verticalMask
        return (dirMask0 and dirMask1) != 0 // touches must move in the same vertical direction
    }

    protected open fun touchDirection(touch: TouchWrapper): Int {
        val dx = touch.translationX
        val dy = touch.translationY
        var dirMask = 0
        if (abs(dx) > abs(dy)) {
            dirMask = dirMask or if (dx < 0) LEFT else 0
            dirMask = dirMask or if (dx > 0) RIGHT else 0
        } else {
            dirMask = dirMask or if (dy < 0) UP else 0
            dirMask = dirMask or if (dy > 0) DOWN else 0
        }
        return dirMask
    }
}