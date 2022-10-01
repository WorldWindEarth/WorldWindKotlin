package earth.worldwind.geom

/**
 * Rectangular region in a two-dimensional coordinate system expressed as an origin and dimensions extending from the
 * origin.
 */
open class Viewport(
    /**
     * The X component of the viewport's origin.
     */
    var x: Int,
    /**
     * The Y component of the viewport's origin.
     */
    var y: Int,
    /**
     * The viewport's width.
     */
    var width: Int,
    /**
     * The viewport's height.
     */
    var height: Int
) {
    /**
     * Constructs an empty viewport width X, Y, width and height all zero.
     */
    constructor(): this(x = 0, y = 0,  width = 0,  height = 0)

    /**
     * Constructs a viewport with the origin and dimensions of a specified viewport.
     *
     * @param viewport the viewport specifying the values
     */
    constructor(viewport: Viewport): this(viewport.x, viewport.y, viewport.width, viewport.height)

    /**
     * Indicates whether this viewport is empty. A viewport is empty when either its width or its height are
     * zero (or negative).
     *
     * @return true if this viewport is empty, false otherwise
     */
    val isEmpty get() = width <= 0 || height <= 0

    /**
     * Sets this viewport to an empty viewport.
     *
     * @return this viewport with its width and height both set to zero
     */
    fun setEmpty() = apply {
        width = 0
        height = 0
    }

    /**
     * Sets this viewport to the specified origin and dimensions.
     *
     * @param x      the new X component of the viewport's lower left corner
     * @param y      the new Y component of the viewport's lower left corner
     * @param width  the viewport's new width
     * @param height the viewport's new height
     *
     * @return this viewport set to the specified values
     */
    fun set(x: Int, y: Int, width: Int, height: Int) = apply {
        this.x = x
        this.y = y
        this.width = width
        this.height = height
    }

    /**
     * Sets this viewport to the origin and dimensions of a specified viewport.
     *
     * @param viewport the viewport specifying the new values
     *
     * @return this viewport with its origin and dimensions set to that of the specified viewport
     */
    fun copy(viewport: Viewport) = set(viewport.x, viewport.y, viewport.width, viewport.height)

    /**
     * Indicates whether this viewport intersects a specified viewport. Two viewport intersect when both overlap by a
     * non-zero amount. An empty viewport never intersects another viewport.
     *
     * @param x      the X component of the viewport to test intersection with
     * @param y      the Y component of the viewport to test intersection with
     * @param width  the viewport width to test intersection with
     * @param height the viewport height to test intersection with
     *
     * @return true if the specified viewport intersections this viewport, false otherwise
     */
    fun intersects(x: Int, y: Int, width: Int, height: Int) =
        this.width > 0 && this.height > 0 && width > 0 && height > 0
                && this.x < x + width && x < this.x + this.width
                && this.y < y + height && y < this.y + this.height

    /**
     * Indicates whether this viewport intersects a specified viewport. Two viewport intersect when both overlap by a
     * non-zero amount. An empty viewport never intersects another viewport.
     *
     * @param viewport the viewport to test intersection with
     *
     * @return true if the specified viewport intersections this viewport, false otherwise
     */
    fun intersects(viewport: Viewport) =
        width > 0 && height > 0 && viewport.width > 0 && viewport.height > 0
                && x < viewport.x + viewport.width && viewport.x < x + width
                && y < viewport.y + viewport.height && viewport.y < y + height

    /**
     * Computes the intersection of this viewport and a specified viewport, storing the result in this viewport and
     * returning whether the viewport intersects. Two viewport intersect when both overlap by a non-zero amount.
     * An empty viewport never intersects another viewport.
     * <br>
     * When there is no intersection, this returns false and leaves this viewport unchanged. To test for intersection
     * without modifying this viewport, use [intersects].
     *
     * @param x      the X component of the viewport to intersect with
     * @param y      the Y component of the viewport to intersect with
     * @param width  the viewport width to intersect with
     * @param height the viewport height to intersect with
     *
     * @return true if this viewport intersects the specified viewport, false otherwise
     */
    fun intersect(x: Int, y: Int, width: Int, height: Int): Boolean {
        if (this.width > 0 && this.height > 0 && width > 0 && height > 0
            && this.x < x + width && x < this.x + this.width
            && this.y < y + height && y < this.y + this.height) {
            if (this.x < x) {
                this.width -= x - this.x
                this.x = x
            }
            if (this.y < y) {
                this.height -= y - this.y
                this.y = y
            }
            if (this.x + this.width > x + width) this.width = x + width - this.x
            if (this.y + this.height > y + height) this.height = y + height - this.y
            return true
        }
        return false
    }

    /**
     * Computes the intersection of this viewport and a specified viewport, storing the result in this viewport and
     * returning whether the viewport intersects. Two viewport intersect when both overlap by a non-zero amount.
     * An empty viewport never intersects another viewport.
     * <br>
     * When there is no intersection, this returns false and leaves this viewport unchanged. To test for intersection
     * without modifying this viewport, use [intersects].
     *
     * @param viewport the viewport to intersect with
     *
     * @return true if this viewport intersects the specified viewport, false otherwise
     */
    fun intersect(viewport: Viewport): Boolean {
        if (width > 0 && height > 0 && viewport.width > 0 && viewport.height > 0
            && x < viewport.x + viewport.width && viewport.x < x + width
            && y < viewport.y + viewport.height && viewport.y < y + height) {
            if (x < viewport.x) {
                width -= viewport.x - x
                x = viewport.x
            }
            if (y < viewport.y) {
                height -= viewport.y - y
                y = viewport.y
            }
            if (x + width > viewport.x + viewport.width) width = viewport.x + viewport.width - x
            if (y + height > viewport.y + viewport.height) height = viewport.y + viewport.height - y
            return true
        }
        return false
    }

    /**
     * Indicates whether this viewport contains a specified point. An empty viewport never contains a point.
     *
     * @param x the point's X component
     * @param y the point's Y component
     *
     * @return true if this viewport contains the point, false otherwise
     */
    fun contains(x: Int, y: Int) = x >= this.x && x < this.x + width && y >= this.y && y < this.y + height

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Viewport) return false
        return x == other.x && y == other.y && width == other.width && height == other.height
    }

    override fun hashCode(): Int {
        var result = x
        result = 31 * result + y
        result = 31 * result + width
        result = 31 * result + height
        return result
    }

    override fun toString() = "Viewport(x=$x, y=$y, width=$width, height=$height)"
}