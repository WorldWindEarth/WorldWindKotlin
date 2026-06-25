package earth.worldwind.util

import kotlin.math.max

/** Primitive-Float dynamic list (cf. [IntList]) for hot vertex-accumulation paths where boxing each
 *  `Float` would dominate assembly time on dense tiles. */
internal class FloatList(initialCapacity: Int = 64) {
    private var buf: FloatArray = FloatArray(max(initialCapacity, 0))
    var size: Int = 0
        private set

    fun add(value: Float) {
        if (size == buf.size) grow(size + 1)
        buf[size++] = value
    }

    fun clear() { size = 0 }

    /** Pre-grow to [minCapacity] floats so a known-size fill skips the incremental copyOf growth. */
    fun ensureCapacity(minCapacity: Int) { if (minCapacity > buf.size) grow(minCapacity) }

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
