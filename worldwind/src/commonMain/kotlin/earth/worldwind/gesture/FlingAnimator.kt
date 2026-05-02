package earth.worldwind.gesture

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Drives a per-frame action with monotonically decaying velocity until the velocity drops below a
 * threshold. The platform supplies a [FrameScheduler] that fires a tick on each render frame
 * (Choreographer / requestAnimationFrame / Swing Timer); each tick computes its own elapsed time
 * so the animation stays correct even when the scheduler's interval drifts.
 *
 * Used for inertial pan ("fling") after a drag/pan gesture. Distance integrates to `v0 / k`
 * (linear in initial velocity) and duration is `ln(v0 / vMin) / k` (logarithmic) — so harder
 * flicks travel proportionally further while still settling in roughly the same wall-clock time.
 */
class FlingAnimator(
    private val scheduler: FrameScheduler,
    /** Applies one frame's worth of pan in screen-pixel units. */
    private val applyPanDelta: (deltaPxX: Double, deltaPxY: Double) -> Unit,
    /** Optional hook called when the animator transitions active <-> inactive. Use to bookkeep
     *  any "virtual gesture" counters so a fresh real gesture cleanly cancels the fling. */
    private val onActiveChange: (active: Boolean) -> Unit = {},
) {
    /** Velocities below this magnitude (in caller's units per millisecond) are treated as stopped. */
    var minVelocity: Double = 0.2

    /** Exponential decay rate per second. With `decayPerSecond = 3.0` velocity halves every ~230 ms. */
    var decayPerSecond: Double = 3.0

    var isActive = false
        private set
    private var vx = 0.0
    private var vy = 0.0

    /** Begins a fling at the given velocity. A no-op if the speed is below [minVelocity]. */
    fun start(vx0: Double, vy0: Double) {
        cancel()
        // Non-finite check is explicit: `sqrt(NaN) < minVelocity` is false, so without it a NaN
        // seed would zombify the fling (tick keeps firing, never auto-cancels).
        if (!vx0.isFinite() || !vy0.isFinite() || sqrt(vx0 * vx0 + vy0 * vy0) < minVelocity) return
        vx = vx0
        vy = vy0
        isActive = true
        onActiveChange(true)
        scheduler.start { dtMs -> tick(dtMs) }
    }

    /** Cancels an in-progress fling. Safe to call when not active. */
    fun cancel() {
        if (!isActive) return
        isActive = false
        scheduler.stop()
        onActiveChange(false)
    }

    private fun tick(dtMs: Double) {
        if (!isActive || dtMs <= 0.0) return
        applyPanDelta(vx * dtMs, vy * dtMs)
        val decay = exp(-decayPerSecond * dtMs / 1000.0)
        vx *= decay
        vy *= decay
        if (sqrt(vx * vx + vy * vy) < minVelocity) cancel()
    }
}

/**
 * Per-platform vsync-aligned (or close-enough) frame scheduler. Implementations are expected to
 * fire [start]'s callback repeatedly until [stop] is called, with `dtMs` being the elapsed
 * milliseconds since the previous tick (or since [start] for the first tick). The `dtMs` should
 * be capped to a reasonable upper bound (e.g. 64 ms) so a missed frame can't teleport the camera.
 */
interface FrameScheduler {
    /** Begins firing [tick] every render frame until [stop] is called. */
    fun start(tick: (dtMs: Double) -> Unit)

    /** Stops firing. Safe to call when not started. */
    fun stop()
}
