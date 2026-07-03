package earth.worldwind.layer.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/** Outcome of a single bulk tile fetch — drives the `(downloaded, skipped, total)` progress tally. */
internal enum class TileOutcome {
    /** Tile was fetched (or already cached) and written through. */
    DOWNLOADED,
    /** Nothing to fetch — 404 / empty payload / no network source. Never retried. */
    SKIPPED,
}

/**
 * Per-tile retry/backoff shared by the bulk-retrieval loops. Retries [fetchOne] up to [maxRetries]
 * times, backing off [retryTimeoutShort]/[retryTimeoutLong] only on a *thrown* transient error; a
 * returned [TileOutcome] is final (a [TileOutcome.SKIPPED] is a permanent 404/empty/no-source miss).
 * Exhausted retries count as SKIPPED; cancellation is checked per attempt and propagated.
 */
internal suspend fun retryTile(
    maxRetries: Int,
    retryTimeoutShort: Duration,
    retryTimeoutLong: Duration,
    fetchOne: suspend () -> TileOutcome,
): TileOutcome {
    var attempt = 0
    while (attempt < maxRetries) {
        coroutineContext.ensureActive()
        attempt++
        try {
            return fetchOne()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            delay(if (attempt % 2 == 0) retryTimeoutLong else retryTimeoutShort)
        }
    }
    return TileOutcome.SKIPPED
}

/** Running `(downloaded, skipped)` tally against a fixed [total], firing [onProgress] after each tile. */
internal class BulkProgress(
    private val total: Long,
    private val onProgress: ((downloaded: Long, skipped: Long, total: Long) -> Unit)?,
) {
    private var downloaded = 0L
    private var skipped = 0L

    fun record(outcome: TileOutcome) {
        if (outcome == TileOutcome.DOWNLOADED) onProgress?.invoke(++downloaded, skipped, total)
        else onProgress?.invoke(downloaded, ++skipped, total)
    }
}
