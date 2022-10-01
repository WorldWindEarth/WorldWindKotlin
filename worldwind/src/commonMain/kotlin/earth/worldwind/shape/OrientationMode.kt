package earth.worldwind.shape

/**
 * Orientation mode indicates how WorldWind interprets a renderable's orientation value, e.g., tilt and rotate
 * values. Accepted values are [RELATIVE_TO_GLOBE], and [RELATIVE_TO_SCREEN].
 */
enum class OrientationMode {
    /**
     * Indicating that the related value is specified relative to the globe.
     */
    RELATIVE_TO_GLOBE,
    /**
     * Indicating that the related value is specified relative to the plane of the screen.
     */
    RELATIVE_TO_SCREEN;
}