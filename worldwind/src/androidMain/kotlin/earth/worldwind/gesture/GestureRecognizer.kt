package earth.worldwind.gesture

import android.view.MotionEvent
import earth.worldwind.gesture.GestureState.*
import earth.worldwind.util.Logger.DEBUG
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.isLoggable
import earth.worldwind.util.Logger.logMessage

abstract class GestureRecognizer {
    var isEnabled = true
    var state = POSSIBLE
        private set
    var x = 0f
        protected set
    var y = 0f
        protected set
    var translationX = 0f
        protected set
    var translationY = 0f
        protected set
    private var startX = 0f
    private var startY = 0f
    private var centroidShiftX = 0f
    private var centroidShiftY = 0f
    private val centroidArray = FloatArray(2)
    private var stateSequence = 0L
    private val listeners = mutableListOf<GestureListener>()

    constructor()
    constructor(listener: GestureListener) { listeners.add(listener) }

    fun addListener(listener: GestureListener) { listeners.add(listener) }

    fun removeListener(listener: GestureListener) { listeners.remove(listener) }

    protected open fun notifyListeners(event: MotionEvent) {
        for (listener in listeners) listener.gestureStateChanged(event, this)
    }

    protected open fun reset() {
        state = POSSIBLE
        stateSequence = 0
        x = 0f
        y = 0f
        startX = 0f
        startY = 0f
        translationX = 0f
        translationY = 0f
        centroidShiftX = 0f
        centroidShiftY = 0f
    }

    protected open fun resetTranslation() {
        startX = x
        startY = y
        translationX = 0f
        translationY = 0f
        centroidShiftX = 0f
        centroidShiftY = 0f
    }

    protected open fun transitionToState(event: MotionEvent, newState: GestureState) {
        when (newState) {
            POSSIBLE, FAILED -> state = newState
            RECOGNIZED, BEGAN -> {
                state = newState
                stateSequence++
                prepareToRecognize(event)
                notifyListeners(event)
            }
            CHANGED, CANCELLED, ENDED -> {
                state = newState
                stateSequence++
                notifyListeners(event)
            }
        }
    }

    protected open fun prepareToRecognize(event: MotionEvent) {}

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        val currentStateSequence = stateSequence
        try {
            when (val action = event.actionMasked) {
                MotionEvent.ACTION_DOWN -> handleActionDown(event)
                MotionEvent.ACTION_POINTER_DOWN -> handleActionPointerDown(event)
                MotionEvent.ACTION_MOVE -> handleActionMove(event)
                MotionEvent.ACTION_CANCEL -> handleActionCancel(event)
                MotionEvent.ACTION_POINTER_UP -> handleActionPointerUp(event)
                MotionEvent.ACTION_UP -> handleActionUp(event)
                else -> if (isLoggable(DEBUG)) {
                    logMessage(
                        DEBUG, "GestureRecognizer", "onTouchEvent",
                        "Unrecognized event action '$action'"
                    )
                }
            }
        } catch (e: Exception) {
            logMessage(ERROR, "GestureRecognizer", "onTouchEvent", "Exception handling event", e)
        }
        return currentStateSequence != stateSequence // stateSequence changes if the event was recognized
    }

    protected open fun handleActionDown(event: MotionEvent) {
        val index = event.actionIndex
        x = event.getX(index)
        y = event.getY(index)
        startX = x
        startY = y
        translationX = 0f
        translationY = 0f
        centroidShiftX = 0f
        centroidShiftY = 0f
        actionDown(event)
    }

    protected open fun handleActionPointerDown(event: MotionEvent) {
        centroidChanged(event)
        actionDown(event)
    }

    protected open fun handleActionMove(event: MotionEvent) {
        eventCentroid(event, centroidArray)
        translationX = centroidArray[0] - startX + centroidShiftX
        translationY = centroidArray[1] - startY + centroidShiftY
        x = centroidArray[0]
        y = centroidArray[1]
        actionMove(event)
    }

    protected open fun handleActionCancel(event: MotionEvent) {
        actionCancel(event)
        when (state) {
            POSSIBLE -> transitionToState(event, FAILED)
            BEGAN, CHANGED -> transitionToState(event, CANCELLED)
            else -> {}
        }
        reset()
    }

    protected open fun handleActionPointerUp(event: MotionEvent) {
        centroidChanged(event)
        actionUp(event)
    }

    protected open fun handleActionUp(event: MotionEvent) {
        actionUp(event)
        when (state) {
            POSSIBLE -> transitionToState(event, FAILED)
            BEGAN, CHANGED -> transitionToState(event, ENDED)
            else -> {}
        }
        reset()
    }

    protected open fun centroidChanged(event: MotionEvent) {
        centroidShiftX += x
        centroidShiftY += y
        eventCentroid(event, centroidArray)
        x = centroidArray[0]
        y = centroidArray[1]
        centroidShiftX -= centroidArray[0]
        centroidShiftY -= centroidArray[1]
    }

    protected open fun eventCentroid(event: MotionEvent, result: FloatArray): FloatArray {
        val index = event.actionIndex
        val action = event.actionMasked
        var x = 0f
        var y = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            // suppress coordinates from pointers that are no longer down
            if (i == index && action == MotionEvent.ACTION_POINTER_UP) continue
            x += event.getX(i)
            y += event.getY(i)
            count++
        }
        result[0] = x / count
        result[1] = y / count
        return result
    }

    protected open fun actionDown(event: MotionEvent) {}
    protected open fun actionMove(event: MotionEvent) {}
    protected open fun actionCancel(event: MotionEvent) {}
    protected open fun actionUp(event: MotionEvent) {}
}