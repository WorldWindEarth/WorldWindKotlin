package earth.worldwind.formats.las

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Time-budgeted cooperative yielder for long parse/build loops. [tick] hands control back to the
 * event loop once [budget] of work has elapsed, capping each slice to ~one frame so single-threaded
 * targets (JS/wasm) stay interactive mid-load instead of freezing. Yields via [delay] — a macrotask
 * that lets the browser paint; `yield()`/`delay(0)` don't. Cheap below budget, so [tick] often.
 */
internal class RenderYield(private val budget: Duration = 12.milliseconds) {
    private var mark = TimeSource.Monotonic.markNow()

    suspend fun tick() {
        if (mark.elapsedNow() >= budget) {
            delay(1)
            mark = TimeSource.Monotonic.markNow()
        }
    }
}
