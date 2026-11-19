package earth.worldwind.sensor

import earth.worldwind.geom.Angle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where a ray met the ground, or where it was given up.
 */
class RayHit {
    /** True when the ray met the ground; false when it reached its range or turned to the sky first. */
    var isHit = false
    /** False when part of the ray crossed ground whose elevations were not loaded yet. */
    var isComplete = true
    /** Distance along the ground from the point under the ray's origin, metres. */
    var distance = 0.0
    var latitude = 0.0
    var longitude = 0.0
    /** Height above the ellipsoid of the hit, or of the ground at the range when missed (0 if unknown). */
    var altitude = 0.0

    fun copy(other: RayHit) = apply {
        isHit = other.isHit
        isComplete = other.isComplete
        distance = other.distance
        latitude = other.latitude
        longitude = other.longitude
        altitude = other.altitude
    }
}

/**
 * Finds where straight rays first meet the ground of a [DemTileCache].
 *
 * The ray's height above the ground it passes over is exact for a straight line over a sphere
 * whose radius is the ellipsoid's curvature along the ray's bearing at its origin: at a distance
 * `s` along the ground the ray is `p / cos(s/R − δ) − R` above that sphere, with `p = (R + h)·cos δ`
 * for a ray `δ` below the horizontal from height `h`. So the ray rises away from the ground with
 * the earth's curvature, about 8 m at 10 km.
 *
 * The ground track is followed in pieces of [segmentLength], straight in latitude/longitude
 * within a piece (a centimetre off the great circle at 1 km). Within a tile the ray walks the
 * tile's max pyramid from the coarsest block down, stepping over every block it passes above;
 * in a cell it does not pass above, the ground along the ray is the cell's bilinear surface, a
 * quadratic in distance, so the crossing is solved in closed form, not stepped to.
 *
 * Not thread-safe: one caster per thread. It allocates nothing per ray.
 */
open class RayCaster(val tiles: DemTileCache) {
    /** Length of the straight pieces of the ground track, metres. */
    var segmentLength = 1000.0

    // Per ray
    private var radius = 0.0
    private var p = 0.0
    private var delta = 0.0
    private var lat0 = 0.0
    private var sinLat0 = 0.0
    private var cosLat0 = 0.0
    private var lon0 = 0.0
    private var latScale = 1.0
    private var lonScale = 1.0
    private var sinBearing = 0.0
    private var cosBearing = 0.0
    // Per piece: latitude/longitude in degrees, linear in distance
    private var segStart = 0.0
    private var segLat = 0.0
    private var segLon = 0.0
    private var segDLat = 0.0
    private var segDLon = 0.0
    private var segInvDLat = 0.0
    private var segInvDLon = 0.0
    private val scratch = DoubleArray(2)
    private var pieceEnd = -1.0
    private var pieceEndLat = 0.0
    private var pieceEndLon = 0.0
    // Last tile looked up, kept across rays: most consecutive rays cross the same few tiles
    private var lastRow = -1
    private var lastColumn = -1
    private var lastGeneration = -1L
    private var lastTile: DemTile? = null

    /**
     * Casts a ray from a point [altitude] metres above the ellipsoid along [bearing], [depression]
     * below the horizontal (negative looks up), out to [maxRange] metres along the ground.
     */
    fun cast(
        latitudeDegrees: Double, longitudeDegrees: Double, altitude: Double,
        bearing: Angle, depression: Angle, maxRange: Double, result: RayHit,
    ) = cast(latitudeDegrees, longitudeDegrees, altitude, bearing.inRadians, depression.inRadians, maxRange, result)

    /** As the other [cast], angles in radians. */
    fun cast(
        latitudeDegrees: Double, longitudeDegrees: Double, altitude: Double,
        bearing: Double, depression: Double, maxRange: Double, result: RayHit,
    ): RayHit {
        result.isHit = false
        result.isComplete = true
        val ellipsoid = tiles.globe.ellipsoid
        val phi = latitudeDegrees * DEG
        lat0 = phi
        sinLat0 = sin(phi)
        cosLat0 = cos(phi)
        lon0 = longitudeDegrees * DEG
        sinBearing = sin(bearing)
        cosBearing = cos(bearing)
        // Radius of curvature along the bearing (Euler), from the meridian and prime-vertical radii
        val e2 = ellipsoid.eccentricitySquared
        val w = 1.0 - e2 * sinLat0 * sinLat0
        val primeVertical = ellipsoid.semiMajorAxis / sqrt(w)
        val meridian = ellipsoid.semiMajorAxis * (1.0 - e2) / (w * sqrt(w))
        radius = 1.0 / (cosBearing * cosBearing / meridian + sinBearing * sinBearing / primeVertical)
        // The track is a great circle on the sphere of that radius; north-south and east-west the
        // ellipsoid is curved by the meridian and prime-vertical radii instead, about 0.3% apart
        // at mid latitudes - 25 m at 15 km on a diagonal bearing if left unscaled.
        latScale = radius / meridian
        lonScale = radius / primeVertical
        delta = depression.coerceIn(-MAX_ANGLE, MAX_ANGLE)
        p = (radius + altitude) * cos(delta)
        // Past this the ray points away from the earth for good
        val range = min(maxRange, radius * (PI / 2 + delta) * 0.999)

        pieceEnd = -1.0
        var s = 0.0
        while (s < range) {
            val end = min(s + segmentLength, range)
            setSegment(s, end)
            if (walkSegment(s, end, result)) return result
            s = end
        }
        // Missed: report the ground at the range
        result.distance = range
        trackPoint(range, scratch)
        result.latitude = scratch[0]
        result.longitude = scratch[1]
        result.altitude = elevationAt(scratch[0], scratch[1]) ?: 0.0
        return result
    }

    /** Bilinear ground height at a location from the loaded tiles, or null if its tile is not loaded. */
    fun elevationAt(latitudeDegrees: Double, longitudeDegrees: Double): Double? {
        val tile = tiles.tileAt(latitudeDegrees, longitudeDegrees) ?: return null
        val cell = tile.cellDegrees
        val u = ((longitudeDegrees - tile.minLongitude) / cell).coerceIn(0.0, tile.cells.toDouble())
        val v = ((latitudeDegrees - tile.minLatitude) / cell).coerceIn(0.0, tile.cells.toDouble())
        val c = min(u.toInt(), tile.cells - 1)
        val r = min(v.toInt(), tile.cells - 1)
        return bilinear(tile, r, c, u - c, v - r)
    }

    /** Height of the ray above the curvature sphere at [s] metres along the ground. */
    private fun rayHeight(s: Double): Double {
        val angle = s / radius - delta
        return if (angle >= PI / 2) Double.POSITIVE_INFINITY else p / cos(angle) - radius
    }

    /** Lowest height of the ray over `[a, b]`: the height is convex, lowest where `s/R = δ`. */
    private fun minRayHeight(a: Double, b: Double) = rayHeight((radius * delta).coerceIn(a, b))

    /**
     * Point [s] metres along the ground track, latitude and longitude in degrees: a great circle on
     * the curvature sphere, its latitude and longitude steps rescaled to the ellipsoid's radii.
     */
    private fun trackPoint(s: Double, out: DoubleArray) {
        val d = s / radius
        val sinD = sin(d)
        val cosD = cos(d)
        val sinLat = (sinLat0 * cosD + cosLat0 * sinD * cosBearing).coerceIn(-1.0, 1.0)
        out[0] = (lat0 + (asin(sinLat) - lat0) * latScale) / DEG
        out[1] = (lon0 + atan2(sinBearing * sinD * cosLat0, cosD - sinLat0 * sinLat) * lonScale) / DEG
    }

    private fun setSegment(start: Double, end: Double) {
        // A piece starts where the last one ended: reuse its end point, not the trigonometry
        val latA: Double
        val lonA: Double
        if (start == pieceEnd) {
            latA = pieceEndLat
            lonA = pieceEndLon
        } else {
            trackPoint(start, scratch)
            latA = scratch[0]
            lonA = scratch[1]
        }
        trackPoint(end, scratch)
        pieceEnd = end
        pieceEndLat = scratch[0]
        pieceEndLon = scratch[1]
        var dLon = scratch[1] - lonA
        if (dLon > 180.0) dLon -= 360.0 else if (dLon < -180.0) dLon += 360.0
        val length = end - start
        segStart = start
        segLat = latA
        segLon = lonA
        segDLat = (scratch[0] - latA) / length
        segDLon = dLon / length
        segInvDLat = if (segDLat != 0.0) 1.0 / segDLat else 0.0
        segInvDLon = if (segDLon != 0.0) 1.0 / segDLon else 0.0
    }

    private fun latAt(s: Double) = segLat + segDLat * (s - segStart)

    private fun lonAt(s: Double) = segLon + segDLon * (s - segStart)

    /** Distance at which the piece leaves a latitude/longitude box it is inside, or infinity. */
    private fun exitDistance(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): Double {
        var exit = Double.POSITIVE_INFINITY
        if (segDLat > 0.0) exit = min(exit, segStart + (maxLat - segLat) * segInvDLat)
        else if (segDLat < 0.0) exit = min(exit, segStart + (minLat - segLat) * segInvDLat)
        if (segDLon > 0.0) exit = min(exit, segStart + (maxLon - segLon) * segInvDLon)
        else if (segDLon < 0.0) exit = min(exit, segStart + (minLon - segLon) * segInvDLon)
        return exit
    }

    private fun walkSegment(start: Double, end: Double, result: RayHit): Boolean {
        val span = tiles.tileDegrees
        var t = start
        var guard = 0
        while (t < end && guard++ < MAX_TILES_PER_SEGMENT) {
            val probe = t + NUDGE
            val lat = latAt(probe)
            val lonUnwrapped = lonAt(probe)
            val lon = normalizeLongitude(lonUnwrapped)
            val row = tiles.rowOf(lat)
            val column = tiles.columnOf(lon)
            val minLat = row * span - 90.0
            // The tile's west edge in the piece's own (possibly unwrapped) longitudes
            val minLon = column * span - 180.0 + (lonUnwrapped - lon)
            val exit = max(min(end, exitDistance(minLat, minLat + span, minLon, minLon + span)), t + NUDGE)
            val tile = tileAt(row, column)
            if (tile == null) result.isComplete = false
            else if (traverse(tile, minLon, t, exit, result)) return true
            t = exit
        }
        return false
    }

    private fun tileAt(row: Int, column: Int): DemTile? {
        val generation = tiles.generation
        if (row == lastRow && column == lastColumn && generation == lastGeneration) lastTile?.let { return it }
        return tiles.tile(row, column).also {
            lastRow = row
            lastColumn = column
            lastGeneration = generation
            lastTile = it
        }
    }

    /**
     * Walks one tile's pyramid over `[start, end]`; true with [result] filled at the first crossing.
     * After each step the walk climbs back up one level only, not to the top: a ray near the
     * ground keeps to the fine levels it needs, a ray that clears the ground climbs back to skip
     * in large blocks within a few steps.
     */
    private fun traverse(tile: DemTile, minLon: Double, start: Double, end: Double, result: RayHit): Boolean {
        val cell = tile.cellDegrees
        val cells = tile.cells
        val top = tile.topLevel
        val invCell = 1.0 / cell
        var t = start
        var steps = 0
        var level = top
        while (t < end) {
            if (++steps > MAX_STEPS_PER_TILE) return false // cannot happen for a sane ray; never spin
            val probe = t + NUDGE
            val u = (lonAt(probe) - minLon) * invCell
            val v = (latAt(probe) - tile.minLatitude) * invCell
            while (true) {
                val size = 1 shl level
                val blocks = cells shr level
                val column = floor(u / size).toInt().coerceIn(0, blocks - 1)
                val row = floor(v / size).toInt().coerceIn(0, blocks - 1)
                val blockLat = tile.minLatitude + row * size * cell
                val blockLon = minLon + column * size * cell
                val blockSpan = size * cell
                val exit = max(min(end, exitDistance(blockLat, blockLat + blockSpan, blockLon, blockLon + blockSpan)), t + NUDGE)
                if (minRayHeight(t, exit) > tile.blockMax(level, row, column)) {
                    t = exit // passes above the whole block
                    if (level < top) level++
                    break
                }
                if (level == 0) {
                    if (crossCell(tile, row, column, blockLat, blockLon, t, exit, result)) return true
                    t = exit
                    if (level < top) level++
                    break
                }
                level--
            }
        }
        return false
    }

    private fun clearance(tile: DemTile, row: Int, column: Int, cellLat: Double, cellLon: Double, s: Double): Double {
        val fu = ((lonAt(s) - cellLon) / tile.cellDegrees).coerceIn(0.0, 1.0)
        val fv = ((latAt(s) - cellLat) / tile.cellDegrees).coerceIn(0.0, 1.0)
        return rayHeight(s) - bilinear(tile, row, column, fu, fv)
    }

    /**
     * The ray's first crossing of one cell's bilinear surface over `[a, b]`. Along a straight
     * track the surface is a quadratic in distance, and over a cell the ray's height departs from
     * a quadratic by well under a millimetre, so the clearance is fitted through three samples and
     * its first root solved exactly.
     */
    private fun crossCell(
        tile: DemTile, row: Int, column: Int, cellLat: Double, cellLon: Double, a: Double, b: Double, result: RayHit,
    ): Boolean {
        val ca = clearance(tile, row, column, cellLat, cellLon, a)
        val x = if (ca <= 0.0) 0.0 else {
            val cm = clearance(tile, row, column, cellLat, cellLon, 0.5 * (a + b))
            val cb = clearance(tile, row, column, cellLat, cellLon, b)
            firstRoot(2 * ca - 4 * cm + 2 * cb, -3 * ca + 4 * cm - cb, ca)
                ?: if (cb <= 0.0) ca / (ca - cb) else return false
        }
        val s = a + x * (b - a)
        result.isHit = true
        result.distance = s
        result.latitude = latAt(s)
        result.longitude = normalizeLongitude(lonAt(s))
        result.altitude = rayHeight(s) - clearance(tile, row, column, cellLat, cellLon, s)
        return true
    }

    companion object {
        private const val DEG = PI / 180.0
        /** Forced progress past a boundary, metres: keeps the walk moving through rounding. */
        private const val NUDGE = 1e-4
        private const val MAX_ANGLE = PI / 2 - 1e-6
        private const val MAX_TILES_PER_SEGMENT = 64
        private const val MAX_STEPS_PER_TILE = 1 shl 16

        internal fun bilinear(tile: DemTile, row: Int, column: Int, fu: Double, fv: Double): Double {
            val z00 = tile.post(row, column)
            val z01 = tile.post(row, column + 1)
            val z10 = tile.post(row + 1, column)
            val z11 = tile.post(row + 1, column + 1)
            return (1 - fv) * ((1 - fu) * z00 + fu * z01) + fv * ((1 - fu) * z10 + fu * z11)
        }

        /** Smallest root in `[0, 1]` of `a·x² + b·x + c`, or null. */
        internal fun firstRoot(a: Double, b: Double, c: Double): Double? {
            if (abs(a) <= 1e-12 * (abs(b) + abs(c))) {
                if (b == 0.0) return null
                val x = -c / b
                return if (x in 0.0..1.0) x else null
            }
            val disc = b * b - 4 * a * c
            if (disc < 0.0) return null
            val sq = sqrt(disc)
            val r1 = (-b - sq) / (2 * a)
            val r2 = (-b + sq) / (2 * a)
            val lo = min(r1, r2)
            val hi = max(r1, r2)
            return if (lo in 0.0..1.0) lo else if (hi in 0.0..1.0) hi else null
        }

        internal fun normalizeLongitude(lon: Double) =
            if (lon > 180.0) lon - 360.0 else if (lon < -180.0) lon + 360.0 else lon
    }
}
