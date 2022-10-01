package earth.worldwind.gesture

import earth.worldwind.gesture.GestureState.*
import kotlinx.browser.window
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.MouseEvent
import kotlin.math.sqrt

/**
 * A concrete gesture recognizer subclass that looks for touch panning gestures.
 */
open class PanRecognizer(
    target: EventTarget, callback: ((GestureRecognizer)->Unit)? = null
) : GestureRecognizer(target, callback) {
    var minNumberOfTouches = 1
    var maxNumberOfTouches = Int.MAX_VALUE
    var interpretDistance = 20

    override fun mouseDown(event: MouseEvent) {
        if (state == POSSIBLE) state = FAILED // touch gestures fail upon receiving a mouse event
    }

    override fun touchMove(touch: TouchWrapper) {
        if (state == POSSIBLE) {
            if (shouldInterpret()) state = if (shouldRecognize()) BEGAN else FAILED
        } else if (state == BEGAN || state == CHANGED) state = CHANGED
    }

    override fun touchEnd(touch: TouchWrapper) {
        if (touchCount == 0) { // last touch ended
            if (state == POSSIBLE) state = FAILED else if (state == BEGAN || state == CHANGED) state = ENDED
        }
    }

    override fun touchCancel(touch: TouchWrapper) {
        if (touchCount == 0) { // last touch cancelled
            if (state == POSSIBLE) state = FAILED else if (state == BEGAN || state == CHANGED) state = CANCELLED
        }
    }

    override fun prepareToRecognize() {
        // set translation to zero when the pan begins
        resetTranslation()
    }

    protected open fun shouldInterpret(): Boolean {
        val dx = translationX
        val dy = translationY
        val distance = sqrt(dx * dx + dy * dy)
        return distance > interpretDistance * window.devicePixelRatio // interpret touches when the touch centroid moves far enough
    }

    protected open fun shouldRecognize() = touchCount in minNumberOfTouches..maxNumberOfTouches
}