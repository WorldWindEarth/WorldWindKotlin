package earth.worldwind.layer

import kotlin.time.Clock

/**
 * Per-tile exponential-backoff bookkeeping shared by the tiled vector layers ([TiledVectorLayer],
 * [earth.worldwind.layer.buildings.OsmBuildingsLayer]). A failed fetch bumps the key's streak and
 * bars re-requests until a deadline; the schedule is 2 s, 5 s, 15 s, then 60 s. A key clears on the
 * next success. Keyed by whatever tile identity the layer uses ([String] or a tile-coordinate class).
 */
internal class TileBackoff<K> {
    private class Entry {
        var failCount = 0
        var nextRetryEpochMs = 0L
    }

    private val entries = HashMap<K, Entry>()

    /** Number of keys currently in backoff (including permanently given-up keys). */
    val size: Int get() = entries.size

    /** True while [key] is still inside its post-failure backoff window — gate re-requests on this. */
    fun isInBackoff(key: K): Boolean {
        val entry = entries[key] ?: return false
        return Clock.System.now().toEpochMilliseconds() < entry.nextRetryEpochMs
    }

    /**
     * Advance [key]'s failure streak and arm the next retry window. Returns the backoff delay in ms
     * (schedule a wake-up redraw for it), or `null` when the streak has passed [maxRetries] — the key
     * is then parked far in the future so it's never re-requested and the caller stops waking the
     * render loop. A null [maxRetries] never gives up (always returns a delay).
     */
    fun recordFailure(key: K, maxRetries: Int? = null): Long? {
        val entry = entries.getOrPut(key) { Entry() }
        entry.failCount++
        if (maxRetries != null && entry.failCount > maxRetries) {
            entry.nextRetryEpochMs = Long.MAX_VALUE
            return null
        }
        val delayMs = backoffDelayMs(entry.failCount)
        entry.nextRetryEpochMs = Clock.System.now().toEpochMilliseconds() + delayMs
        return delayMs
    }

    /** Clear [key]'s backoff after a successful fetch (or when the tile is invalidated). */
    fun clear(key: K) { entries.remove(key) }

    /** Drop all backoff state (layer clear / close). */
    fun clear() { entries.clear() }

    private companion object {
        fun backoffDelayMs(failCount: Int): Long = when (failCount) {
            1 -> 2_000
            2 -> 5_000
            3 -> 15_000
            else -> 60_000
        }
    }
}
