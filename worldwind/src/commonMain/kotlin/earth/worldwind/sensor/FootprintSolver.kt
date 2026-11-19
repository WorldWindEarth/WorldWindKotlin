package earth.worldwind.sensor

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Position
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A framing sensor's pose and optics. [position] is above the ellipsoid (ABSOLUTE altitude).
 *
 * The optical axis points along [azimuth] (clockwise from north), [depression] below the local
 * horizontal (negative looks up). A positive [roll] turns the frame clockwise as seen from behind
 * the sensor, its right side down. Fields may be changed in place at any time; the engine compares
 * them with what it last solved for.
 */
class Sensor(
    var position: Position,
    var azimuth: Angle,
    var depression: Angle,
    var horizontalFov: Angle,
    var verticalFov: Angle,
    /** How far along the ground a ray is followed before it is given up, metres. */
    var maxRange: Double,
    var roll: Angle = ZERO,
)

/**
 * Where a sensor's frame meets the ground.
 *
 * [outline] goes round the frame's edges from its top-left corner through top-right, bottom-right
 * and bottom-left, which for a sensor looking down is clockwise seen from above. Its points are on
 * the ground, heights above the ellipsoid. A ray that meets no ground within the range (or looks
 * at the sky) contributes the ground point at the range instead, so the outline then follows the
 * range circle there, and [cornerHits] tells which corners did meet the ground.
 *
 * A footprint is never changed after it is made, so its lists may be handed to shapes as they are.
 */
class Footprint(
    /** Where the sensor was when this was solved. */
    val sensorPosition: Position,
    val outline: List<Position>,
    /** Top-left, top-right, bottom-right, bottom-left. */
    val corners: List<Position>,
    val cornerHits: List<Boolean>,
    /** False when some ray crossed ground whose elevations were still loading; it will be solved again. */
    val isComplete: Boolean,
    val rayCount: Int,
)

/**
 * Solves [Footprint]s with a [RayCaster]: a ray through each corner of the frame, then rays along
 * the edges only where the ground needs them.
 *
 * Each edge is first split [minDepth] times evenly, which no ground can hide from. Past that a
 * piece of an edge is split again only while the ray through its middle lands more than
 * [tolerance] metres off the straight line between its ends' landings, or lands differently (on
 * the ground or not) from them, down to [maxDepth] splits. Flat ground stays at the even split;
 * a ridge across an edge gets rays until its outline is within [tolerance].
 */
class FootprintSolver(val tiles: DemTileCache) {
    val caster = RayCaster(tiles)
    /**
     * Largest gap, metres, between the drawn outline and a ray's landing that is let stand. The
     * ray count, and so the cost, follows it: over rough hills an 8 km footprint takes about 32
     * rays at 20 m, 57 at 10 m and 113 at 3 m (with [maxDepth] 5).
     */
    var tolerance = 10.0
    /** Even splits of every edge: 2 is 4 pieces an edge, 16 rays at least. */
    var minDepth = 2
    /** Most splits of an edge: 4 is up to 16 pieces an edge. */
    var maxDepth = 4

    // Per solve
    private var lat0 = 0.0
    private var lon0 = 0.0
    private var alt0 = 0.0
    private var range = 0.0
    private var metersPerDegreeLon = 0.0
    private var tanH = 0.0
    private var tanV = 0.0
    private val f = DoubleArray(3)
    private val r = DoubleArray(3)
    private val u = DoubleArray(3)
    private var rays = 0
    private var complete = true

    fun solve(sensor: Sensor): Footprint {
        val position = sensor.position
        lat0 = position.latitude.inDegrees
        lon0 = position.longitude.inDegrees
        alt0 = position.altitude
        range = sensor.maxRange
        metersPerDegreeLon = DemTileCache.METERS_PER_DEGREE * cos(lat0 * PI / 180.0)
        tanH = tan(sensor.horizontalFov.inRadians / 2)
        tanV = tan(sensor.verticalFov.inRadians / 2)
        sensorFrameAxes(sensor.azimuth.inRadians, sensor.depression.inRadians, sensor.roll.inRadians, f, r, u)
        rays = 0
        complete = true

        val cornerHits = Array(4) { cast(CORNER_X[it], CORNER_Y[it]) }
        val outline = ArrayList<Position>(4 shl minDepth)
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            outline += cornerHits[i].toPosition()
            edge(CORNER_X[i], CORNER_Y[i], cornerHits[i], CORNER_X[j], CORNER_Y[j], cornerHits[j], 0, outline)
        }
        return Footprint(
            sensorPosition = Position(position),
            outline = outline,
            corners = cornerHits.map { it.toPosition() },
            cornerHits = cornerHits.map { it.isHit },
            isComplete = complete,
            rayCount = rays,
        )
    }

    /** The ray through frame point (x, y), each in [-1, 1] from left/bottom to right/top. */
    private fun cast(x: Double, y: Double): RayHit {
        val e = f[0] + x * tanH * r[0] + y * tanV * u[0]
        val n = f[1] + x * tanH * r[1] + y * tanV * u[1]
        val up = f[2] + x * tanH * r[2] + y * tanV * u[2]
        val bearing = atan2(e, n)
        val depression = atan2(-up, sqrt(e * e + n * n))
        rays++
        return caster.cast(lat0, lon0, alt0, bearing, depression, range, RayHit()).also {
            if (!it.isComplete) complete = false
        }
    }

    private fun edge(
        ax: Double, ay: Double, a: RayHit, bx: Double, by: Double, b: RayHit, depth: Int, out: MutableList<Position>,
    ) {
        if (depth >= maxDepth) return
        val mx = 0.5 * (ax + bx)
        val my = 0.5 * (ay + by)
        val m = cast(mx, my)
        val split = depth < minDepth || a.isHit != b.isHit || m.isHit != a.isHit || offLine(m, a, b) > tolerance
        if (!split) return
        edge(ax, ay, a, mx, my, m, depth + 1, out)
        out += m.toPosition()
        edge(mx, my, m, bx, by, b, depth + 1, out)
    }

    /** Distance, metres, from [m]'s landing to the straight segment between [a]'s and [b]'s. */
    private fun offLine(m: RayHit, a: RayHit, b: RayHit): Double {
        val ax = (a.longitude - lon0) * metersPerDegreeLon
        val ay = (a.latitude - lat0) * DemTileCache.METERS_PER_DEGREE
        val bx = (b.longitude - lon0) * metersPerDegreeLon - ax
        val by = (b.latitude - lat0) * DemTileCache.METERS_PER_DEGREE - ay
        val bz = b.altitude - a.altitude
        val mx = (m.longitude - lon0) * metersPerDegreeLon - ax
        val my = (m.latitude - lat0) * DemTileCache.METERS_PER_DEGREE - ay
        val mz = m.altitude - a.altitude
        val length2 = bx * bx + by * by + bz * bz
        val t = if (length2 > 0.0) ((mx * bx + my * by + mz * bz) / length2).coerceIn(0.0, 1.0) else 0.0
        val dx = mx - t * bx
        val dy = my - t * by
        val dz = mz - t * bz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun RayHit.toPosition() = Position.fromDegrees(latitude, longitude, altitude)

    private companion object {
        val CORNER_X = doubleArrayOf(-1.0, 1.0, 1.0, -1.0)
        val CORNER_Y = doubleArrayOf(1.0, 1.0, -1.0, -1.0)
    }
}
