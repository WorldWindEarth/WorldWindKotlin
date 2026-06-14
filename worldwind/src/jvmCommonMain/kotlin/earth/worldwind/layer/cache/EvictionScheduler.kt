package earth.worldwind.layer.cache

import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Mapbox-style per-table eviction bookkeeping for GeoPackage-backed cache stores. Each
 * [notifyInsert] bumps an in-memory row counter; once over `maxEntries × (1 + OVERSHOOT_RATIO)`
 * the store's chunked drain fires on a shared background scope. Replaces the single bulk
 * sweep at store-open that used to stall SQLite for tens of seconds while letting the cache
 * grow unbounded between sessions.
 */
internal class EvictionScheduler {

    /** Per-table approximate row count; seeded asynchronously on first insert. */
    private val approxCounts = HashMap<String, Long>()

    /** Tables whose seed `SELECT COUNT(*)` is in flight; further inserts skip until it lands. */
    private val seedInFlight = HashSet<String>()

    /** Suppresses concurrent fires while one drain is still running. */
    private val evictInFlight = HashMap<String, Boolean>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var shutdown: Boolean = false

    /** Bump the counter and schedule [evict] when the table is over budget. Insert
     *  count > 1 accounts for multi-row writes (e.g. feature batches). */
    fun notifyInsert(
        tableName: String,
        policy: CachePolicy,
        countRows: suspend () -> Long,
        evict: suspend () -> Unit,
        inserted: Long = 1L,
    ) {
        if (shutdown || policy.maxEntries == Long.MAX_VALUE || inserted <= 0) return
        // First touch — kick off a background seed and skip this put's eviction decision.
        // Mapbox-pattern: keeps the writer non-blocking; the second put accumulates on the seed.
        val current = synchronized(this) {
            val cached = approxCounts[tableName]
            if (cached == null) {
                if (seedInFlight.add(tableName)) scope.launch {
                    try {
                        val seeded = countRows()
                        synchronized(this@EvictionScheduler) {
                            approxCounts.putIfAbsent(tableName, seeded)
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        logMessage(WARN, "EvictionScheduler", "seed", "count failed on $tableName", t)
                    } finally {
                        synchronized(this@EvictionScheduler) { seedInFlight.remove(tableName) }
                    }
                }
                return
            }
            val next = cached + inserted
            approxCounts[tableName] = next
            next
        }
        val overshoot = maxOf((policy.maxEntries * OVERSHOOT_RATIO).toLong(), MIN_OVERSHOOT_ROWS)
        if (current <= policy.maxEntries + overshoot) return
        val claimed = synchronized(this) {
            if (evictInFlight[tableName] == true) false else { evictInFlight[tableName] = true; true }
        }
        if (!claimed) return
        scope.launch {
            try {
                evict()
                // Compensating decrement instead of remove(): preserves any concurrent inserts'
                // bumps that landed during eviction. Floors at 0 in case overshoot estimate drifts.
                synchronized(this@EvictionScheduler) {
                    val after = (approxCounts[tableName] ?: 0L) - (current - policy.maxEntries)
                    approxCounts[tableName] = after.coerceAtLeast(0L)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logMessage(WARN, "EvictionScheduler", "notifyInsert", "eviction failed on $tableName", t)
            } finally {
                synchronized(this@EvictionScheduler) { evictInFlight.remove(tableName) }
            }
        }
    }

    /** Cancel in-flight evictions on [GeoPackage] close. */
    fun shutdown() {
        if (shutdown) return
        shutdown = true
        scope.cancel()
    }

    companion object {
        /** Grace zone over [CachePolicy.maxEntries] — 5 % means eviction only fires when the
         *  cache crosses 105 % of budget, avoiding scheduling on every put at capacity. */
        private const val OVERSHOOT_RATIO = 0.05

        /** Floor on the grace zone — tiny caches (maxEntries < ~320) where 5 % rounds to 0 would
         *  otherwise schedule async eviction on every put, defeating the point of the scheduler. */
        private const val MIN_OVERSHOOT_ROWS = 16L
    }
}
