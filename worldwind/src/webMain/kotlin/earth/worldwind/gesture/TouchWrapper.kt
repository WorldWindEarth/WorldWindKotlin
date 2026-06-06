package earth.worldwind.gesture

/**
 * Represents a touch point.
 */
open class TouchWrapper(
    /**
     * A number uniquely identifying the touch point
     */
    var identifier: Int,
    /**
     * The X coordinate of the touch point's location.
     */
    var clientX: Int,
    /**
     * The Y coordinate of the touch point's location.
     */
    var clientY: Int
) {
    /**
     * Indicates this touch point's translation along the X axis since the touch started.
     */
    var translationX: Int
        get() = clientX - clientStartX
        set(value) { clientStartX = clientX - value }
    /**
     * Indicates this touch point's translation along the Y axis since the touch started.
     */
    var translationY: Int
        get() = clientY - clientStartY
        set(value) { clientStartY = clientY - value }
    protected var clientStartX = clientX
    protected var clientStartY = clientY
}