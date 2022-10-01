package earth.worldwind.draw

/**
 * Drawable group provides a standard set of group IDs for organizing WorldWindow drawing into four phases:
 * background, surface, shape, and screen.
 * Accepted values are [BACKGROUND], [SURFACE], [SHAPE] and [SCREEN].
 */
enum class DrawableGroup {
    /**
     * Indicating drawables displayed before everything else. This group is typically
     * used to display atmosphere and stars before all other drawables.
     */
    BACKGROUND,
    /**
     * Indicating drawables displayed on the globe's surface. Surface drawables are
     * displayed beneath shapes and screen drawables.
     */
    SURFACE,
    /**
     * Indicating shape drawables, such as placemarks, polygons and polylines. Shape
     * drawables are displayed on top of surface drawables, but beneath screen drawables.
     */
    SHAPE,
    /**
     * Indicating drawables displayed in the plane of the screen. Screen drawables are
     * displayed on top of everything else.
     */
    SCREEN;
}