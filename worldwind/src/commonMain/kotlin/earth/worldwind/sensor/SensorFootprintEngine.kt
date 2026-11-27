package earth.worldwind.sensor

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.globe.Globe
import kotlinx.coroutines.CoroutineScope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Keeps the ground footprints of many sensors up to date within a fixed share of one thread.
 *
 * Call [update] once a frame on the render thread. It solves the footprints that are due, the
 * ones never solved first and then the longest waiting, until [frameBudget] is spent, and hands
 * each new [Footprint] to its sensor's listener. A footprint is due when its sensor has moved or
 * turned past [positionThreshold] or [angleThreshold] (or changed its optics) and at least
 * [minUpdateInterval] has passed since it was last solved, or when elevations it may have used
 * have been loaded or refined since. More sensors than the budget can serve are served less often,
 * never with more time: the thread's load stays at about `frameBudget × frame rate`.
 *
 * Elevations come from a [DemTileCache] shared by every sensor, read in the background. Nothing
 * any map has drawn is used, so a footprint does not depend on where a map looks or how far it is
 * zoomed.
 *
 * @param scope where tile reads are launched from and published on: the render thread's scope,
 * such as `RenderResourceCache.mainScope`
 * @param resolutionMeters post spacing of the elevation tiles, sized to the elevation source
 */
class SensorFootprintEngine(
    globe: Globe,
    scope: CoroutineScope,
    resolutionMeters: Double = 30.0,
) {
    val tiles = DemTileCache(globe, scope, resolutionMeters)
    val solver = FootprintSolver(tiles)

    /**
     * Most time [update] spends solving in one call. At 60 frames a second the default 1 ms is
     * about 6% of the render thread, whatever the number of sensors.
     */
    var frameBudget: Duration = 1.milliseconds
    /** Least time between two solves of one sensor's footprint. */
    var minUpdateInterval: Duration = 200.milliseconds
    /** Sensor movement, metres, below which its footprint is left as it is. */
    var positionThreshold = 0.5
    /** Sensor turn below which its footprint is left as it is. */
    var angleThreshold: Angle = 0.1.degrees

    /** Share of wall time [update] spent over the last whole second, 0 to 1. */
    var load = 0.0
        private set
    /** Footprints solved over the last whole second. */
    var solvesPerSecond = 0
        private set
    /** Rays cast over the last whole second. */
    var raysPerSecond = 0
        private set

    val sensorCount get() = entries.size

    private class Entry(val sensor: Sensor, val listener: (Footprint) -> Unit) {
        var footprint: Footprint? = null
        var solvedAt = Long.MIN_VALUE
        var generation = -1L
        val pose = DoubleArray(9)
    }

    private val entries = mutableListOf<Entry>()
    private val due = mutableListOf<Entry>()
    private val clock = TimeSource.Monotonic.markNow()
    private var windowStart = 0L
    private var windowBusy = 0L
    private var windowSolves = 0
    private var windowRays = 0

    /** Starts keeping [sensor]'s footprint, handing every new one to [listener] on the render thread. */
    fun add(sensor: Sensor, listener: (Footprint) -> Unit) {
        entries += Entry(sensor, listener)
    }

    fun remove(sensor: Sensor) {
        entries.removeAll { it.sensor === sensor }
    }

    /** The last footprint solved for [sensor], if any. */
    fun footprint(sensor: Sensor) = entries.firstOrNull { it.sensor === sensor }?.footprint

    /** Stops every sensor and drops the loaded elevations. */
    fun clear() {
        entries.clear()
        tiles.clear()
    }

    /**
     * Solves the footprints that are due, within [frameBudget]. Returns true while work remains:
     * footprints due but not reached, waiting out [minUpdateInterval], or elevations loading.
     */
    fun update(): Boolean {
        val start = now()
        tiles.refreshChanged()
        val generation = tiles.generation
        val interval = minUpdateInterval.inWholeNanoseconds
        var waiting = false
        due.clear()
        for (entry in entries) {
            if (!needsSolve(entry, generation)) continue
            if (entry.solvedAt != Long.MIN_VALUE && start - entry.solvedAt < interval) waiting = true
            else due += entry
        }
        due.sortBy { it.solvedAt }
        val budget = frameBudget.inWholeNanoseconds
        var solved = 0
        for (entry in due) {
            if (now() - start >= budget) break
            solve(entry, generation)
            solved++
        }
        account(start, now())
        return waiting || solved < due.size || tiles.pendingFetches > 0
    }

    private fun solve(entry: Entry, generation: Long) {
        val footprint = solver.solve(entry.sensor)
        entry.footprint = footprint
        entry.solvedAt = now()
        entry.generation = generation
        recordPose(entry)
        windowSolves++
        windowRays += footprint.rayCount
        entry.listener(footprint)
    }

    private fun needsSolve(entry: Entry, generation: Long): Boolean {
        if (entry.footprint == null || entry.generation != generation) return true
        val s = entry.sensor
        val p = entry.pose
        val lat = s.position.latitude.inDegrees
        val dy = (lat - p[0]) * DemTileCache.METERS_PER_DEGREE
        val dx = angleDelta(s.position.longitude.inDegrees, p[1]) * DemTileCache.METERS_PER_DEGREE * cos(lat * PI / 180.0)
        val dz = s.position.altitude - p[2]
        if (sqrt(dx * dx + dy * dy + dz * dz) > positionThreshold) return true
        val turn = angleThreshold.inDegrees
        return abs(angleDelta(s.azimuth.inDegrees, p[3])) > turn ||
            abs(s.depression.inDegrees - p[4]) > turn ||
            abs(angleDelta(s.roll.inDegrees, p[5])) > turn ||
            s.horizontalFov.inDegrees != p[6] || s.verticalFov.inDegrees != p[7] || s.maxRange != p[8]
    }

    private fun recordPose(entry: Entry) {
        val s = entry.sensor
        val p = entry.pose
        p[0] = s.position.latitude.inDegrees
        p[1] = s.position.longitude.inDegrees
        p[2] = s.position.altitude
        p[3] = s.azimuth.inDegrees
        p[4] = s.depression.inDegrees
        p[5] = s.roll.inDegrees
        p[6] = s.horizontalFov.inDegrees
        p[7] = s.verticalFov.inDegrees
        p[8] = s.maxRange
    }

    private fun account(start: Long, end: Long) {
        windowBusy += end - start
        val window = end - windowStart
        if (window >= WINDOW) {
            load = windowBusy.toDouble() / window
            solvesPerSecond = (windowSolves * 1e9 / window).toInt()
            raysPerSecond = (windowRays * 1e9 / window).toInt()
            windowStart = end
            windowBusy = 0
            windowSolves = 0
            windowRays = 0
        }
    }

    private fun now() = clock.elapsedNow().inWholeNanoseconds

    private companion object {
        val WINDOW = 1.seconds.inWholeNanoseconds

        /** Smallest signed difference between two angles in degrees. */
        fun angleDelta(a: Double, b: Double): Double {
            var d = (a - b) % 360.0
            if (d > 180.0) d -= 360.0 else if (d < -180.0) d += 360.0
            return d
        }
    }
}
