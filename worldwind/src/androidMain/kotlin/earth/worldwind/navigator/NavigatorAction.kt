package earth.worldwind.navigator

/**
 * Navigator event action indicates the type of NavigatorEvent that has been generated.
 * Accepted values are [MOVED] and [STOPPED].
 */
enum class NavigatorAction {
    /**
     * Indicating that the navigator has moved.
     */
    MOVED,
    /**
     * Indicating that the navigator has stopped moving.
     */
    STOPPED;
}