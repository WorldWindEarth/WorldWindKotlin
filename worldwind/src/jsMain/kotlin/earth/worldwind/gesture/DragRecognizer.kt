package earth.worldwind.gesture

import earth.worldwind.gesture.GestureState.*
import kotlinx.browser.window
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.MouseEvent
import kotlin.math.sqrt

/**
 * A concrete gesture recognizer subclass that looks for mouse drag gestures.
 */
open class DragRecognizer(
    target: EventTarget, callback: ((GestureRecognizer)->Unit)? = null
) : GestureRecognizer(target, callback) {
    var button: Short = 0
    var interpretDistance = 5

    override fun mouseMove(event: MouseEvent) {
        if (state == POSSIBLE) {
            if (shouldInterpret()) {
                state = if (shouldRecognize()) {
                    resetTranslation() // set translation to zero when the drag begins
                    BEGAN
                } else {
                    FAILED
                }
            }
        } else if (state == BEGAN || state == CHANGED) state = CHANGED
    }

    override fun mouseUp(event: MouseEvent) {
        if (mouseButtonMask == 0) { // last button up
            if (state == POSSIBLE) state = FAILED else if (state == BEGAN || state == CHANGED) state = ENDED
        }
    }

    override fun touchStart(touch: TouchWrapper) {
        if (state == POSSIBLE) state = FAILED // mouse gestures fail upon receiving a touch event
    }

    protected open fun shouldInterpret(): Boolean {
        val dx = translationX
        val dy = translationY
        val distance = sqrt(dx * dx + dy * dy)
        return distance > interpretDistance * window.devicePixelRatio // interpret mouse movement when the cursor moves far enough
    }

    /**
     * @return true when the specified button is the only button down
     */
    protected open fun shouldRecognize() = 1 shl button.toInt() == mouseButtonMask
}