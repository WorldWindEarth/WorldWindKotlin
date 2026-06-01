package earth.worldwind.util

import kotlin.concurrent.Volatile

/** Sibling of [FloatArrayPool] for ByteArrays; identical contract. */
object ByteArrayPool {
    private const val MAX_POOLED = 16
    private val pool = SynchronizedPool<ByteArray>()
    @Volatile private var approxCount = 0

    fun acquire(minBytes: Int): ByteArray {
        val buf = pool.acquire()
        if (buf != null) {
            approxCount = maxOf(0, approxCount - 1)
            if (buf.size >= minBytes) return buf
        }
        return ByteArray(minBytes)
    }

    fun release(buf: ByteArray) {
        if (buf.isEmpty()) return
        if (approxCount >= MAX_POOLED) return
        approxCount += 1
        pool.release(buf)
    }
}
