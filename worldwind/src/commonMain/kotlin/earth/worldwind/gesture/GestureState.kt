package earth.worldwind.gesture

/**
 * Gesture state indicates a GestureRecognizer's current state.
 * Accepted values are [POSSIBLE], [FAILED], [RECOGNIZED], [BEGAN], [CHANGED], [CANCELLED], and [ENDED].
 */
enum class GestureState {
    /**
     * POSSIBLE gesture recognizer state. Gesture recognizers in this state are
     * idle when there is no input event to evaluate, or are evaluating input events to determine whether or not to
     * transition into another state.
     */
    POSSIBLE,
    /**
     * FAILED gesture recognizer state. Gesture recognizers transition to this
     * state from the POSSIBLE state when the gesture cannot be recognized given the current input.
     */
    FAILED,
    /**
     * RECOGNIZED gesture recognizer state. Discrete gesture recognizers
     * transition to this state from the POSSIBLE state when the gesture is recognized.
     */
    RECOGNIZED,
    /**
     * BEGAN gesture recognizer state. Continuous gesture recognizers transition
     * to this state from the POSSIBLE state when the gesture is first recognized.
     */
    BEGAN,
    /**
     * CHANGED gesture recognizer state. Continuous gesture recognizers
     * transition to this state from the BEGAN state or the CHANGED state, whenever an input event indicates a change in
     * the gesture.
     */
    CHANGED,
    /**
     * CANCELLED gesture recognizer state. Continuous gesture recognizers may
     * transition to this state from the BEGAN state or the CHANGED state when the touch events are cancelled.
     */
    CANCELLED,
    /**
     * ENDED gesture recognizer state. Continuous gesture recognizers
     * transition to this state from either the BEGAN state or the CHANGED state when the current input no longer
     * represents the gesture.
     */
    ENDED;
}