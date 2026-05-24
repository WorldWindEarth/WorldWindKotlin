package earth.worldwind.layer.mvt

import kotlin.math.max

/**
 * Primitive-Float dynamic list. Mirrors [earth.worldwind.util.IntList] for hot vertex
 * accumulation paths where boxing each `Float` would dominate assembly time on dense tiles.
 *
 * Used by both [MvtBatchedPolygonTile] and [MvtBatchedLineTile] — shared because two
 * file-private classes with the same name in the same package trip K2's resolver.
 * `internal` scope keeps it out of the public layer API while still letting both batchers
 * compile against the single declaration.
 */
internal class FloatList(initialCapacity: Int = 64) {
    private var buf: FloatArray = FloatArray(max(initialCapacity, 0))
    var size: Int = 0
        private set

    fun add(value: Float) {
        if (size == buf.size) grow(size + 1)
        buf[size++] = value
    }

    fun clear() { size = 0 }

    fun shrink() {
        buf = FloatArray(0)
        size = 0
    }

    fun toFloatArray(): FloatArray = buf.copyOf(size)

    private fun grow(minCapacity: Int) {
        val current = buf.size
        val target = max(minCapacity, current + max(current shr 1, 64))
        buf = buf.copyOf(target)
    }
}
