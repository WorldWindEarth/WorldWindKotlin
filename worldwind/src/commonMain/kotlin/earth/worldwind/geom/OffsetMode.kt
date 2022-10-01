package earth.worldwind.geom

/**
 * Offset mode indicates how WorldWind interprets an offset's x and y values.
 * Accepted values are [FRACTION], [INSET_PIXELS] and [PIXELS].
 */
enum class OffsetMode {
    /**
     * Indicating that the associated parameters are fractional values of the virtual
     * rectangle's width or height in the range [0, 1], where 0 indicates the rectangle's origin and 1 indicates the
     * corner opposite its origin.
     */
    FRACTION,
    /**
     * Indicating that the associated parameters are in units of pixels relative to the
     * virtual rectangle's corner opposite its origin corner.
     */
    INSET_PIXELS,
    /**
     * Indicating that the associated parameters are in units of pixels relative to the
     * virtual rectangle's origin.
     */
    PIXELS;
}