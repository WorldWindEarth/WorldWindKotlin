package earth.worldwind.gesture

import earth.worldwind.geom.Angle
import earth.worldwind.gesture.GestureState.*
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.MouseEvent
import kotlin.math.abs
import kotlin.math.atan2

/**
 * A concrete gesture recognizer subclass that looks for two finger rotation gestures.
 */
open class RotationRecognizer(
    target: EventTarget, callback: ((GestureRecognizer)->Unit)? = null
) : GestureRecognizer(target, callback) {
    var referenceAngle = 0.0
    var interpretThreshold = 20
    var weight = 0.4
    val rotationWithOffset get() = rotation + offsetRotation
    protected var rotation = 0.0
    protected var offsetRotation = 0.0
    protected val rotationTouches = mutableListOf<TouchWrapper>()

    override fun reset() {
        super.reset()
        rotation = 0.0
        offsetRotation = 0.0
        referenceAngle = 0.0
        rotationTouches.clear()
    }

    override fun mouseDown(event: MouseEvent) {
        if (state == POSSIBLE) state = FAILED // touch gestures fail upon receiving a mouse event
    }

    override fun touchStart(touch: TouchWrapper) {
        if (rotationTouches.size < 2) {
            rotationTouches.add(touch)
            if (rotationTouches.size == 2) {
                referenceAngle = currentTouchAngle()
                offsetRotation += rotation
                rotation = 0.0
            }
        }
    }

    override fun touchMove(touch: TouchWrapper) {
        if (rotationTouches.size == 2) {
            if (state == POSSIBLE) {
                if (shouldRecognize()) state = BEGAN
            } else if (state == BEGAN || state == CHANGED) {
                val angle = currentTouchAngle()
                val newRotation = Angle.normalizeAngle180(angle - referenceAngle)
                val w = weight
                rotation = rotation * (1 - w) + newRotation * w
                state = CHANGED
            }
        }
    }

    override fun touchEnd(touch: TouchWrapper) {
        rotationTouches -= touch

        // Transition to the ended state if this was the last touch.
        if (touchCount == 0) // last touch ended
            if (state == POSSIBLE) state = FAILED else if (state == BEGAN || state == CHANGED) state = ENDED
    }

    override fun touchCancel(touch: TouchWrapper) {
        rotationTouches -= touch

        // Transition to the cancelled state if this was the last touch.
        if (touchCount == 0)
            if (state == POSSIBLE) state = FAILED else if (state == BEGAN || state == CHANGED) state = CANCELLED
    }

    override fun prepareToRecognize() {
        referenceAngle = currentTouchAngle()
        rotation = 0.0
    }

    protected open fun shouldRecognize(): Boolean {
        rotation = Angle.normalizeAngle180(currentTouchAngle() - referenceAngle)
        return abs(rotation) > interpretThreshold
    }

    protected open fun currentTouchAngle(): Double {
        val touch0 = rotationTouches[0]
        val touch1 = rotationTouches[1]
        val dx = touch0.clientX - touch1.clientX
        val dy = touch0.clientY - touch1.clientY
        return Angle.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
    }
}