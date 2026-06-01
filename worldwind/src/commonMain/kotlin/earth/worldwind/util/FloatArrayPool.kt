package earth.worldwind.util

import kotlin.concurrent.Volatile

/**
 * Cross-thread reusable [FloatArray] pool. Single-queue, no size buckets — the queue
 * converges to the largest-seen size, smaller acquirers get an oversized buffer (callers
 * use their own valid count when reading/writing).
 */
object FloatArrayPool {
    /** Bounded memory ceiling = MAX_POOLED × largest-seen size. */
    private const val MAX_POOLED = 16

    private val pool = SynchronizedPool<FloatArray>()
    /** Approximate, raced under concurrent acquire+release; worst case the pool grows slightly over cap. */
    @Volatile private var approxCount = 0

    fun acquire(minFloats: Int): FloatArray {
        val buf = pool.acquire()
        if (buf != null) {
            approxCount = maxOf(0, approxCount - 1)
            if (buf.size >= minFloats) return buf
            // Polled buffer too small — drop, allocate fresh.
        }
        return FloatArray(minFloats)
    }

    fun release(buf: FloatArray) {
        if (buf.isEmpty()) return
        if (approxCount >= MAX_POOLED) return
        approxCount += 1
        pool.release(buf)
    }
}
