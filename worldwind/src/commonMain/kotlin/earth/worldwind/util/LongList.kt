package earth.worldwind.util

import kotlin.math.max

/** Primitive-Long dynamic list (cf. [IntList]) for hot label-id accumulation where boxing each `Long`
 *  would dominate dense-tile assembly; collects per-point-label feature ids for the cross-tile id dedup. */
internal class LongList(initialCapacity: Int = 16) {
    private var buf: LongArray = LongArray(max(initialCapacity, 0))
    var size: Int = 0
        private set

    fun add(value: Long) {
        if (size == buf.size) grow(size + 1)
        buf[size++] = value
    }

    fun clear() { size = 0 }

    fun toLongArray(): LongArray = buf.copyOf(size)

    private fun grow(minCapacity: Int) {
        val current = buf.size
        val target = max(minCapacity, current + max(current shr 1, 16))
        buf = buf.copyOf(target)
    }
}
