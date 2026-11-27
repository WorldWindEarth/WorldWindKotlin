package earth.worldwind.sensor

import earth.worldwind.globe.Globe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.time.TimeSource

/**
 * Keeps a [Viewshed] for every sensor added, swept on [worker] off the thread that owns the
 * tiles and the maps, as [SensorFootprintEngine] keeps footprints. [update] once per frame on
 * [scope]'s thread: a sensor is swept again when it has no viewshed, when its tiles changed,
 * or when its pose moved by more than the elevation data resolves, half a cell in position or
 * a cell's angle at its range in aim. One sweep in flight per sensor; a later pose waits for it
 * and then wins. Results come back on [scope]'s thread, through the listener given with [add].
 */
class ViewshedEngine(
    /** The elevation tiles to sweep over, shared with a footprint solver when there is one. */
    val tiles: DemTileCache,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) {
    constructor(
        globe: Globe, scope: CoroutineScope, resolutionMeters: Double = 30.0,
        worker: CoroutineDispatcher = Dispatchers.Default,
    ) : this(DemTileCache(globe, scope, resolutionMeters), scope, worker)

    val solver = ViewshedSolver(tiles)
    /** Of the last sweep that finished, for the measurements. */
    var lastSweepMillis = 0.0
        private set
    var sweepsPerSecond = 0
        private set
    val sensorCount get() = entries.size

    private class Entry(val sensor: Sensor, val listener: (Viewshed) -> Unit) {
        var viewshed: Viewshed? = null
        var job: Job? = null
        var generation = -1L
        val pose = DoubleArray(8)
    }

    private val entries = mutableListOf<Entry>()
    private val clock = TimeSource.Monotonic.markNow()
    private var windowStart = 0L
    private var windowSweeps = 0

    fun add(sensor: Sensor, listener: (Viewshed) -> Unit) {
        entries += Entry(sensor, listener)
    }

    fun remove(sensor: Sensor) {
        for (entry in entries) if (entry.sensor === sensor) entry.job?.cancel()
        entries.removeAll { it.sensor === sensor }
    }

    fun viewshed(sensor: Sensor) = entries.firstOrNull { it.sensor === sensor }?.viewshed

    /** Forgets every sensor; the tiles stay, they may be another's too. */
    fun clear() {
        for (entry in entries) entry.job?.cancel()
        entries.clear()
    }

    /** Once per frame on [scope]'s thread. Returns true while a sweep is running or a tile is loading. */
    fun update(): Boolean {
        tiles.refreshChanged()
        val generation = tiles.generation
        var busy = tiles.pendingFetches > 0
        for (entry in entries) {
            if (entry.job != null) { busy = true; continue }
            if (!needsSweep(entry, generation)) continue
            val input = solver.prepare(entry.sensor)
            recordPose(entry)
            entry.generation = generation
            entry.job = scope.launch {
                try {
                    val started = TimeSource.Monotonic.markNow()
                    val viewshed = withContext(worker) { solver.sweep(input) }
                    lastSweepMillis = started.elapsedNow().inWholeMicroseconds / 1000.0
                    account()
                    entry.viewshed = viewshed
                    entry.listener(viewshed)
                } finally {
                    entry.job = null
                }
            }
            busy = true
        }
        return busy
    }

    private fun needsSweep(entry: Entry, generation: Long): Boolean {
        val viewshed = entry.viewshed ?: return true
        if (entry.generation != generation || !viewshed.isComplete) return true
        val sensor = entry.sensor
        val pose = entry.pose
        val cell = tiles.cellDegrees * DemTileCache.METERS_PER_DEGREE
        val range = max(sensor.maxRange, cell)
        val angleThreshold = atan(cell / range)
        val lat = sensor.position.latitude.inDegrees
        val dLat = (lat - pose[0]) * DemTileCache.METERS_PER_DEGREE
        val dLon = (sensor.position.longitude.inDegrees - pose[1]) * DemTileCache.METERS_PER_DEGREE * cos(lat * PI / 180.0)
        if (dLat * dLat + dLon * dLon > 0.25 * cell * cell) return true
        if (abs(sensor.position.altitude - pose[2]) > 0.5 * cell) return true
        if (abs(wrap(sensor.azimuth.inRadians - pose[3])) > angleThreshold) return true
        if (abs(sensor.depression.inRadians - pose[4]) > angleThreshold) return true
        if (abs(wrap(sensor.roll.inRadians - pose[5])) > angleThreshold) return true
        if (sensor.horizontalFov.inRadians != pose[6] || sensor.verticalFov.inRadians != pose[7]) return true
        return sensor.maxRange != viewshed.sensor.maxRange
    }

    private fun recordPose(entry: Entry) {
        val sensor = entry.sensor
        val pose = entry.pose
        pose[0] = sensor.position.latitude.inDegrees
        pose[1] = sensor.position.longitude.inDegrees
        pose[2] = sensor.position.altitude
        pose[3] = sensor.azimuth.inRadians
        pose[4] = sensor.depression.inRadians
        pose[5] = sensor.roll.inRadians
        pose[6] = sensor.horizontalFov.inRadians
        pose[7] = sensor.verticalFov.inRadians
    }

    private fun account() {
        val now = clock.elapsedNow().inWholeNanoseconds
        if (now - windowStart >= 1_000_000_000L) {
            sweepsPerSecond = windowSweeps
            windowSweeps = 0
            windowStart = now
        }
        windowSweeps++
    }

    private companion object {
        fun wrap(angle: Double): Double {
            var a = angle
            while (a > PI) a -= 2 * PI
            while (a < -PI) a += 2 * PI
            return a
        }
    }
}
