package earth.worldwind.gesture

import earth.worldwind.gesture.GestureState.*
import earth.worldwind.util.Logger
import org.w3c.dom.Touch
import org.w3c.dom.TouchEvent
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventListener
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.pointerevents.PointerEvent

/**
 * Gesture recognizers translate user input event streams into higher level actions. A gesture
 * recognizer is associated with an event target, which dispatches mouse and keyboard events to the gesture
 * recognizer. When a gesture recognizer has received enough information from the event stream to interpret the
 * action, it calls its callback functions. Callback functions may be specified at construction or added to the
 * [gestureCallbacks] list after construction.
 */
abstract class GestureRecognizer(
    /**
     * Indicates the document element this gesture recognizer observes for mouse and touch events.
     */
    val target: EventTarget,
    /**
     * An optional function to call when this gesture is recognized. If non-null, the
     * function is called when this gesture is recognized, and is passed a single argument: this gesture recognizer,
     * e.g., <code>gestureCallback(recognizer)</code>.
     */
    callback: ((GestureRecognizer)->Unit)?
): EventListener {
    /**
     * Indicates whether this gesture recognizer is enabled. When false, this gesture recognizer will ignore any events
     * dispatched by its target.
     */
    var isEnabled = true
    /**
     * Indicates this gesture's current state. Possible values are [POSSIBLE], [FAILED], [RECOGNIZED], [BEGAN],
     * [CHANGED], [CANCELLED] and [ENDED].
     */
    var state = POSSIBLE
        set(newState) {
            nextState = null // clear any pending state transition
            when (newState) {
                POSSIBLE -> field = newState
                FAILED -> {
                    field = newState
                    updateRecognizersWaitingForFailure()
                    resetIfEventsEnded()
                }
                RECOGNIZED -> {
                    if (tryToRecognize(newState)) { // may prevent the transition to Recognized
                        field = newState
                        prepareToRecognize()
                        notifyListeners()
                        callGestureCallbacks()
                        resetIfEventsEnded()
                    }
                }
                BEGAN -> {
                    if (tryToRecognize(newState)) { // may prevent the transition to Began
                        field = newState
                        prepareToRecognize()
                        notifyListeners()
                        callGestureCallbacks()
                    }
                }
                CHANGED -> {
                    field = newState
                    notifyListeners()
                    callGestureCallbacks()
                }
                CANCELLED -> {
                    field = newState
                    notifyListeners()
                    callGestureCallbacks()
                    resetIfEventsEnded()
                }
                ENDED -> {
                    field = newState
                    notifyListeners()
                    callGestureCallbacks()
                    resetIfEventsEnded()
                }
            }
        }
    /**
     * Indicates the X coordinate of this gesture.
     */
    var clientX = 0
    /**
     * Returns the Y coordinate of this gesture.
     */
    var clientY = 0
    /**
     * Indicates this gesture's translation along the X axis since the gesture started.
     */
    var translationX = 0.0
        private set
    /**
     * Indicates this gesture's translation along the Y axis since the gesture started.
     */
    var translationY = 0.0
        private set
    /**
     * Indicates the currently pressed mouse buttons as a bitmask. A value of 0 indicates that no buttons are
     * pressed. A nonzero value indicates that one or more buttons are pressed as follows: bit 1 indicates the
     * primary button, bit 2 indicates the auxiliary button, bit 3 indicates the secondary button.
     */
    var mouseButtonMask = 0
        private set
    /**
     * Indicates the number of active touches.
     */
    val touchCount get() = touches.size
    /**
     * The list of functions to call when this gesture is recognized. The functions have a single argument:
     * this gesture recognizer, e.g., <code>gestureCallback(recognizer)</code>. Applications may
     * add functions to this array or remove them.
     */
    val gestureCallbacks = mutableListOf<(GestureRecognizer)->Unit>()
    private var nextState: GestureState? = null
    private var clientStartX = 0
    private var clientStartY = 0
    private var translationWeight = 0.4
    private val touches = mutableListOf<TouchWrapper>()
    private var touchCentroidShiftX = 0
    private var touchCentroidShiftY = 0
    private val canRecognizeWith = mutableSetOf<GestureRecognizer>()
    private val requiresFailureOf = mutableSetOf<GestureRecognizer>()
    private val requiredToFailBy = mutableSetOf<GestureRecognizer>()
    private val listenerList = mutableListOf<GestureListener>()

    companion object {
        val allRecognizers = mutableListOf<GestureRecognizer>()
    }

    init {
        // Add the optional gesture callback.
        callback?.let { gestureCallbacks += it }

        // Add this recognizer to the list of all recognizers.
        allRecognizers += this
    }

    /**
     * Registers a gesture state listener on this GestureRecognizer. Registering state listeners using this function
     * enables applications to receive notifications of gesture recognition.
     *
     * @param listener The function to call when the event occurs.
     */
    fun addListener(listener: GestureListener) { listenerList += listener }

    /**
     * Removes a gesture state listener from this GestureRecognizer. The listener must be the same object passed to
     * addListener. Calling removeListener with arguments that do not identify a currently registered
     * listener has no effect.
     *
     * @param listener The listener to remove. Must be the same object passed to addListener.
     */
    fun removeListener(listener: GestureListener) { listenerList -= listener }

    fun touch(index: Int): TouchWrapper {
        require(index in touches.indices) {
            Logger.logMessage(Logger.ERROR, "GestureRecognizer", "touch", "indexOutOfRange")
        }
        return touches[index]
    }

    fun recognizeSimultaneouslyWith(recognizer: GestureRecognizer) {
        canRecognizeWith.add(recognizer)
        recognizer.canRecognizeWith.add(this)
    }

    fun canRecognizeSimultaneouslyWith(recognizer: GestureRecognizer) = canRecognizeWith.contains(recognizer)

    fun requireRecognizerToFail(recognizer: GestureRecognizer) {
        requiresFailureOf.add(recognizer)
        recognizer.requiredToFailBy.add(this)
    }

    fun requiresRecognizerToFail(recognizer: GestureRecognizer) = requiresFailureOf.contains(recognizer)

    fun requiredToFailByRecognizer(recognizer: GestureRecognizer) = requiredToFailBy.contains(recognizer)

    open fun reset() {
        state = POSSIBLE
        nextState = null
        clientX = 0
        clientY = 0
        clientStartX = 0
        clientStartY = 0
        translationX = 0.0
        translationY = 0.0
        mouseButtonMask = 0
        touches.clear()
        touchCentroidShiftX = 0
        touchCentroidShiftY = 0
    }

    protected open fun resetTranslation() {
        clientStartX = clientX
        clientStartY = clientY
        translationX = 0.0
        translationY = 0.0
        touchCentroidShiftX = 0
        touchCentroidShiftY = 0
    }

    protected open fun prepareToRecognize() {}

    protected open fun mouseDown(event: MouseEvent) {}
    protected open fun mouseMove(event: MouseEvent) {}
    protected open fun mouseUp(event: MouseEvent) {}

    protected open fun touchStart(touch: TouchWrapper) {}
    protected open fun touchMove(touch: TouchWrapper) {}
    protected open fun touchCancel(touch: TouchWrapper) {}
    protected open fun touchEnd(touch: TouchWrapper) {}
    
    protected open fun updateRecognizersWaitingForFailure() {
        // Transition gestures that are waiting for this gesture to transition to Failed.
        requiredToFailBy.forEach { r -> r.nextState?.let { r.state = it } }
    }

    protected open fun tryToRecognize(newState: GestureState): Boolean {
        // Transition to Failed if another gesture can prevent this gesture from recognizing.
        if (allRecognizers.any { r -> canBePreventedByRecognizer(r) }) {
            state = FAILED
            return false
        }

        // Delay the transition to Recognized/Began if this gesture is waiting for a gesture in the Possible state.
        if (allRecognizers.any { r -> isWaitingForRecognizerToFail(r) }) {
            nextState = newState
            return false
        }

        // Transition to Failed state all other gestures that can be prevented from recognizing by this gesture.
        allRecognizers.filter { r -> canPreventRecognizer(r) }.forEach { r -> r.state = FAILED }
        return true
    }

    protected open fun canPreventRecognizer(that: GestureRecognizer) =
        this != that && target == that.target && that.state == POSSIBLE &&
                (requiredToFailByRecognizer(that) || !canRecognizeSimultaneouslyWith(that))

    protected open fun canBePreventedByRecognizer(that: GestureRecognizer) =
        this != that && target == that.target && that.state == RECOGNIZED &&
                (requiresRecognizerToFail(that) || !canRecognizeSimultaneouslyWith(that))

    protected open fun isWaitingForRecognizerToFail(that: GestureRecognizer) =
        this != that && target == that.target && that.state == POSSIBLE && requiresRecognizerToFail(that)

    protected open fun notifyListeners() = listenerList.forEach { l -> l.gestureStateChanged(this) }

    protected open fun callGestureCallbacks() = gestureCallbacks.forEach { c -> c(this) }

    override fun handleEvent(event: Event) {
        if (!isEnabled) return

        if (event.defaultPrevented && state == POSSIBLE) return // ignore cancelled events while in the Possible state

        try {
            when {
                event.type == "mousedown" && event is MouseEvent -> handleMouseDown(event)
                event.type == "mousemove" && event is MouseEvent -> handleMouseMove(event)
                event.type == "mouseup" && event is MouseEvent -> handleMouseUp(event)
                event.type == "touchstart" && event is TouchEvent -> for (i in 0 until event.changedTouches.length)
                    handleTouchStart(wrapTouch(event.changedTouches.item(i)!!))
                event.type == "touchmove" && event is TouchEvent -> for (i in 0 until event.changedTouches.length)
                    handleTouchMove(wrapTouch(event.changedTouches.item(i)!!))
                event.type == "touchcancel" && event is TouchEvent -> for (i in 0 until event.changedTouches.length)
                    handleTouchCancel(wrapTouch(event.changedTouches.item(i)!!))
                event.type == "touchend" && event is TouchEvent -> for (i in 0 until event.changedTouches.length)
                    handleTouchEnd(wrapTouch(event.changedTouches.item(i)!!))
                event.type == "pointerdown" && event is PointerEvent && event.pointerType == "mouse" ->
                    handleMouseDown(event)
                event.type == "pointermove" && event is PointerEvent && event.pointerType == "mouse" ->
                    handleMouseMove(event)
                event.type == "pointercancel" && event is PointerEvent && event.pointerType == "mouse" -> {
                    // Intentionally left blank. The W3C Pointer Events specification is ambiguous on what cancel means
                    // for mouse input, and there is no evidence that this event is actually generated (6/19/2015).
                }
                event.type == "pointerup" && event is PointerEvent && event.pointerType == "mouse" ->
                    handleMouseUp(event)
                event.type == "pointerdown" && event is PointerEvent && event.pointerType == "touch" ->
                    handleTouchStart(wrapPointer(event))
                event.type == "pointermove" && event is PointerEvent && event.pointerType == "touch" ->
                    handleTouchMove(wrapPointer(event))
                event.type == "pointercancel" && event is PointerEvent && event.pointerType == "touch" ->
                    handleTouchCancel(wrapPointer(event))
                event.type == "pointerup" && event is PointerEvent && event.pointerType == "touch" ->
                    handleTouchEnd(wrapPointer(event))
                else -> Logger.logMessage(Logger.INFO, "GestureRecognizer", "handleEvent",
                    "Unrecognized event type: ${event.type}")
            }
        } catch (e: Exception) {
            Logger.logMessage(Logger.ERROR, "GestureRecognizer", "handleEvent", "Error handling event.\n$e")
        }
    }

    protected open fun handleMouseDown(event: MouseEvent) {
        if (event.type == "mousedown" && touches.size > 0) return // ignore synthesized mouse down events on Android Chrome

        val buttonBit = 1 shl event.button.toInt()
        if (buttonBit and mouseButtonMask != 0) return // ignore redundant mouse down events

        if (mouseButtonMask == 0) { // first button down
            clientX = event.clientX
            clientY = event.clientY
            clientStartX = event.clientX
            clientStartY = event.clientY
            translationX = 0.0
            translationY = 0.0
        }

        mouseButtonMask = mouseButtonMask or buttonBit
        mouseDown(event)
    }

    protected open fun handleMouseMove(event: MouseEvent) {
        if (mouseButtonMask == 0) return // ignore mouse move events when this recognizer does not consider any button to be down

        if (clientX == event.clientX && clientY == event.clientY) return // ignore redundant mouse move events

        val dx = event.clientX - clientStartX
        val dy = event.clientY - clientStartY
        val w = translationWeight
        clientX = event.clientX
        clientY = event.clientY
        translationX = translationX * (1 - w) + dx * w
        translationY = translationY * (1 - w) + dy * w
        mouseMove(event)
    }

    protected open fun handleMouseUp(event: MouseEvent) {
        val buttonBit = 1 shl event.button.toInt()
        if (buttonBit and mouseButtonMask == 0) return // ignore mouse up events for buttons this recognizer does not consider to be down

        mouseButtonMask = mouseButtonMask and buttonBit.inv()
        mouseUp(event)

        if (mouseButtonMask == 0) resetIfEventsEnded() // last button up
    }

    protected open fun handleTouchStart(touch: TouchWrapper) {
        touches.add(touch)

        if (touches.size == 1) { // first touch
            clientX = touch.clientX
            clientY = touch.clientY
            clientStartX = touch.clientX
            clientStartY = touch.clientY
            translationX = 0.0
            translationY = 0.0
            touchCentroidShiftX = 0
            touchCentroidShiftY = 0
        } else touchesAddedOrRemoved()

        touchStart(touch)
    }

    protected open fun handleTouchMove(nextTouch: TouchWrapper) {
        val touch = touchById(nextTouch.identifier) ?: return // ignore events for touches that did not start in this recognizer's target
        if (touch.clientX == nextTouch.clientX && touch.clientY == nextTouch.clientY) return // ignore redundant touch move events, which we've encountered on Android Chrome

        touch.clientX = nextTouch.clientX
        touch.clientY = nextTouch.clientY

        val centroid = touchCentroid()
        val dx = centroid.clientX - clientStartX + touchCentroidShiftX
        val dy = centroid.clientY - clientStartY + touchCentroidShiftY
        val w = translationWeight
        clientX = centroid.clientX
        clientY = centroid.clientY
        translationX = translationX * (1 - w) + dx * w
        translationY = translationY * (1 - w) + dy * w

        touchMove(touch)
    }

    protected open fun handleTouchCancel(touch: TouchWrapper) {
        val touchIdx = indexOfTouchWithId(touch.identifier)
        if (touchIdx == -1) return // ignore events for touches that did not start in this recognizer's target
        touches.removeAt(touchIdx)
        touchesAddedOrRemoved()
        touchCancel(touch)
        resetIfEventsEnded()
    }

    protected open fun handleTouchEnd(touch: TouchWrapper) {
        val touchIdx = indexOfTouchWithId(touch.identifier)
        if (touchIdx == -1) return // ignore events for touches that did not start in this recognizer's target
        touches.removeAt(touchIdx)
        touchesAddedOrRemoved()
        touchEnd(touch)
        resetIfEventsEnded()
    }

    protected open fun resetIfEventsEnded() {
        if (state != POSSIBLE && mouseButtonMask == 0 && touches.size == 0) reset()
    }

    protected open fun touchesAddedOrRemoved() {
        touchCentroidShiftX += clientX
        touchCentroidShiftY += clientY
        val centroid = touchCentroid()
        clientX = centroid.clientX
        clientY = centroid.clientY
        touchCentroidShiftX -= clientX
        touchCentroidShiftY -= clientY
    }

    protected open fun touchCentroid(): Click {
        var x = 0
        var y = 0

        touches.forEach { touch ->
            x += touch.clientX / touches.size
            y += touch.clientY / touches.size
        }

        return Click(x, y)
    }

    protected open fun indexOfTouchWithId(identifier: Int) = touches.indexOfFirst { t -> t.identifier == identifier }

    protected open fun touchById(identifier: Int) = touches.firstOrNull { t -> t.identifier == identifier }

    protected open fun wrapTouch(event: Touch) = TouchWrapper(event.identifier, event.clientX, event.clientY)

    protected open fun wrapPointer(event: PointerEvent) = TouchWrapper(event.pointerId, event.clientX, event.clientY)

    protected class Click(
        /**
         * The X coordinate of the click point's location.
         */
        val clientX: Int,
        /**
         * The Y coordinate of the click point's location.
         */
        val clientY: Int
    )
}