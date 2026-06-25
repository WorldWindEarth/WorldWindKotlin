package earth.worldwind.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * Priority-ordered permit pool. [acquire] suspends until a permit is free; when multiple acquirers
 * are waiting, [release] wakes the one with the highest [priority] (= largest Double). Replaces
 * `kotlinx.coroutines.sync.Semaphore`, which is FIFO — under a fast pan with hundreds of queued
 * tile-fetch requests, FIFO leaves the camera-relevant (or coarse-fallback) tile buried behind
 * seconds of stale prior-frame requests. There is no in-flight cancellation: this reorders only the
 * WAIT line, not the running set. Cesium 3D Tiles uses the same pattern in `RequestScheduler.update()`.
 *
 * Shared by [earth.worldwind.layer.ogc3d.stream.TileFetchQueue] (closer + coarser wins) and the
 * vector-tile fetch path (coarse-first, so the fallback always paints before refinement).
 *
 * Implementation: linear-insert + head-pop sorted list of waiters; O(N) per acquire, O(1) per
 * release. For N up to ~1000 (observed at globe scale) the linear insert is microseconds — far
 * cheaper than the multi-second queue stall it eliminates.
 */
class PrioritySemaphore(private val maxPermits: Int) {
    private val mutex = Mutex()
    @Volatile private var available: Int = maxPermits
    private val waiters = ArrayDeque<Waiter>()

    private class Waiter(val priority: Double, val deferred: CompletableDeferred<Unit>)

    suspend fun acquire(priority: Double) {
        // Fast path or register-and-wait, decided under the mutex.
        val waiter: Waiter? = mutex.withLock {
            if (available > 0) {
                available--
                null
            } else {
                Waiter(priority, CompletableDeferred()).also { w ->
                    // Sorted descending by priority: head = highest.
                    var i = 0
                    while (i < waiters.size && waiters[i].priority >= priority) i++
                    waiters.add(i, w)
                }
            }
        }
        if (waiter == null) return  // fast path: got a permit immediately
        try {
            waiter.deferred.await()
        } catch (e: CancellationException) {
            // NonCancellable so we can clean up even though we ARE the cancelled coroutine.
            // If `waiters.remove` returns false, [release] already popped us off and delivered
            // the permit (its `deferred.complete` ran in the window between our cancel and our
            // resume) — we never claimed it, so hand it on to the next waiter via [release].
            // Without this, every cancel-during-wakeup leaks one permit; a fling-pan session
            // drains the pool and stalls every future fetch.
            withContext(NonCancellable) {
                val wokenButCancelled = mutex.withLock { !waiters.remove(waiter) }
                if (wokenButCancelled) release()
            }
            throw e
        }
    }

    /** Must be cancellation-safe — callers invoke from `finally` blocks of fetches that may already
     *  be cancelled. A bare `mutex.withLock` would throw immediately under the cancelled context,
     *  leaking the permit; running under `NonCancellable` lets the brief atomic update always finish. */
    suspend fun release() = withContext(NonCancellable) {
        // Loop in case the head waiter was cancelled between acquire-suspend and now — skip dead
        // waiters and either wake a live one or return the permit to the pool.
        while (true) {
            val woken = mutex.withLock {
                if (waiters.isEmpty()) {
                    available++
                    null
                } else waiters.removeFirst()
            } ?: return@withContext
            if (woken.deferred.complete(Unit)) return@withContext
        }
    }

    fun availablePermits(): Int = available
}

/** Acquire a permit at [priority], run [block], then release — the priority analogue of
 *  `kotlinx.coroutines.sync.withPermit`. Higher [priority] is served first when the pool is
 *  contended; equal priorities keep FIFO order. */
suspend inline fun <T> PrioritySemaphore.withPermit(priority: Double, block: () -> T): T {
    acquire(priority)
    try {
        return block()
    } finally {
        release()
    }
}
