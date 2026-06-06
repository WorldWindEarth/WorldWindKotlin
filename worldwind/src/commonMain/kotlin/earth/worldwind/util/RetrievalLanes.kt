package earth.worldwind.util

/** The lane a retrieval runs on: [LOCAL] (in-bundle resources, local files and cache-only reads)
 *  or [REMOTE] (anything fetched from a remote server — map tiles, remote icons, DEM tiles, any URL). */
enum class RetrievalLane { LOCAL, REMOTE }

/** Next phase for a cache-backed source — see [RetrievalLanes.planNetworkBound]. */
enum class RetrievalPhase { NONE, CACHE, NETWORK }

/**
 * Shared, key-agnostic retrieval-lane bookkeeping. Used by every poll-per-frame retriever that
 * has a local cache in front of a remote source — the image [earth.worldwind.render.RenderResourceCache]
 * (keyed by `ImageSource`) and the gridded-coverage
 * [earth.worldwind.globe.elevation.coverage.AbstractTiledElevationCoverage] (keyed by tile `Long`).
 *
 * Splits retrievals into a **remote** lane (anything fetched from a remote server) and a **local**
 * lane (in-bundle resources, local files, and cache-only reads), so a slow remote fetch can never
 * occupy a slot a local or cached read needs. The piece that makes the separation actually hold is
 * [cacheChecked]: once a source's cache-only read misses, it is recorded so its cache read is NOT
 * re-issued on every subsequent frame while it waits for a remote slot. Without that, a congested
 * remote lane indirectly saturates the local lane with repeating cache-miss reads and starves
 * genuine local resources.
 *
 * The element type [K] is the retrieval's identity key (an `ImageSource`, a tile `Long`, …); it must
 * have value-correct `equals`/`hashCode` since membership drives dedup and the lane budgets.
 *
 * Not thread-safe: callers reserve/release from a single retrieval scope (the main scope on every
 * platform), matching how the elevation and image stacks already drive retrieval.
 */
class RetrievalLanes<K> {
    /** Remote-server fetches in progress (remote lane). */
    val remoteRetrievals = mutableSetOf<K>()
    /** Local reads in progress (local lane): resources, files, and cache-only reads. */
    val localRetrievals = mutableSetOf<K>()
    /** Keys whose cache-only read already missed: route straight to the remote lane instead of
     *  re-reading the cache every frame. Cleared on success / failure / revalidation / [clear]. */
    val cacheChecked = mutableSetOf<K>()

    private fun lane(lane: RetrievalLane) = if (lane == RetrievalLane.REMOTE) remoteRetrievals else localRetrievals

    /** True when [key] can be reserved on [lane] now: a free slot and not already in flight there. */
    fun canReserve(lane: RetrievalLane, queueSize: Int, key: K) =
        lane(lane).let { it.size < queueSize && !it.contains(key) }

    fun reserve(lane: RetrievalLane, key: K) { lane(lane) += key }
    fun release(lane: RetrievalLane, key: K) { lane(lane) -= key }

    /** True when a remote (network) fetch for [key] is already in flight. */
    fun isNetworkInFlight(key: K) = remoteRetrievals.contains(key)

    /**
     * Plan the next phase for a cache-backed [key]:
     *  - [RetrievalPhase.NONE] when the key is absent or its remote fetch is already in flight;
     *  - [RetrievalPhase.CACHE] for a first-time cache-only read on the local lane;
     *  - [RetrievalPhase.NETWORK] once the cache read has already missed (recorded via [markChecked]).
     */
    fun planNetworkBound(key: K, isAbsent: Boolean) = when {
        isAbsent || remoteRetrievals.contains(key) -> RetrievalPhase.NONE
        !cacheChecked.contains(key) -> RetrievalPhase.CACHE
        else -> RetrievalPhase.NETWORK
    }

    fun markChecked(key: K) { cacheChecked += key }
    fun unmarkChecked(key: K) { cacheChecked -= key }

    fun clear() {
        remoteRetrievals.clear()
        localRetrievals.clear()
        cacheChecked.clear()
    }
}
