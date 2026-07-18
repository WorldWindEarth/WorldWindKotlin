package earth.worldwind.layer.mvt

import earth.worldwind.geom.Position
import earth.worldwind.layer.mercator.SlippyTiles
import earth.worldwind.util.IntList
import earth.worldwind.util.RingSimplifier
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Convert the raw MVT command stream of an [MvtFeature] into lat/lon geometry for a slippy
 * tile at (z, x, y) with the given `extent` (tile-coordinate resolution; conventionally 4096).
 *
 * Command stream layout (vector-tile-spec §4.3.2):
 *   CommandInteger = (id & 0x7) | (count << 3)
 *     id 1 = MoveTo (count×{dx, dy})
 *     id 2 = LineTo (count×{dx, dy})
 *     id 7 = ClosePath (no parameters)
 *   Parameter values are zigzag-encoded varints; [MvtDecoder] left them as raw uint32 bits.
 *
 * Per geometry type:
 *   POINT      — one MoveTo with N≥1 parameter pairs, each an independent point.
 *   LINESTRING — repeated `MoveTo(1) + LineTo(K)`; each MoveTo starts a new linestring.
 *   POLYGON    — repeated `MoveTo(1) + LineTo(K) + ClosePath`; signed area in tile-space
 *                discriminates exterior (positive) from interior (negative) rings. Holes
 *                follow their enclosing exterior in the stream.
 *
 * The cursor starts at (0, 0) and accumulates deltas across all commands; MoveTo updates it
 * just like LineTo (the spec is explicit on this).
 *
 * All entry points are stateless — concurrent calls from parallel tile-fetch coroutines are
 * safe.
 */
object MvtGeometry {

    /**
     * Per-tile reused scratch for the decode hot path. One instance per tile (decoded on a single
     * worker thread), threaded through the decode entry points so the inner loops allocate nothing
     * per feature or per ring. NOT thread-safe — one instance per concurrent tile assembly.
     */
    class Scratch {
        var zigzag = IntArray(0)
        val ringX = IntList()
        val ringY = IntList()
        val kept = IntList()
        val dpStack = IntList()
        val rawOuter = IntList()    // raw tile-coord outline [x,y,x,y,…] of the current exterior, for hole tests
        private var keep = BooleanArray(0)
        /** Reusable BooleanArray >= [n], reset to false over [0, n). */
        fun keepBuf(n: Int): BooleanArray {
            if (keep.size < n) keep = BooleanArray(n) else keep.fill(false, 0, n)
            return keep
        }
    }

    /** Inflate a POINT feature's geometry into one [Position] per point. Empty on malformed input. */
    fun decodePoints(feature: MvtFeature, z: Int, x: Int, y: Int, extent: Int, scratch: Scratch): List<Position> {
        if (feature.type != MvtGeometryType.POINT) return emptyList()
        val out = ArrayList<Position>()
        var cursorX = 0
        var cursorY = 0
        walkCommands(feature.geometry, scratch) { id, count, params, offset ->
            if (id != CMD_MOVE_TO) return@walkCommands
            // POINT's "count" can exceed 1: each (dx, dy) is an independent point with the
            // cursor advancing through all of them.
            for (i in 0 until count) {
                cursorX += params[offset + 2 * i]
                cursorY += params[offset + 2 * i + 1]
                out += unproject(z, x, y, extent, cursorX, cursorY)
            }
        }
        return out
    }

    /** Inflate a LINESTRING feature into polylines (each MoveTo starts a new one), each a flat
     *  interleaved `[lon°, lat°, …]` degree array — no per-vertex [Position]; altitude dropped. */
    fun decodeLines(feature: MvtFeature, z: Int, x: Int, y: Int, extent: Int, simplify: Boolean = true, scratch: Scratch): List<DoubleArray> {
        if (feature.type != MvtGeometryType.LINESTRING) return emptyList()
        val lines = ArrayList<DoubleArray>()
        // Buffer the current line as raw tile coords (no boxing) so RDP runs before unproject; reused per MoveTo.
        val lineX = scratch.ringX.also { it.clear() }
        val lineY = scratch.ringY.also { it.clear() }
        val kept = scratch.kept
        val nz = (1 shl z).toDouble()
        var cursorX = 0
        var cursorY = 0
        // RDP-simplify the buffered open polyline in tile coords, then emit survivors as flat lon/lat.
        fun flushLine() {
            val rawN = lineX.size
            if (rawN < 2) return
            kept.clear()
            if (rawN < 3 || !simplify) {
                // <3 verts: RDP is a no-op (both endpoints always kept); pass through unchanged.
                for (i in 0 until rawN) kept.add(i)
            } else {
                // Open polyline: keep survivors in order, no closing edge / area handling.
                val keep = scratch.keepBuf(rawN)
                simplifyRing(lineX, lineY, rawN, keep, scratch.dpStack)
                for (i in 0 until rawN) if (keep[i]) kept.add(i)
            }
            val n = kept.size
            // Unproject each kept tile coord to flat [lon°, lat°] (inlines unproject's math).
            val flat = DoubleArray(n * 2)
            for (i in 0 until n) {
                val a = kept[i]
                val tx = x.toDouble() + lineX[a].toDouble() / extent
                val ty = y.toDouble() + lineY[a].toDouble() / extent
                flat[i * 2] = SlippyTiles.tileXToLonDegrees(tx, nz)
                flat[i * 2 + 1] = SlippyTiles.tileYToLatDegrees(ty, nz)
            }
            lines += flat
        }
        walkCommands(feature.geometry, scratch) { id, count, params, offset ->
            when (id) {
                CMD_MOVE_TO -> {
                    flushLine()
                    cursorX += params[offset]
                    cursorY += params[offset + 1]
                    lineX.clear(); lineY.clear()
                    lineX.add(cursorX); lineY.add(cursorY)
                }
                CMD_LINE_TO -> {
                    if (lineX.size == 0) return@walkCommands
                    for (i in 0 until count) {
                        cursorX += params[offset + 2 * i]
                        cursorY += params[offset + 2 * i + 1]
                        lineX.add(cursorX); lineY.add(cursorY)
                    }
                }
            }
        }
        flushLine() // emit the final buffered line
        return lines
    }

    /**
     * Inflate a POLYGON feature into a list of (outerRing, holes) pairs. Exterior rings have
     * positive signed area in tile-space coordinates (where Y grows downward); negative-area
     * rings are holes attached to the most recently emitted exterior ring.
     *
     * The MVT-positive-area test produces CCW outer rings after Y inversion to north-up,
     * matching WorldWind's [earth.worldwind.shape.Polygon] tessellator expectation.
     */
    fun decodePolygons(feature: MvtFeature, z: Int, x: Int, y: Int, extent: Int, simplify: Boolean = true, scratch: Scratch): List<PolygonRings> {
        if (feature.type != MvtGeometryType.POLYGON) return emptyList()
        val polygons = ArrayList<PolygonRings>()
        // Accumulate the current ring as raw tile coords (no Position/boxing); the flat lon/lat
        // array is built once per ring at ClosePath. ringX/ringY are reused across rings.
        val ringX = scratch.ringX.also { it.clear() }
        val ringY = scratch.ringY.also { it.clear() }
        scratch.rawOuter.clear()
        val kept = scratch.kept
        var inRing = false
        val nz = (1 shl z).toDouble()
        var cursorX = 0
        var cursorY = 0
        walkCommands(feature.geometry, scratch) { id, count, params, offset ->
            when (id) {
                CMD_MOVE_TO -> {
                    cursorX += params[offset]
                    cursorY += params[offset + 1]
                    ringX.clear(); ringY.clear()
                    ringX.add(cursorX); ringY.add(cursorY)
                    inRing = true
                }
                CMD_LINE_TO -> {
                    if (!inRing) return@walkCommands
                    for (i in 0 until count) {
                        cursorX += params[offset + 2 * i]
                        cursorY += params[offset + 2 * i + 1]
                        ringX.add(cursorX); ringY.add(cursorY)
                    }
                }
                CMD_CLOSE_PATH -> {
                    if (!inRing) return@walkCommands
                    inRing = false
                    val rawN = ringX.size
                    if (rawN < 3) return@walkCommands
                    // Signed area on the RAW ring (tile coords): positive ⇒ exterior, negative ⇒ hole-candidate.
                    var area = 0.0
                    for (i in 0 until rawN) {
                        val b = if (i + 1 == rawN) 0 else i + 1
                        area += ringX[i].toDouble() * ringY[b] - ringX[b].toDouble() * ringY[i]
                    }
                    // Simplify for the emitted RENDER geometry only.
                    kept.clear()
                    if (rawN < 4 || !simplify) {
                        for (i in 0 until rawN) kept.add(i)
                    } else {
                        val keep = scratch.keepBuf(rawN)
                        simplifyRing(ringX, ringY, rawN, keep, scratch.dpStack)
                        for (i in 0 until rawN) if (keep[i]) kept.add(i)
                    }
                    val n = kept.size
                    if (n < 3) return@walkCommands
                    val flat = DoubleArray(n * 2)
                    for (i in 0 until n) {
                        val a = kept[i]
                        val tx = x.toDouble() + ringX[a].toDouble() / extent
                        val ty = y.toDouble() + ringY[a].toDouble() / extent
                        flat[i * 2] = SlippyTiles.tileXToLonDegrees(tx, nz)
                        flat[i * 2 + 1] = SlippyTiles.tileYToLatDegrees(ty, nz)
                    }
                    if (area > 0.0) {
                        polygons += PolygonRings(flat)
                        snapshotRawOuter(scratch.rawOuter, ringX, ringY, rawN)
                    } else {
                        // Hole vs. separate landmass decided on RAW tile coords against the RAW current
                        // exterior — the simplified outline is far too coarse at globe scale and would
                        // swallow offshore islands (Ireland, Greenland) as fake holes.
                        val cur = polygons.lastOrNull()
                        if (cur != null && scratch.rawOuter.size >= 6 && rawCentroidInsideTile(scratch.rawOuter, ringX, ringY, rawN)) {
                            cur.addHole(flat)
                        } else {
                            polygons += PolygonRings(flat)
                            snapshotRawOuter(scratch.rawOuter, ringX, ringY, rawN)
                        }
                    }
                }
            }
        }
        return polygons
    }

    /** Copy the n-vertex raw tile-coord ring [xs]/[ys] into [dst] as interleaved [x,y,…]. */
    private fun snapshotRawOuter(dst: IntList, xs: IntList, ys: IntList, n: Int) {
        dst.clear()
        for (i in 0 until n) { dst.add(xs[i]); dst.add(ys[i]) }
    }

    /** True if the centroid of the n-vertex raw ring [xs]/[ys] lies inside the interleaved tile-coord
     *  ring [outer] (ray casting). Tells a real hole (centroid inside its exterior) from a separately
     *  wound foreign ring (offshore island) at full resolution. */
    private fun rawCentroidInsideTile(outer: IntList, xs: IntList, ys: IntList, n: Int): Boolean {
        var cx = 0.0; var cy = 0.0
        for (i in 0 until n) { cx += xs[i]; cy += ys[i] }
        cx /= n; cy /= n
        val m = outer.size / 2
        var inside = false
        var j = m - 1
        for (i in 0 until m) {
            val xi = outer[i * 2].toDouble(); val yi = outer[i * 2 + 1].toDouble()
            val xj = outer[j * 2].toDouble(); val yj = outer[j * 2 + 1].toDouble()
            if ((yi > cy) != (yj > cy) && cx < (xj - xi) * (cy - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * Convert a tile-local point at zoom [z], tile column [x], tile row [y], and extent
     * [extent] (almost always 4096) into a sea-level [Position].
     *
     * Web Mercator slippy-tile math: x maps linearly to longitude; y inverts through `sinh`
     * because Mercator y is `arcsinh(tan(lat))`. The point may legitimately fall outside
     * `[0, extent]` — MVT tiles include a small buffer (typically 64 px) of geometry that
     * overlaps neighbours to avoid hairline gaps; we project anywhere.
     */
    fun unproject(z: Int, x: Int, y: Int, extent: Int, px: Int, py: Int): Position {
        val n = (1 shl z).toDouble()
        val tx = x.toDouble() + px.toDouble() / extent
        val ty = y.toDouble() + py.toDouble() / extent
        val lonDeg = SlippyTiles.tileXToLonDegrees(tx, n)
        val latDeg = SlippyTiles.tileYToLatDegrees(ty, n)
        return Position.fromDegrees(latDeg, lonDeg, 0.0)
    }

    private const val CMD_MOVE_TO = 1
    private const val CMD_LINE_TO = 2
    private const val CMD_CLOSE_PATH = 7

    /** ~1 px at extent 4096 rendered ~512 px; compared against its square (8*8 = 64) for sqrt-free RDP. */
    private const val SIMPLIFY_TOLERANCE_UNITS = 8

    /** Simplification of a ring/line in integer tile coords; marks survivors in [keep] (size [n]).
     *  Delegates to the shared [RingSimplifier] (global Douglas–Peucker) with a fixed tile-unit
     *  tolerance — correct at every zoom because MVT tile coords are always extent-relative.
     *  [stack] is the per-tile reusable explicit-recursion buffer. */
    private fun simplifyRing(xs: IntList, ys: IntList, n: Int, keep: BooleanArray, stack: IntList) {
        val tolSq = (SIMPLIFY_TOLERANCE_UNITS * SIMPLIFY_TOLERANCE_UNITS).toDouble()
        RingSimplifier.simplify(n, tolSq, keep, stack, xAt = { xs[it].toDouble() }, yAt = { ys[it].toDouble() })
    }

    /**
     * Walk a command stream, invoking [block] once per command with the decoded `id`, `count`,
     * a zig-zag-decoded parameter array, and the offset of that command's parameters.
     * [block] receives the same `params` array on every call — don't retain references across
     * calls; the contents are owned by this walker.
     */
    private inline fun walkCommands(
        stream: IntArray,
        scratch: Scratch,
        block: (id: Int, count: Int, params: IntArray, offset: Int) -> Unit,
    ) {
        if (stream.isEmpty()) return
        if (scratch.zigzag.size < stream.size) scratch.zigzag = IntArray(stream.size)
        val params = scratch.zigzag
        stream.copyInto(params, 0, 0, stream.size)
        val len = stream.size
        var i = 0
        while (i < len) {
            val cmd = params[i++]
            val id = cmd and 0x7
            val count = cmd ushr 3
            val paramsPerCommand = when (id) {
                CMD_MOVE_TO, CMD_LINE_TO -> 2
                CMD_CLOSE_PATH -> 0
                else -> return // unknown command; abort the rest of the stream
            }
            val totalParams = count * paramsPerCommand
            // Zig-zag-decode just this command's parameter run; varints were already kept as
            // their raw uint32 bits in Int two's complement, so the math works without any
            // UInt conversion.
            for (k in 0 until totalParams) {
                val raw = params[i + k]
                params[i + k] = (raw ushr 1) xor -(raw and 1)
            }
            block(id, count, params, i)
            i += totalParams
        }
    }

    /** A polygon's outer ring with zero or more inner holes, in lat/lon. */
    /** Rings as flat interleaved `[lon°, lat°, …]` degree arrays — avoids a [Position] object per
     *  vertex (thousands per dense tile) and feeds earcut / [MvtBatchedPolygonTile] directly. */
    class PolygonRings(val outer: DoubleArray) {
        /** Hole rings; stays the shared empty list until the first hole - most rings have none. */
        var holes: List<DoubleArray> = emptyList()
            private set

        fun addHole(ring: DoubleArray) {
            (holes as? ArrayList<DoubleArray> ?: ArrayList<DoubleArray>(2).also { holes = it }).add(ring)
        }
    }

    /**
     * Result of [labelAnchorForLine]: where to place a line label and how to orient it.
     * [bearingDeg] is the local tangent bearing in degrees (0 = north, 90 = east), suitable
     * for [earth.worldwind.shape.Label.rotation] with `rotationMode = RELATIVE_TO_GLOBE`.
     */
    data class LineLabelAnchor(val position: Position, val bearingDeg: Double)

    /**
     * Find the midpoint of [line] (by cumulative chord length) and the bearing of the
     * segment containing it. Used by [MvtVectorLayer] to place single labels along
     * LINESTRING features under [MvtStyleRule.LabelPlacement.LINE].
     *
     * Returns null for degenerate input (fewer than 2 vertices or zero total length).
     *
     * Uses local equirectangular bearing (`atan2(Δlon × cos(lat), Δlat)`) — accurate enough
     * for road-segment-scale labels, much cheaper than great-circle azimuth.
     */
    fun labelAnchorForLine(line: List<Position>): LineLabelAnchor? {
        val n = line.size
        if (n < 2) return null

        // Pass 1: total chord length (in degrees — units cancel out).
        var total = 0.0
        val segLen = DoubleArray(n - 1)
        for (i in 0 until n - 1) {
            val a = line[i]
            val b = line[i + 1]
            val dx = b.longitude.inDegrees - a.longitude.inDegrees
            val dy = b.latitude.inDegrees - a.latitude.inDegrees
            val len = sqrt(dx * dx + dy * dy)
            segLen[i] = len
            total += len
        }
        if (total <= 0.0) return null

        // Pass 2: walk segments until we pass the half-length mark.
        val half = total * 0.5
        var acc = 0.0
        var segIdx = 0
        while (segIdx < segLen.size - 1 && acc + segLen[segIdx] < half) {
            acc += segLen[segIdx]
            segIdx++
        }
        val a = line[segIdx]
        val b = line[segIdx + 1]
        val remaining = half - acc
        val t = if (segLen[segIdx] > 0) remaining / segLen[segIdx] else 0.0

        val midLat = a.latitude.inDegrees + (b.latitude.inDegrees - a.latitude.inDegrees) * t
        val midLon = a.longitude.inDegrees + (b.longitude.inDegrees - a.longitude.inDegrees) * t
        val midAlt = a.altitude + (b.altitude - a.altitude) * t

        // Tangent bearing — compute from the same segment we picked. Equirectangular
        // approximation (cos(lat) for longitude scaling).
        val cosLat = cos(midLat * PI / 180.0)
        val dx = (b.longitude.inDegrees - a.longitude.inDegrees) * cosLat
        val dy = b.latitude.inDegrees - a.latitude.inDegrees
        // atan2 returns radians from east-counter-clockwise; convert to north-clockwise
        // (the cartographic bearing convention WorldWind's Label uses).
        var bearing = atan2(dx, dy) * 180.0 / PI
        // Flip 180° if text would otherwise read upside-down. Keep bearing in [-90, 90] so
        // labels always read left-to-right rather than right-to-left.
        if (bearing > 90.0) bearing -= 180.0
        else if (bearing < -90.0) bearing += 180.0

        return LineLabelAnchor(
            Position.fromDegrees(midLat, midLon, midAlt),
            bearing,
        )
    }
}
