package earth.worldwind.gesture

import android.view.MotionEvent
import earth.worldwind.gesture.GestureState.*
import kotlin.math.sqrt

/**
 * Gesture recognizer implementation that detects touch panning gestures.
 */
open class PanRecognizer: GestureRecognizer {
    var minNumberOfPointers = 1
    var maxNumberOfPointers = Int.MAX_VALUE
    var interpretDistance = 20f

    constructor()
    constructor(listener: GestureListener): super(listener)

    override fun actionMove(event: MotionEvent) {
        when (state) {
            POSSIBLE -> if (shouldInterpret())
                if (shouldRecognize(event)) transitionToState(event, BEGAN)
                else transitionToState(event, FAILED)
            BEGAN, CHANGED -> transitionToState(event, CHANGED)
            else -> {}
        }
    }

    override fun prepareToRecognize(event: MotionEvent) {
        // set translation to zero when the pan begins
        resetTranslation()
    }

    protected open fun shouldInterpret(): Boolean {
        val dx = translationX
        val dy = translationY
        val distance = sqrt(dx * dx + dy * dy)
        return distance > interpretDistance // interpret touches when the touch centroid moves far enough
    }

    protected open fun shouldRecognize(event: MotionEvent): Boolean {
        val count = event.pointerCount
        return count != 0 && count in minNumberOfPointers..maxNumberOfPointers
    }
}