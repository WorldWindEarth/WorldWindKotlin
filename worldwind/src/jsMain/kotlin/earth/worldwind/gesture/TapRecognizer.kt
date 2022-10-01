package earth.worldwind.gesture

import earth.worldwind.gesture.GestureState.*
import kotlinx.browser.window
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.MouseEvent
import kotlin.math.sqrt

/**
 * A concrete gesture recognizer subclass that looks for single or multiple taps.
 */
open class TapRecognizer(
    target: EventTarget, callback: ((GestureRecognizer)->Unit)? = null
) : GestureRecognizer(target, callback) {
    var numberOfTaps = 1
    var numberOfTouches = 1
    var maxTouchMovement = 20
    var maxTapDuration = 500
    var maxTapInterval = 400
    protected val taps = mutableListOf<TouchWrapper>()
    protected var timeout: Int? = null

    override fun reset() {
        super.reset()
        taps.clear()
        cancelFailAfterDelay()
    }

    override fun mouseDown(event: MouseEvent) {
        if (state != POSSIBLE) return
        state = FAILED // touch gestures fail upon receiving a mouse event
    }

    override fun touchStart(touch: TouchWrapper) {
        if (state != POSSIBLE) return

        if (touchCount > numberOfTouches) {
            state = FAILED
        } else if (touchCount == 1) { // first touch started
            taps.add(TouchWrapper(touchCount, clientX, clientY))
            failAfterDelay(maxTapDuration) // fail if the tap is down too long
        } else {
            val tap = taps[taps.size - 1]
            tap.identifier = touchCount // max number of simultaneous touches
            tap.clientX = clientX // touch centroid
            tap.clientY = clientY
        }
    }

    override fun touchMove(touch: TouchWrapper) {
        if (state != POSSIBLE) return
        val dx = translationX
        val dy = translationY
        val distance = sqrt(dx * dx + dy * dy)
        if (distance > maxTouchMovement * window.devicePixelRatio ) state = FAILED
    }

    override fun touchEnd(touch: TouchWrapper) {
        if (state != POSSIBLE) return
        if (touchCount != 0) return // wait until the last touch ends
        val tapCount = taps.size
        val tap = taps[tapCount - 1]
        if (tap.identifier != numberOfTouches) {
            state = FAILED // wrong number of touches
        } else if (tapCount == numberOfTaps) {
            clientX = taps[0].clientX
            clientY = taps[0].clientY
            state = RECOGNIZED
        } else failAfterDelay(maxTapInterval) // fail if the interval between taps is too long
    }

    override fun touchCancel(touch: TouchWrapper) {
        if (state != POSSIBLE) return
        state = FAILED
    }

    protected open fun failAfterDelay(delay: Int) {
        timeout?.let { window.clearTimeout(it) }
        timeout = window.setTimeout({
            timeout = null
            if (state == POSSIBLE) state = FAILED // fail if we haven't already reached a terminal state
        }, delay)
    }

    protected open fun cancelFailAfterDelay() = timeout?.let { window.clearTimeout(it) }.also { timeout = null }
}