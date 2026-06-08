package earth.worldwind.globe.elevation.coverage

/**
 * A decoded elevation tile ([ShortArray], NW origin) plus min/max/missing stats computed once at
 * construction, so height-limit queries that fully contain the tile skip the per-texel rescan.
 * Missing posts are [Short.MIN_VALUE] and excluded from the min/max.
 */
class ElevationImage(val array: ShortArray) {
    /** Lowest valid elevation in the tile, or `0` if the tile has no valid posts. */
    var minElevation = 0f
        private set
    /** Highest valid elevation in the tile, or `0` if the tile has no valid posts. */
    var maxElevation = 0f
        private set
    /** True if the tile has at least one valid (non-missing) post. */
    var hasData = false
        private set
    /** True if the tile has at least one missing post. */
    var hasMissingData = false
        private set
    /** Heap footprint charged to the coverage cache (2 bytes per post). */
    val sizeInBytes get() = array.size * 2

    init {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (p in array) {
            if (p != NO_DATA) {
                hasData = true
                val f = p.toFloat()
                if (f < min) min = f
                if (f > max) max = f
            } else {
                hasMissingData = true
            }
        }
        if (hasData) {
            minElevation = min
            maxElevation = max
        }
    }

    private companion object {
        const val NO_DATA = Short.MIN_VALUE
    }
}
