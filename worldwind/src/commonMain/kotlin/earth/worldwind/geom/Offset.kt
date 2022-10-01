package earth.worldwind.geom

import earth.worldwind.geom.OffsetMode.FRACTION
import earth.worldwind.geom.OffsetMode.INSET_PIXELS
import kotlin.jvm.JvmStatic

/**
 * Specifies an offset relative to a rectangle. Used by renderable shapes.
 */
open class Offset(
    /**
     * The units of this instance's X offset. See this class' constructor description for a list of the possible
     * values.
     */
    var xUnits: OffsetMode,
    /**
     * The offset in the X dimension, interpreted according to this instance's xUnits argument.
     */
    var x: Double,
    /**
     * The units of this instance's Y offset. See this class' constructor description for a list of the possible
     * values.
     */
    var yUnits: OffsetMode,
    /**
     * The offset in the Y dimension, interpreted according to this instance's yUnits argument.
     */
    var y: Double
) {
    /**
     * Creates a new offset of this offset with identical property values.
     */
    constructor(offset: Offset): this(offset.xUnits, offset.x, offset.yUnits, offset.y)

    companion object {
        /**
         * This factory method returns a new offset used for anchoring a rectangle to its center.
         */
        @JvmStatic fun center() = Offset(FRACTION, 0.5, FRACTION, 0.5)
        /**
         * This factory method returns a new offset used for anchoring a rectangle to its bottom-left corner.
         */
        @JvmStatic fun bottomLeft() = Offset(FRACTION, 0.0, FRACTION, 0.0)
        /**
         * This factory method returns a new offset for anchoring a rectangle to its center of its bottom edge.
         */
        @JvmStatic fun bottomCenter() = Offset(FRACTION, 0.5, FRACTION, 0.0)
        /**
         * This factory method returns a new offset for anchoring a rectangle to its bottom-right corner.
         */
        @JvmStatic fun bottomRight() = Offset(FRACTION, 1.0, FRACTION, 0.0)
        /**
         * This factory method returns a new offset for anchoring a rectangle its top-left corner.
         */
        @JvmStatic fun topLeft() = Offset(FRACTION, 0.0, FRACTION, 1.0)
        /**
         * This factory method returns a new offset for anchoring a rectangle to the center of its top edge.
         */
        @JvmStatic fun topCenter() = Offset(FRACTION, 0.5, FRACTION, 1.0)
        /**
         * This factory method returns a new offset for anchoring a rectangle to its top-right corner.
         */
        @JvmStatic fun topRight() = Offset(FRACTION, 1.0, FRACTION, 1.0)
    }

    /**
     * Sets this offset to identical property values of the specified offset.
     */
    fun copy(offset: Offset) = apply {
        x = offset.x
        y = offset.y
        xUnits = offset.xUnits
        yUnits = offset.yUnits
    }

    /**
     * Returns this offset's absolute X and Y coordinates in pixels for a rectangle of a specified size in pixels. The
     * returned offset is in pixels relative to the rectangle's origin, and is defined in the coordinate system used by
     * the caller.
     *
     * @param width  the rectangle's width in pixels
     * @param height the rectangles height in pixels
     * @param result a pre-allocated Vec2 in which to return the computed offset relative to the rectangle's origin
     *
     * @return the result argument set to the computed offset
     */
    fun offsetForSize(width: Double, height: Double, result: Vec2): Vec2 {
        val x = when (xUnits) {
            FRACTION -> width * x
            INSET_PIXELS -> width - x
            else -> x // default to OFFSET_PIXELS
        }
        val y = when (yUnits) {
            FRACTION -> height * y
            INSET_PIXELS -> height - y
            else -> y // default to OFFSET_PIXELS
        }
        return result.set(x, y)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Offset) return false
        return xUnits == other.xUnits && x == other.x && yUnits == other.yUnits && y == other.y
    }

    override fun hashCode(): Int {
        var result = xUnits.hashCode()
        result = 31 * result + x.hashCode()
        result = 31 * result + yUnits.hashCode()
        result = 31 * result + y.hashCode()
        return result
    }

    override fun toString() = "Offset(xUnits=$xUnits, x=$x, yUnits=$yUnits, y=$y)"
}