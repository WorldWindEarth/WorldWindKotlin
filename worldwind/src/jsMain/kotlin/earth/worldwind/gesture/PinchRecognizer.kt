package earth.worldwind.gesture

import earth.worldwind.gesture.GestureState.*
import kotlinx.browser.window
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.MouseEvent
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A concrete gesture recognizer subclass that looks for two finger pinch gestures.
 */
open class PinchRecognizer(
    target: EventTarget, callback: ((GestureRecognizer)->Unit)? = null
) : GestureRecognizer(target, callback) {
    var referenceDistance = 0.0
    var interpretThreshold = 20
    var weight = 0.4
    val scaleWithOffset get() = scale * offsetScale
    protected var scale = 1.0
    protected var offsetScale = 1.0
    protected val pinchTouches = mutableListOf<TouchWrapper>()

    override fun reset() {
        super.reset()
        scale = 1.0
        offsetScale = 1.0
        referenceDistance = 0.0
        pinchTouches.clear()
    }

    override fun mouseDown(event: MouseEvent) {
        if (state == POSSIBLE) state = FAILED // touch gestures fail upon receiving a mouse event
    }

    override fun touchStart(touch: TouchWrapper) {
        if (pinchTouches.size < 2) {
            pinchTouches.add(touch)
            if (pinchTouches.size == 2) {
                referenceDistance = currentPinchDistance()
                offsetScale *= scale
                scale = 1.0
            }
        }
    }

    override fun touchMove(touch: TouchWrapper) {
        if (pinchTouches.size == 2) {
            if (state == POSSIBLE) {
                if (shouldRecognize()) state = BEGAN
            } else if (state == BEGAN || state == CHANGED) {
                val distance = currentPinchDistance()
                val newScale = abs(distance / referenceDistance)
                val w = weight
                scale = scale * (1 - w) + newScale * w
                state = CHANGED
            }
        }
    }

    override fun touchEnd(touch: TouchWrapper) {
        pinchTouches -= touch

        // Transition to the ended state if this was the last touch.
        if (touchCount == 0) // last touch ended
            if (state == POSSIBLE) state = FAILED else if (state == BEGAN || state == CHANGED) state = ENDED
    }

    override fun touchCancel(touch: TouchWrapper) {
        pinchTouches -= touch

        // Transition to the cancelled state if this was the last touch.
        if (touchCount == 0) // last touch ended
            if (state == POSSIBLE) state = FAILED else if (state == BEGAN || state == CHANGED) state = CANCELLED
    }

    override fun prepareToRecognize() {
        referenceDistance = currentPinchDistance()
        scale = 1.0
    }

    protected open fun shouldRecognize() =
        abs(currentPinchDistance() - referenceDistance) > interpretThreshold * window.devicePixelRatio

    protected open fun currentPinchDistance(): Double {
        val touch0 = pinchTouches[0]
        val touch1 = pinchTouches[1]
        val dx = touch0.clientX - touch1.clientX
        val dy = touch0.clientY - touch1.clientY
        return sqrt((dx * dx + dy * dy).toDouble())
    }
}