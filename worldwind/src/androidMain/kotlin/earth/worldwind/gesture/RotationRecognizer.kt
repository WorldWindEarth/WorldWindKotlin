package earth.worldwind.gesture

import android.view.MotionEvent
import earth.worldwind.geom.Angle.Companion.normalizeAngle180
import earth.worldwind.geom.Angle.Companion.toDegrees
import earth.worldwind.gesture.GestureState.*
import kotlin.math.abs
import kotlin.math.atan2

open class RotationRecognizer: GestureRecognizer {
    var rotation = 0f
    protected var rotationOffset = 0f
    protected var referenceAngle = 0f
    protected val pointerIds = IntArray(2)
    protected var pointerIdCount = 0
    var interpretAngle = 20f
    val rotationWithOffset get() = normalizeAngle180((rotation + rotationOffset).toDouble()).toFloat()

    constructor()
    constructor(listener: GestureListener) : super(listener)

    override fun reset() {
        super.reset()
        rotation = 0f
        rotationOffset = 0f
        referenceAngle = 0f
        pointerIdCount = 0
    }

    override fun actionDown(event: MotionEvent) {
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)
        if (pointerIdCount < 2) {
            pointerIds[pointerIdCount++] = pointerId // add it to the pointer ID array
            if (pointerIdCount == 2) {
                referenceAngle = currentTouchAngle(event)
                rotationOffset += rotation
                rotation = 0f
            }
        }
    }

    override fun actionMove(event: MotionEvent) {
        if (pointerIdCount == 2) {
            when (state) {
                POSSIBLE -> if (shouldRecognize(event)) transitionToState(event, BEGAN)
                BEGAN, CHANGED -> {
                    val angle = currentTouchAngle(event)
                    rotation = normalizeAngle180((angle - referenceAngle).toDouble()).toFloat()
                    transitionToState(event, CHANGED)
                }
                else -> {}
            }
        }
    }

    override fun actionUp(event: MotionEvent) {
        val pointerId = event.getPointerId(event.actionIndex)
        if (pointerIds[0] == pointerId) {
            // remove the first pointer ID
            pointerIds[0] = pointerIds[1]
            pointerIdCount--
        } else if (pointerIds[1] == pointerId) {
            // remove the second pointer ID
            pointerIdCount--
        }
    }

    override fun prepareToRecognize(event: MotionEvent) {
        referenceAngle = currentTouchAngle(event)
        rotation = 0f
    }

    protected open fun shouldRecognize(event: MotionEvent): Boolean {
        if (event.pointerCount != 2) {
            return false // require exactly two pointers to recognize the gesture
        }
        val angle = currentTouchAngle(event)
        val rotation = normalizeAngle180((angle - referenceAngle).toDouble()).toFloat()
        return abs(rotation) > interpretAngle
    }

    protected open fun currentTouchAngle(event: MotionEvent): Float {
        val index0 = event.findPointerIndex(pointerIds[0])
        val index1 = event.findPointerIndex(pointerIds[1])
        val dx = event.getX(index0) - event.getX(index1)
        val dy = event.getY(index0) - event.getY(index1)
        val rad = atan2(dy, dx)
        return toDegrees(rad.toDouble()).toFloat()
    }
}