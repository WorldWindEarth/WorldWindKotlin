package earth.worldwind.geom

/**
 * Continuous interval in a one-dimensional coordinate system expressed as a lower bound an an upper bound, inclusive.
 */
open class Range(
    /**
     * The range's lower bound, inclusive.
     */
    var lower: Int,
    /**
     * The range's upper bound, inclusive.
     */
    var upper: Int
) {
    /**
     * Constructs an empty range with lower and upper both zero.
     */
    constructor(): this(lower = 0, upper = 0)

    /**
     * Constructs a range with the lower bound and upper bound of a specified range.
     *
     * @param range the range specifying the values
     */
    constructor(range: Range) : this(range.lower, range.upper)

    /**
     * Returns the length of the interval between this range's lower bound and upper bound, or 0 if this range is empty.
     */
    val length get() = if (upper > lower) upper - lower else 0

    /**
     * Indicates whether or not this range is empty. An range is empty when its lower bound is greater than or equal to
     * its upper bound.
     *
     * @return true if this range is empty, false otherwise
     */
    val isEmpty get() = lower >= upper

    /**
     * Sets this range to an empty range.
     *
     * @return this range with its lower bound and upper bound both set to zero
     */
    fun setEmpty() = set(0, 0)

    /**
     * Sets this range to the specified lower bound and upper bound.
     *
     * @param lower the new lower bound, inclusive
     * @param upper the new upper bound, inclusive
     *
     * @return this range set to the specified values
     */
    fun set(lower: Int, upper: Int) = apply {
        this.lower = lower
        this.upper = upper
    }

    /**
     * Sets this range to the lower bound and upper bound of a specified range.
     *
     * @param range the range specifying the new values
     *
     * @return this range with its lower bound and upper bound set to that of the specified range
     */
    fun copy(range: Range) = set(range.lower, range.upper)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Range) return false
        return lower == other.lower && upper == other.upper
    }

    override fun hashCode(): Int {
        var result = lower
        result = 31 * result + upper
        return result
    }

    override fun toString() = "Range(lower=$lower, upper=$upper)"
}