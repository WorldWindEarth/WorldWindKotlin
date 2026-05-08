package earth.worldwind.gesture

import earth.worldwind.gesture.GestureState.BEGAN
import earth.worldwind.gesture.GestureState.CHANGED
import earth.worldwind.gesture.GestureState.FAILED
import earth.worldwind.gesture.GestureState.POSSIBLE
import kotlin.math.sqrt

open class PanRecognizer : GestureRecognizer {
    var minNumberOfPointers = 1
    var maxNumberOfPointers = Int.MAX_VALUE
    var interpretDistance = 20f

    constructor()
    constructor(listener: GestureListener) : super(listener)

    override fun actionMove(event: TouchEvent) {
        when (state) {
            POSSIBLE -> if (shouldInterpret())
                if (shouldRecognize(event)) transitionToState(event, BEGAN)
                else transitionToState(event, FAILED)
            BEGAN, CHANGED -> transitionToState(event, CHANGED)
            else -> {}
        }
    }

    override fun prepareToRecognize(event: TouchEvent) { resetTranslation() }

    protected open fun shouldInterpret(): Boolean {
        val dx = translationX
        val dy = translationY
        val distance = sqrt(dx * dx + dy * dy)
        return distance > interpretDistance
    }

    protected open fun shouldRecognize(event: TouchEvent): Boolean {
        val count = event.pointerCount
        return count != 0 && count in minNumberOfPointers..maxNumberOfPointers
    }
}
