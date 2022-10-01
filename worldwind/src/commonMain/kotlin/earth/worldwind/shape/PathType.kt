package earth.worldwind.shape

/**
 * Path type indicates how WorldWind create a geographic path between two locations.
 * Accepted values are [GREAT_CIRCLE], [LINEAR] and [RHUMB_LINE].
 */
enum class PathType {
    /**
     * Indicating a great circle arc between two locations.
     */
    GREAT_CIRCLE,
    /**
     * Indicating simple linear interpolation between two locations.
     */
    LINEAR,
    /**
     * Indicating a line of constant bearing between two locations.
     */
    RHUMB_LINE;
}