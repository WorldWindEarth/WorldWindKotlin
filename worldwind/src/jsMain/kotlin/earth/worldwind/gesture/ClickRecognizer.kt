package earth.worldwind.gesture

import earth.worldwind.gesture.GestureState.*
import kotlinx.browser.window
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.MouseEvent
import kotlin.math.sqrt

/**
 * A concrete gesture recognizer subclass that looks for single or multiple mouse clicks.
 */
open class ClickRecognizer(
    target: EventTarget, callback: ((GestureRecognizer)->Unit)? = null
) : GestureRecognizer(target, callback) {
    var numberOfClicks = 1
    var button: Short = 0
    var maxMouseMovement = 5
    var maxClickDuration = 500
    var maxClickInterval = 400
    protected val clicks = mutableListOf<Click>()
    protected var timeout: Int? = null

    override fun reset() {
        super.reset()
        clicks.clear()
        cancelFailAfterDelay()
    }

    override fun mouseDown(event: MouseEvent) {
        if (state != POSSIBLE) return
        if (button != event.button) state = FAILED else {
            clicks.add(Click(clientX, clientY))
            failAfterDelay(maxClickDuration) // fail if the click is down too long
        }
    }

    override fun mouseMove(event: MouseEvent) {
        if (state != POSSIBLE) return
        val dx = translationX
        val dy = translationY
        val distance = sqrt(dx * dx + dy * dy)
        if (distance > maxMouseMovement * window.devicePixelRatio) state = FAILED
    }

    override fun mouseUp(event: MouseEvent) {
        if (state != POSSIBLE) return
        if (mouseButtonMask != 0) return // wait until the last button is up
        if (clicks.size == numberOfClicks) {
            clientX = clicks[0].clientX
            clientY = clicks[0].clientY
            state = RECOGNIZED
        } else failAfterDelay(maxClickInterval) // fail if the interval between clicks is too long
    }

    override fun touchStart(touch: TouchWrapper) {
        if (state != POSSIBLE) return
        state = FAILED // mouse gestures fail upon receiving a touch event
    }

    protected open fun failAfterDelay(delay: Int) {
        timeout?.let { window.clearTimeout(it) }
        timeout = window.setTimeout({
            timeout = null
            if (state == POSSIBLE) state = FAILED // fail if we haven't already reached a terminal state
        }, delay)
    }

    protected open fun cancelFailAfterDelay() { timeout?.let { window.clearTimeout(it) }.also { timeout = null } }
}