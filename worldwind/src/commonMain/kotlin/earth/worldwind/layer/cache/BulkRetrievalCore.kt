package earth.worldwind.layer.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Outcome of a single bulk tile fetch — drives the `(downloaded, skipped, total)` progress tally. */
internal enum class TileOutcome {
    /** Tile was fetched (or already cached) and written through. */
    DOWNLOADED,
    /** Nothing to fetch — 404 / empty payload / no network source. Never retried. */
    SKIPPED,
}

/**
 * Per-tile retry/backoff shared by the bulk-retrieval loops. A *thrown* error is transient by
 * contract (no connectivity, server hiccup, hung fetch — every source reserves the returned
 * [TileOutcome.SKIPPED] for permanent 404/empty/no-source misses), so the loop retries it
 * FOREVER: an offline gap parks the bulk download until the network returns instead of skipping
 * tiles, matching the pre-2.0 `makeLocal` behavior. Only a returned [TileOutcome] ends the tile,
 * and cancelling the bulk job is always honored (checked per attempt and during backoff).
 *
 * [maxRetries] shapes the backoff, not a give-up point: the first attempts alternate
 * [retryTimeoutShort]/[retryTimeoutLong]; past [maxRetries] the loop settles into polling at
 * [retryTimeoutLong] so a long outage isn't hammered with short retries.
 *
 * Each attempt is capped by [tileTimeout] on the CALLER's dispatcher. This is the loop's hang
 * backstop: the HTTP layer's own request timeout is enforced by a watchdog coroutine on
 * `Dispatchers.IO`, so when IO is saturated (heavy concurrent browsing) a fetch can neither
 * complete nor time out — this cap converts that hang into a normal backoff-and-retry.
 */
internal suspend fun retryTile(
    maxRetries: Int,
    retryTimeoutShort: Duration,
    retryTimeoutLong: Duration,
    tileTimeout: Duration = 2.minutes,
    fetchOne: suspend () -> TileOutcome,
): TileOutcome {
    var attempt = 0
    while (true) {
        coroutineContext.ensureActive()
        attempt++
        try {
            return withTimeout(tileTimeout) { fetchOne() }
        } catch (timeout: TimeoutCancellationException) {
            // Order matters: TimeoutCancellationException IS a CancellationException, but ours means
            // "this attempt hung" — transient, so back off and retry. Real outer-scope cancellation
            // still propagates via ensureActive() / delay() on the next iteration.
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
        }
        delay(if (attempt >= maxRetries || attempt % 2 == 0) retryTimeoutLong else retryTimeoutShort)
    }
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
