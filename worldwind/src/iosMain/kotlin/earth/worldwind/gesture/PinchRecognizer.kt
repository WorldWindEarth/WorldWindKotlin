package earth.worldwind.gesture

import earth.worldwind.gesture.GestureState.BEGAN
import earth.worldwind.gesture.GestureState.CHANGED
import earth.worldwind.gesture.GestureState.POSSIBLE
import kotlin.math.abs
import kotlin.math.sqrt

open class PinchRecognizer : GestureRecognizer {
    protected var scale = 1f
    protected var scaleOffset = 1f
    protected var referenceDistance = 0f
    protected val pointerIds = IntArray(2)
    protected var pointerIdCount = 0
    var interpretDistance = 20f
    val scaleWithOffset get() = scale * scaleOffset

    constructor()
    constructor(listener: GestureListener) : super(listener)

    override fun reset() {
        super.reset()
        scale = 1f
        scaleOffset = 1f
        referenceDistance = 0f
        pointerIdCount = 0
    }

    override fun actionDown(event: TouchEvent) {
        val pointerId = event.getPointerId(event.actionIndex)
        if (pointerIdCount < 2) {
            pointerIds[pointerIdCount++] = pointerId
            if (pointerIdCount == 2) {
                referenceDistance = currentPinchDistance(event)
                scaleOffset *= scale
                scale = 1f
            }
        }
    }

    override fun actionMove(event: TouchEvent) {
        if (pointerIdCount == 2) {
            when (state) {
                POSSIBLE -> if (shouldRecognize(event)) transitionToState(event, BEGAN)
                BEGAN, CHANGED -> {
                    val distance = currentPinchDistance(event)
                    scale = abs(distance / referenceDistance)
                    transitionToState(event, CHANGED)
                }
                else -> {}
            }
        }
    }

    override fun actionUp(event: TouchEvent) {
        val pointerId = event.getPointerId(event.actionIndex)
        if (pointerIds[0] == pointerId) {
            pointerIds[0] = pointerIds[1]
            pointerIdCount--
        } else if (pointerIds[1] == pointerId) {
            pointerIdCount--
        }
    }

    override fun prepareToRecognize(event: TouchEvent) {
        referenceDistance = currentPinchDistance(event)
        scale = 1f
    }

    protected open fun shouldRecognize(event: TouchEvent): Boolean {
        if (event.pointerCount != 2) return false
        val distance = currentPinchDistance(event)
        return abs(distance - referenceDistance) > interpretDistance
    }

    protected open fun currentPinchDistance(event: TouchEvent): Float {
        val index0 = event.findPointerIndex(pointerIds[0])
        val index1 = event.findPointerIndex(pointerIds[1])
        if (index0 < 0 || index1 < 0) return referenceDistance
        val dx = event.getX(index0) - event.getX(index1)
        val dy = event.getY(index0) - event.getY(index1)
        return sqrt(dx * dx + dy * dy)
    }
}
