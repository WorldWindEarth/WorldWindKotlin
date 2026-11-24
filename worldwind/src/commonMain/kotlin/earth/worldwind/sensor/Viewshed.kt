package earth.worldwind.sensor

import earth.worldwind.geom.Position
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * What a sensor sees over the elevation data, independent of any map's view: a polar grid of
 * [columns] azimuth columns across the frame and [bins] range bins out to the sensor's range,
 * [seen] where the ground is inside the frame and not hidden by nearer ground, and [regions],
 * the closed rings around each seen region, in geographic coordinates for drawing on any map.
 *
 * The result of [ViewshedSolver.sweep]; the same input gives the same viewshed on every device.
 */
class Viewshed(
    /** The pose it was computed for, a copy. */
    val sensor: Sensor,
    val columns: Int,
    val bins: Int,
    /** Azimuth of the first column's left edge, radians clockwise from north, and a column's width. */
    val startAzimuth: Double,
    val columnAngle: Double,
    /** Ground distance of one range bin, metres: the elevation posts' spacing, never coarser. */
    val binLength: Double,
    /** `columns × bins`, column major: `seen[column * bins + bin]`. */
    val seen: BooleanArray,
    /** Closed rings around the seen regions, altitude 0, one per region; regions smaller than a cell are dropped. */
    val regions: List<List<Position>>,
    /** False while an elevation tile under the sweep was still loading. */
    val isComplete: Boolean,
    /** The tile cache generation the elevations were read at. */
    val generation: Long,
)

/**
 * A sweep ready to run: the sensor's pose and the elevation tiles under its range, snapshotted on
 * the thread that owns the cache, so [ViewshedSolver.sweep] can run on any other.
 */
class ViewshedInput internal constructor(
    val sensor: Sensor,
    internal val tiles: Map<Long, DemTile>,
    internal val rowOf: (Double) -> Int,
    internal val columnOf: (Double) -> Int,
    /** Elevation post spacing in metres, what the steps are sized to. */
    val cellMeters: Double,
    /** Radius of the Earth sphere the sweep runs on, metres: the ellipsoid's mean radius at the sensor. */
    val radius: Double,
    val columns: Int,
    val bins: Int,
    val startAzimuth: Double,
    val columnAngle: Double,
    val binLength: Double,
    val isComplete: Boolean,
    val generation: Long,
)

/**
 * The viewshed of a sensor over the elevation data: a radial sweep per azimuth column with a
 * running maximum elevation angle, the way GIS viewsheds and azimuth-elevation masks are done,
 * then marching squares over the seen grid for the boundary of every seen region.
 *
 * Steps are sized to the data: the range bin is one elevation post, and the columns are as many
 * as it takes for the arc between two of them at full range to be one post too, so nothing is
 * coarser than a cell. The sweep runs on a sphere of the ellipsoid's mean radius at the sensor,
 * and the ground's drop with the Earth's curvature is in from the first metre, which matters
 * from a few kilometres out. There is no tolerance: a cell is seen when its elevation angle
 * reaches the running maximum, and the odd sample straddling a crest leaves a hole or a sliver
 * smaller than a cell, which the contours drop.
 *
 * [prepare] snapshots the tiles on the cache's thread; [sweep] is pure and may run anywhere.
 */
class ViewshedSolver(val tiles: DemTileCache) {
    /** Most columns and bins a sweep takes; a wide frame at a long range is coarser than a cell beyond this. */
    var maxColumns = 4096
    var maxBins = 8192

    /** Main thread: sizes the sweep and snapshots the tiles under the sensor's range disc, requesting the missing ones. */
    fun prepare(sensor: Sensor): ViewshedInput {
        val copy = Sensor(
            Position(sensor.position), sensor.azimuth, sensor.depression, sensor.horizontalFov, sensor.verticalFov,
            sensor.maxRange, sensor.roll,
        )
        val lat0 = copy.position.latitude.inDegrees
        val lon0 = copy.position.longitude.inDegrees
        val cell = tiles.cellDegrees * DemTileCache.METERS_PER_DEGREE
        val radius = meanRadius(lat0 * DEG)
        val range = max(copy.maxRange, cell)
        // The columns span the frame's ground azimuths, the whole circle when the frame holds the nadir
        val (start, span) = azimuthSpan(copy, cell / range)
        val columns = ceil(span * range / cell).toInt().coerceIn(MIN_COLUMNS, maxColumns)
        val bins = ceil(range / cell).toInt().coerceIn(MIN_BINS, maxBins)
        // Every tile the range disc can touch, loaded or asked for
        val radiusDegrees = range / DemTileCache.METERS_PER_DEGREE
        val lonRadius = radiusDegrees / max(cos(lat0 * DEG), 1e-3)
        val snapshot = HashMap<Long, DemTile>()
        var complete = true
        for (r in tiles.rowOf(lat0 - radiusDegrees)..tiles.rowOf(lat0 + radiusDegrees)) {
            for (c in tiles.columnOf(lon0 - lonRadius)..tiles.columnOf(lon0 + lonRadius)) {
                val tile = tiles.tile(r, c)
                if (tile == null) complete = false else snapshot[key(r, c)] = tile
            }
        }
        return ViewshedInput(
            copy, snapshot, tiles::rowOf, tiles::columnOf, cell, radius,
            columns, bins, start, span / columns, range / bins, complete, tiles.generation,
        )
    }

    /** Any thread: the sweep and the contours. */
    fun sweep(input: ViewshedInput): Viewshed {
        val sensor = input.sensor
        val lat0 = sensor.position.latitude.inDegrees * DEG
        val lon0 = sensor.position.longitude.inDegrees * DEG
        val alt0 = sensor.position.altitude
        val sinLat0 = sin(lat0)
        val cosLat0 = cos(lat0)
        val f = DoubleArray(3); val r = DoubleArray(3); val u = DoubleArray(3)
        sensorFrameAxes(sensor.azimuth.inRadians, sensor.depression.inRadians, sensor.roll.inRadians, f, r, u)
        val tanH = tan(sensor.horizontalFov.inRadians / 2)
        val tanV = tan(sensor.verticalFov.inRadians / 2)
        val range = sensor.maxRange
        val radius = input.radius
        val columns = input.columns
        val bins = input.bins
        val seen = BooleanArray(columns * bins)
        var complete = input.isComplete
        for (i in 0 until columns) {
            val azimuth = input.startAzimuth + (i + 0.5) * input.columnAngle
            val sinAz = sin(azimuth)
            val cosAz = cos(azimuth)
            var horizon = -PI / 2
            for (j in 0 until bins) {
                val s = (j + 0.5) * input.binLength
                // The ground point: a great circle on the sphere, then its post height
                val sigma = s / radius
                val sinSigma = sin(sigma)
                val cosSigma = cos(sigma)
                val lat = asin(sinLat0 * cosSigma + cosLat0 * sinSigma * cosAz)
                val lon = lon0 + atan2(sinAz * sinSigma * cosLat0, cosSigma - sinLat0 * sin(lat))
                val height = elevation(input, lat / DEG, lon / DEG)
                if (height == null) { complete = false; continue }
                // Height over the sensor's horizontal plane, the ground dropping away with the curvature
                val dz = height - alt0 - s * s / (2.0 * radius)
                val angle = atan2(dz, s)
                val visible = angle >= horizon
                if (visible) horizon = angle
                if (!visible) continue
                // Inside the frame: ahead of the sensor, within its angles of view and its range
                val e = s * sinAz; val n = s * cosAz
                val w = e * f[0] + n * f[1] + dz * f[2]
                if (w <= 0.0) continue
                val x = (e * r[0] + n * r[1] + dz * r[2]) / (w * tanH)
                val y = (e * u[0] + n * u[1] + dz * u[2]) / (w * tanV)
                if (abs(x) > 1.0 || abs(y) > 1.0) continue
                if (sqrt(s * s + dz * dz) > range) continue
                seen[i * bins + j] = true
            }
        }
        val regions = contours(seen, columns, bins).map { ring ->
            ring.map { (ci, bj) ->
                val azimuth = input.startAzimuth + ci * input.columnAngle
                val sigma = max(bj, 0.0) * input.binLength / radius
                val sinSigma = sin(sigma)
                val cosSigma = cos(sigma)
                val lat = asin(sinLat0 * cosSigma + cosLat0 * sinSigma * cos(azimuth))
                val lon = lon0 + atan2(sin(azimuth) * sinSigma * cosLat0, cosSigma - sinLat0 * sin(lat))
                Position.fromRadians(lat, lon, 0.0)
            }
        }
        return Viewshed(
            sensor, columns, bins, input.startAzimuth, input.columnAngle, input.binLength, seen, regions, complete,
            input.generation,
        )
    }

    fun solve(sensor: Sensor) = sweep(prepare(sensor))

    /** Bilinear post height from the snapshot, null where the tile is not there. */
    private fun elevation(input: ViewshedInput, latitudeDegrees: Double, longitudeDegrees: Double): Double? {
        val tile = input.tiles[key(input.rowOf(latitudeDegrees), input.columnOf(longitudeDegrees))] ?: return null
        val cell = tile.cellDegrees
        val uu = ((longitudeDegrees - tile.minLongitude) / cell).coerceIn(0.0, tile.cells.toDouble())
        val vv = ((latitudeDegrees - tile.minLatitude) / cell).coerceIn(0.0, tile.cells.toDouble())
        val c = min(uu.toInt(), tile.cells - 1)
        val rr = min(vv.toInt(), tile.cells - 1)
        val fu = uu - c
        val fv = vv - rr
        val h00 = tile.post(rr, c); val h01 = tile.post(rr, c + 1)
        val h10 = tile.post(rr + 1, c); val h11 = tile.post(rr + 1, c + 1)
        return ((h00 * (1 - fu) + h01 * fu) * (1 - fv) + (h10 * (1 - fu) + h11 * fu) * fv).toDouble()
    }

    /** The ellipsoid's mean radius of curvature at a latitude, metres. */
    private fun meanRadius(latitude: Double): Double {
        val ellipsoid = tiles.globe.ellipsoid
        val e2 = ellipsoid.eccentricitySquared
        val s = sin(latitude)
        val w = 1.0 - e2 * s * s
        val primeVertical = ellipsoid.semiMajorAxis / sqrt(w)
        val meridian = ellipsoid.semiMajorAxis * (1.0 - e2) / (w * sqrt(w))
        return sqrt(primeVertical * meridian)
    }

    /**
     * The azimuths the frame's ground can have, as a start and a span in radians: the full circle
     * when the frame holds the nadir, else the widest offset of the frame's boundary from the
     * sensor's azimuth, both ways, plus a cell's angle of margin.
     */
    private fun azimuthSpan(sensor: Sensor, marginAngle: Double): Pair<Double, Double> {
        val f = DoubleArray(3); val r = DoubleArray(3); val u = DoubleArray(3)
        sensorFrameAxes(sensor.azimuth.inRadians, sensor.depression.inRadians, sensor.roll.inRadians, f, r, u)
        val tanH = tan(sensor.horizontalFov.inRadians / 2)
        val tanV = tan(sensor.verticalFov.inRadians / 2)
        // The nadir, straight down, in the frame
        val wn = -f[2]
        if (wn > 0.0 && abs(-r[2] / (wn * tanH)) <= 1.0 && abs(-u[2] / (wn * tanV)) <= 1.0) {
            return (sensor.azimuth.inRadians - PI) to 2 * PI
        }
        var widest = 0.0
        val az0 = sensor.azimuth.inRadians
        for (k in 0..BOUNDARY_SAMPLES * 4) {
            val t = (k % BOUNDARY_SAMPLES).toDouble() / BOUNDARY_SAMPLES * 2.0 - 1.0
            val (x, y) = when (k / BOUNDARY_SAMPLES) { 0 -> t to -1.0; 1 -> 1.0 to t; 2 -> t to 1.0; else -> -1.0 to t }
            val e = f[0] + x * tanH * r[0] + y * tanV * u[0]
            val n = f[1] + x * tanH * r[1] + y * tanV * u[1]
            if (e == 0.0 && n == 0.0) continue
            var offset = atan2(e, n) - az0
            while (offset > PI) offset -= 2 * PI
            while (offset < -PI) offset += 2 * PI
            widest = max(widest, abs(offset))
        }
        val half = min(widest + marginAngle, PI)
        return (az0 - half) to 2 * half
    }

    companion object {
        private const val DEG = PI / 180.0
        private const val MIN_COLUMNS = 8
        private const val MIN_BINS = 4
        private const val BOUNDARY_SAMPLES = 32

        internal fun key(row: Int, column: Int) = row.toLong() shl 32 or (column.toLong() and 0xFFFFFFFFL)

        /**
         * Marching squares over the seen grid, padded with a ring of unseen cells so every contour
         * closes: rings of `(column, bin)` coordinates where a cell's centre is `(i + 0.5, j + 0.5)`,
         * each crossing midway between two cells. Rings whose area is under one cell are dropped.
         */
        internal fun contours(seen: BooleanArray, columns: Int, bins: Int): List<List<Pair<Double, Double>>> {
            val w = columns + 2
            val h = bins + 2
            fun at(i: Int, j: Int) = i in 1..columns && j in 1..bins && seen[(i - 1) * bins + (j - 1)]
            // Segments between edge midpoints; an edge is keyed by its lower-left sample and direction
            fun hEdge(i: Int, j: Int) = (j * w + i) * 2L        // between (i, j) and (i + 1, j)
            fun vEdge(i: Int, j: Int) = (j * w + i) * 2L + 1    // between (i, j) and (i, j + 1)
            val links = HashMap<Long, LongArray>()
            fun link(a: Long, b: Long) {
                for (e in longArrayOf(a, b)) {
                    val other = if (e == a) b else a
                    val arr = links[e]
                    links[e] = if (arr == null) longArrayOf(other) else longArrayOf(arr[0], other)
                }
            }
            for (j in 0 until h - 1) for (i in 0 until w - 1) {
                val c = (if (at(i, j)) 1 else 0) or (if (at(i + 1, j)) 2 else 0) or
                    (if (at(i + 1, j + 1)) 4 else 0) or (if (at(i, j + 1)) 8 else 0)
                val bottom = hEdge(i, j); val right = vEdge(i + 1, j); val top = hEdge(i, j + 1); val left = vEdge(i, j)
                when (c) {
                    1, 14 -> link(left, bottom)
                    2, 13 -> link(bottom, right)
                    3, 12 -> link(left, right)
                    4, 11 -> link(right, top)
                    6, 9 -> link(bottom, top)
                    7, 8 -> link(left, top)
                    5 -> { link(left, top); link(bottom, right) }
                    10 -> { link(left, bottom); link(right, top) }
                }
            }
            fun point(edge: Long): Pair<Double, Double> {
                val vertical = edge and 1L == 1L
                val index = (edge shr 1).toInt()
                val i = index % w
                val j = index / w
                // Sample (i, j) sits at cell centre (i - 1 + 0.5, j - 1 + 0.5)
                val ci = i - 0.5
                val bj = j - 0.5
                return if (vertical) ci to bj + 0.5 else ci + 0.5 to bj
            }
            val rings = mutableListOf<List<Pair<Double, Double>>>()
            val visited = HashSet<Long>()
            for (start in links.keys) {
                if (start in visited) continue
                val ring = mutableListOf<Pair<Double, Double>>()
                var previous = -1L
                var current = start
                while (true) {
                    visited += current
                    ring += point(current)
                    val next = links[current] ?: break
                    if (next.size < 2) break
                    val candidate = if (next[0] == previous) next[1] else next[0]
                    previous = current
                    current = candidate
                    if (current == start || current in visited) break
                }
                if (ring.size >= 3 && abs(area(ring)) >= 1.0) rings += ring
            }
            return rings
        }

        private fun area(ring: List<Pair<Double, Double>>): Double {
            var sum = 0.0
            for (k in ring.indices) {
                val (x0, y0) = ring[k]
                val (x1, y1) = ring[(k + 1) % ring.size]
                sum += x0 * y1 - x1 * y0
            }
            return 0.5 * sum
        }
    }
}
