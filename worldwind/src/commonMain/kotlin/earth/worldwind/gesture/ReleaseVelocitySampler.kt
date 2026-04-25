package earth.worldwind.gesture

/**
 * Samples a drag's instantaneous velocity into a small ring buffer and, on release, computes the
 * average velocity over the last [windowMs] of motion. Better than EMA for fling: users tend to
 * decelerate slightly just before lifting, and the EMA smears that deceleration into the reported
 * release velocity. Averaging over a short trailing window captures the actual flick speed.
 *
 * Units of dx/dy/dt are caller-defined — typically (pixels, pixels, milliseconds), giving a
 * release velocity in pixels per millisecond.
 */
class ReleaseVelocitySampler(
    private val capacity: Int = 5,
    private val windowMs: Double = 100.0,
) {
    private val dx = DoubleArray(capacity)
    private val dy = DoubleArray(capacity)
    private val dt = DoubleArray(capacity)
    private var head = 0
    private var size = 0

    /** Discards all samples — call when a new gesture begins. */
    fun reset() { head = 0; size = 0 }

    /** Records one motion segment. Zero or negative [dtMs] is silently ignored. */
    fun record(deltaPxX: Double, deltaPxY: Double, dtMs: Double) {
        if (dtMs <= 0.0) return
        dx[head] = deltaPxX
        dy[head] = deltaPxY
        dt[head] = dtMs
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /**
     * Returns the average (vx, vy) over samples covering up to [windowMs] of motion ending at the
     * most recent record. Walks newest-to-oldest; stops as soon as the accumulated dt reaches
     * [windowMs]. Returns (0.0, 0.0) if no samples have been recorded.
     */
    fun computeReleaseVelocity(): Pair<Double, Double> {
        if (size == 0) return 0.0 to 0.0
        var sumDx = 0.0; var sumDy = 0.0; var sumDt = 0.0
        var idx = (head - 1 + capacity) % capacity
        var taken = 0
        while (taken < size) {
            sumDx += dx[idx]
            sumDy += dy[idx]
            sumDt += dt[idx]
            taken++
            if (sumDt >= windowMs) break
            idx = (idx - 1 + capacity) % capacity
        }
        if (sumDt <= 0.0) return 0.0 to 0.0
        return (sumDx / sumDt) to (sumDy / sumDt)
    }
}
