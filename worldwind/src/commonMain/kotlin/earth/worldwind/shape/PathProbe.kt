package earth.worldwind.shape

import earth.worldwind.draw.DrawablePathProbe
import earth.worldwind.geom.BoundingSphere
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.RenderContext
import earth.worldwind.render.program.ProbeOccupancyProgram
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds where a path - a shell's trajectory, a cable, a flight line - first runs into solid 3D
 * content: 3D Tiles meshes, filled shapes, meshes and COLLADA models. Terrain is not checked here.
 *
 * The probe looks straight down on a corridor along the path, [halfWidth] metres to each side, with
 * an orthographic camera, and reads the height of the highest surface in every pixel from the
 * depth buffer. Walking the path, the first point at or below the highest surface under it is the
 * hit; everything under a surface counts as solid, so roofs, photogrammetry and models block the
 * path whatever angle it crosses them at. (A side view would miss them: a wall crossed at a right
 * angle and a flat roof are both edge-on to it and draw no pixels.) A second,
 * zoomed render around the first hit refines it. The work happens on the GL thread during the next
 * frames; [onResult] is called on the main thread.
 *
 * Only content the engine has drawn for the frame takes part, so off-screen 3D Tiles at coarse
 * detail may be missed. Shapes with [AbstractShape.isProbeObstacle] off never block the probe.
 *
 * Add the probe to a layer and call [probe]; each call replaces the previous request.
 */
open class PathProbe : AbstractRenderable("Path probe") {
    /** Result of one probe: the first hit, or none when the path is clear. */
    class Result(
        /** The path the probe was asked about. */
        val path: List<Position>,
        /** First solid point along the path, or null. */
        val hit: Position?,
        /** Length along the path from its start to [hit], m. */
        val distanceAlongPath: Double,
    )

    /** Called on the main thread with the result of each [probe] call. */
    var onResult: ((Result) -> Unit)? = null
    /** Image size of each render; the slab is stretched over it. */
    var columns = DEFAULT_COLUMNS
    var rows = DEFAULT_ROWS

    private var request: Request? = null
    private var generation = 0
    private val mainScope = MainScope()

    private class Request(
        val generation: Int,
        val path: List<Position>,
        val halfWidth: Double,
        val startMargin: Double,
        val endMargin: Double,
    ) {
        /** Set after the coarse render: the window the refining render looks at. */
        var refineFrom = Double.NaN
        var refineTo = Double.NaN
        var pending = true
        /** Frames to let pass first: shapes added along with the request need a frame to be drawn. */
        var framesToWait = SETTLE_FRAMES
    }

    /**
     * Asks where [path] first meets solid content within [halfWidth] metres of it. The first
     * [startMargin] and the last [endMargin] metres along the path are not checked, so the
     * object at either end does not count as an obstacle.
     */
    fun probe(path: List<Position>, halfWidth: Double, startMargin: Double = 0.0, endMargin: Double = 0.0) {
        require(path.size >= 2) { "A path needs at least two positions" }
        request = Request(++generation, path.map { Position(it) }, halfWidth, startMargin, endMargin)
    }

    /** Drops the outstanding request, if any; its result is never delivered. */
    fun cancel() {
        request = null
        generation++
    }

    override fun doRender(rc: RenderContext) {
        val request = request ?: return
        if (!request.pending) return
        if (request.framesToWait > 0) {
            request.framesToWait--
            requestRedraw()
            return
        }
        val frame = SlabFrame.of(rc.globe, request.path) ?: return
        val (from, to) = if (request.refineFrom.isNaN()) frame.minX to frame.maxX else request.refineFrom to request.refineTo
        var lowest = Double.MAX_VALUE
        var highest = -Double.MAX_VALUE
        frame.forEachLocalPoint(from, to) { _, z -> lowest = min(lowest, z); highest = max(highest, z) }
        if (lowest > highest) return
        // Height window: anything below the path's lowest point cannot be hit; the top reaches far
        // above the path so that tall objects are never clipped by the near plane.
        val bottom = lowest - VERTICAL_PAD
        val top = highest + HEADROOM
        val drawable = DrawablePathProbe.obtain(rc.getDrawablePool(DrawablePathProbe.PROBE_KEY))
        drawable.columns = columns.coerceIn(1, MAX_SIZE)
        drawable.rows = rows.coerceIn(1, MAX_SIZE)
        frame.projectionView(from, to, bottom, top, request.halfWidth, drawable.projectionView)
        // Range culling and off-screen shape enqueueing go by the corridor around the path itself.
        val center = frame.toWorld((from + to) / 2.0, 0.0, (lowest + highest) / 2.0)
        val radius = 0.5 * sqrt((to - from) * (to - from) + (highest - lowest + 2 * VERTICAL_PAD).let { it * it } + 4.0 * request.halfWidth * request.halfWidth)
        drawable.centerTransform.setToTranslation(center.x, center.y, center.z)
        drawable.range = radius.toFloat()
        rc.sightlineBounds.add(BoundingSphere().set(center, radius))
        drawable.occupancyProgram = rc.getShaderProgram { ProbeOccupancyProgram() }
        val columns = drawable.columns
        val rows = drawable.rows
        drawable.onOccupancy = { cells ->
            mainScope.launch { onImage(request, frame, cells, columns, rows, from, to, bottom, top) }
        }
        request.pending = false
        rc.offerBackgroundDrawable(drawable)
    }

    private fun onImage(
        request: Request, frame: SlabFrame, cells: FloatArray, columns: Int, rows: Int,
        from: Double, to: Double, bottom: Double, top: Double,
    ) {
        if (request.generation != generation) return
        // Highest surface across the corridor at each column; everything below it counts as solid,
        // like a height map, so overhangs (a bridge) err on the safe side.
        val columnTop = DoubleArray(columns) { c ->
            var best = Double.NaN
            for (r in 0 until rows) {
                val depth = cells[r * columns + c]
                if (depth < 0f) continue
                val height = top - depth * (top - bottom)
                if (best.isNaN() || height > best) best = height
            }
            best
        }
        val solid = { x: Double, z: Double ->
            val c = ((x - from) / (to - from) * columns).toInt()
            c in 0 until columns && !columnTop[c].isNaN() && z <= columnTop[c]
        }
        val step = 0.5 * (to - from) / columns
        val hit = frame.firstSolid(from, to, step, request.startMargin, request.endMargin, solid)
        val refining = !request.refineFrom.isNaN()
        if (hit != null && !refining) {
            // Zoom in on the hit: a window a few coarse pixels wide either side, rendered at full size.
            val window = max(REFINE_PIXELS * (to - from) / columns, MIN_REFINE_WINDOW)
            request.refineFrom = max(frame.minX, hit.x - window)
            request.refineTo = min(frame.maxX, hit.x + window)
            request.pending = true
            requestRedraw()
            return
        }
        if (refining && hit == null) {
            // The zoomed render saw nothing: the coarse hit came from a pixel's worth of edge.
            request.refineFrom = Double.NaN
        }
        this.request = null
        onResult?.invoke(
            Result(request.path, hit?.let(frame::toPosition), hit?.distance ?: Double.NaN)
        )
    }

    private fun requestRedraw() = earth.worldwind.WorldWind.requestRedraw()

    /**
     * Local frame at the path's start: X along the path's horizontal direction, Z up, Y across.
     * The path is held as local (x, z) points with cumulative length, so hits map back exactly.
     */
    internal class SlabFrame(
        private val toWorldMatrix: Matrix4,
        private val xs: DoubleArray,
        private val zs: DoubleArray,
        private val lengths: DoubleArray,
        private val positions: List<Position>,
    ) {
        val minX = xs.min()
        val maxX = xs.max()

        class Hit(val x: Double, val z: Double, val distance: Double, val segment: Int, val fraction: Double)

        fun forEachLocalPoint(from: Double, to: Double, block: (Double, Double) -> Unit) {
            for (i in xs.indices) {
                val inside = xs[i] in from..to ||
                    (i > 0 && min(xs[i - 1], xs[i]) <= to && max(xs[i - 1], xs[i]) >= from)
                if (inside) block(xs[i], zs[i])
            }
        }

        fun toWorld(x: Double, y: Double, z: Double) = Vec3(x, y, z).multiplyByMatrix(toWorldMatrix)

        /**
         * Orthographic camera looking straight down on the corridor: image x runs along the path
         * from [left] to [right], image y across it over ±[halfWidth], and window depth goes from 0
         * at height [top] to 1 at height [bottom].
         */
        fun projectionView(left: Double, right: Double, bottom: Double, top: Double, halfWidth: Double, result: Matrix4) {
            // Looking down -Z: the near plane is at height top, the far plane at height bottom.
            val near = -top
            val far = -bottom
            val projection = Matrix4().set(
                2.0 / (right - left), 0.0, 0.0, -(right + left) / (right - left),
                0.0, 1.0 / halfWidth, 0.0, 0.0,
                0.0, 0.0, -2.0 / (far - near), -(far + near) / (far - near),
                0.0, 0.0, 0.0, 1.0,
            )
            val view = Matrix4().copy(toWorldMatrix).invertOrthonormal()
            result.copy(projection).multiplyByMatrix(view)
        }

        /** Walks the path between the margins in [step]-metre bites and returns the first solid point. */
        fun firstSolid(
            from: Double, to: Double, step: Double, startMargin: Double, endMargin: Double,
            solid: (Double, Double) -> Boolean,
        ): Hit? {
            val total = lengths.last()
            for (i in 1 until xs.size) {
                val segmentLength = lengths[i] - lengths[i - 1]
                if (segmentLength <= 0.0) continue
                if (max(xs[i - 1], xs[i]) < from || min(xs[i - 1], xs[i]) > to) continue
                val bites = max(1, (segmentLength / step).toInt())
                for (b in 0..bites) {
                    val f = b.toDouble() / bites
                    val distance = lengths[i - 1] + segmentLength * f
                    if (distance < startMargin || distance > total - endMargin) continue
                    val x = xs[i - 1] + (xs[i] - xs[i - 1]) * f
                    val z = zs[i - 1] + (zs[i] - zs[i - 1]) * f
                    if (x < from || x > to) continue
                    if (solid(x, z)) return Hit(x, z, distance, i, f)
                }
            }
            return null
        }

        fun toPosition(hit: Hit): Position {
            val a = positions[hit.segment - 1]
            val b = positions[hit.segment]
            val f = hit.fraction
            return Position(
                a.latitude.plusDegrees((b.latitude.inDegrees - a.latitude.inDegrees) * f),
                a.longitude.plusDegrees((b.longitude.inDegrees - a.longitude.inDegrees) * f),
                a.altitude + (b.altitude - a.altitude) * f,
            )
        }

        companion object {
            fun of(globe: Globe, path: List<Position>): SlabFrame? {
                val world = path.map { globe.geographicToCartesian(it.latitude, it.longitude, it.altitude, Vec3()) }
                val start = world.first()
                val local = Matrix4()
                globe.cartesianToLocalTransform(start.x, start.y, start.z, local)
                val toLocal = Matrix4().copy(local).invertOrthonormal()
                // Turn the local east-north-up frame so X points at the path's far end.
                val end = Vec3().copy(world.last()).multiplyByMatrix(toLocal)
                if (end.x * end.x + end.y * end.y < 1e-6) return null
                val heading = atan2(end.y, end.x)
                val turn = Matrix4().set(
                    cos(heading), -sin(heading), 0.0, 0.0,
                    sin(heading), cos(heading), 0.0, 0.0,
                    0.0, 0.0, 1.0, 0.0,
                    0.0, 0.0, 0.0, 1.0,
                )
                val toWorld = Matrix4().copy(local).multiplyByMatrix(turn)
                val toSlab = Matrix4().copy(toWorld).invertOrthonormal()
                val xs = DoubleArray(world.size)
                val zs = DoubleArray(world.size)
                val lengths = DoubleArray(world.size)
                world.forEachIndexed { i, point ->
                    val p = Vec3().copy(point).multiplyByMatrix(toSlab)
                    xs[i] = p.x
                    zs[i] = p.z
                    if (i > 0) lengths[i] = lengths[i - 1] + point.distanceTo(world[i - 1])
                }
                return SlabFrame(toWorld, xs, zs, lengths, path)
            }
        }
    }

    companion object {
        const val DEFAULT_COLUMNS = 1024
        /** Pixels across the corridor. */
        const val DEFAULT_ROWS = 16
        /** The scratch framebuffer the probe renders into is this big. */
        const val MAX_SIZE = 1024
        private const val VERTICAL_PAD = 2.0
        /** How far above the path the camera's near plane sits, m: taller than any structure. */
        private const val HEADROOM = 2_000.0
        private const val SETTLE_FRAMES = 2
        private const val REFINE_PIXELS = 4.0
        private const val MIN_REFINE_WINDOW = 20.0
    }
}
