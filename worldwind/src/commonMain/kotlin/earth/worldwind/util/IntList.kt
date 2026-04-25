package earth.worldwind.util

import kotlin.math.max

/**
 * Primitive-Int dynamic list. Drop-in replacement for `MutableList<Int>` in hot loops where
 * boxing each `Int` to a JVM `Integer` causes meaningful GC pressure. Used for shape index
 * buffers (`outlineElements`, `topElements`, etc.) which can hold thousands of indices and
 * are repopulated on every geometry assemble.
 *
 * Single-threaded; backing array grows by 50%-or-12 (whichever is larger) on overflow.
 */
class IntList(initialCapacity: Int = 16) {
    private var data: IntArray = IntArray(max(initialCapacity, 0))
    var size: Int = 0
        private set

    operator fun get(index: Int): Int = data[index]

    fun add(value: Int) {
        if (size == data.size) grow(size + 1)
        data[size++] = value
    }

    fun clear() { size = 0 }

    /** Drops the trailing [count] elements without shrinking the backing array. */
    fun removeLast(count: Int) {
        require(count >= 0 && count <= size) { "removeLast($count) out of range; size=$size" }
        size -= count
    }

    /** Copies [size] elements into [dst] starting at [dstOffset]; returns `dstOffset + size`. */
    fun copyTo(dst: IntArray, dstOffset: Int): Int {
        data.copyInto(dst, dstOffset, 0, size)
        return dstOffset + size
    }

    /** Copies elements into [dst] in reverse order starting at [dstOffset]; returns `dstOffset + size`. */
    fun copyToReversed(dst: IntArray, dstOffset: Int): Int {
        var di = dstOffset
        var si = size - 1
        while (si >= 0) {
            dst[di++] = data[si--]
        }
        return di
    }

    /** Returns a freshly allocated `IntArray` containing exactly [size] elements. */
    fun toIntArray(): IntArray = data.copyOf(size)

    private fun grow(minCapacity: Int) {
        val current = data.size
        val target = max(minCapacity, current + max(current shr 1, 12))
        data = data.copyOf(target)
    }
}
