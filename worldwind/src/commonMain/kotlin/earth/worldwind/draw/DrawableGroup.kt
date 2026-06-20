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
     * Drawables displayed on the globe's surface, beneath shapes and screen drawables.
     *
     * Within this group the ordering convention is:
     *  - `Double.NEGATIVE_INFINITY` — 3D-Tile mesh content (writes [GROUND_COVERED_BIT]
     *    before terrain reads it).
     *  - `-Double.MAX_VALUE` — terrain depth-only quad (immediately after mesh).
     *  - Other zOrder values — terrain decals (imagery, surface shapes, etc.).
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