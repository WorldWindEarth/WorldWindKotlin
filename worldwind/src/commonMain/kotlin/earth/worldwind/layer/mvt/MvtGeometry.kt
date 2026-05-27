package earth.worldwind.layer.mvt

import earth.worldwind.geom.Position
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

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

    /** Inflate a POINT feature's geometry into one [Position] per point. Empty on malformed input. */
    fun decodePoints(feature: MvtFeature, z: Int, x: Int, y: Int, extent: Int): List<Position> {
        if (feature.type != MvtGeometryType.POINT) return emptyList()
        val out = ArrayList<Position>()
        var cursorX = 0
        var cursorY = 0
        walkCommands(feature.geometry) { id, count, params, offset ->
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

    /** Inflate a LINESTRING feature into one or more polylines (each MoveTo starts a new one). */
    fun decodeLines(feature: MvtFeature, z: Int, x: Int, y: Int, extent: Int): List<List<Position>> {
        if (feature.type != MvtGeometryType.LINESTRING) return emptyList()
        val lines = ArrayList<MutableList<Position>>()
        var current: MutableList<Position>? = null
        var cursorX = 0
        var cursorY = 0
        walkCommands(feature.geometry) { id, count, params, offset ->
            when (id) {
                CMD_MOVE_TO -> {
                    cursorX += params[offset]
                    cursorY += params[offset + 1]
                    val started = ArrayList<Position>()
                    started += unproject(z, x, y, extent, cursorX, cursorY)
                    current = started
                    lines += started
                }
                CMD_LINE_TO -> {
                    val line = current ?: return@walkCommands
                    for (i in 0 until count) {
                        cursorX += params[offset + 2 * i]
                        cursorY += params[offset + 2 * i + 1]
                        line += unproject(z, x, y, extent, cursorX, cursorY)
                    }
                }
            }
        }
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
    fun decodePolygons(feature: MvtFeature, z: Int, x: Int, y: Int, extent: Int): List<PolygonRings> {
        if (feature.type != MvtGeometryType.POLYGON) return emptyList()
        val polygons = ArrayList<PolygonRings>()
        var ringTileX: ArrayList<Int>? = null
        var ringTileY: ArrayList<Int>? = null
        var ringLatLon: ArrayList<Position>? = null
        var cursorX = 0
        var cursorY = 0
        walkCommands(feature.geometry) { id, count, params, offset ->
            when (id) {
                CMD_MOVE_TO -> {
                    cursorX += params[offset]
                    cursorY += params[offset + 1]
                    ringTileX = arrayListOf(cursorX)
                    ringTileY = arrayListOf(cursorY)
                    ringLatLon = arrayListOf(unproject(z, x, y, extent, cursorX, cursorY))
                }
                CMD_LINE_TO -> {
                    val tx = ringTileX ?: return@walkCommands
                    val ty = ringTileY ?: return@walkCommands
                    val ll = ringLatLon ?: return@walkCommands
                    for (i in 0 until count) {
                        cursorX += params[offset + 2 * i]
                        cursorY += params[offset + 2 * i + 1]
                        tx += cursorX
                        ty += cursorY
                        ll += unproject(z, x, y, extent, cursorX, cursorY)
                    }
                }
                CMD_CLOSE_PATH -> {
                    val tx = ringTileX
                    val ty = ringTileY
                    val ll = ringLatLon
                    if (tx == null || ty == null || ll == null) return@walkCommands
                    ringTileX = null; ringTileY = null; ringLatLon = null
                    if (ll.size < 3) return@walkCommands
                    // Tile-space signed area (Shoelace): positive ⇒ exterior (spec §4.3.3.3),
                    // negative ⇒ interior hole. Constant factor doesn't matter, only sign.
                    var area = 0.0
                    for (i in tx.indices) {
                        val j = (i + 1) % tx.size
                        area += tx[i].toDouble() * ty[j] - tx[j].toDouble() * ty[i]
                    }
                    if (area > 0.0) {
                        polygons += PolygonRings(ll, mutableListOf())
                    } else {
                        // A hole with no preceding exterior is malformed input; silently drop.
                        polygons.lastOrNull()?.holes?.add(ll)
                    }
                }
            }
        }
        return polygons
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
        val lonDeg = tx / n * 360.0 - 180.0
        val latDeg = atan(sinh(PI * (1 - 2 * ty / n))) * 180.0 / PI
        return Position.fromDegrees(latDeg, lonDeg, 0.0)
    }

    private const val CMD_MOVE_TO = 1
    private const val CMD_LINE_TO = 2
    private const val CMD_CLOSE_PATH = 7

    /**
     * Walk a command stream, invoking [block] once per command with the decoded `id`, `count`,
     * a zig-zag-decoded parameter array, and the offset of that command's parameters.
     * [block] receives the same `params` array on every call — don't retain references across
     * calls; the contents are owned by this walker.
     */
    private inline fun walkCommands(
        stream: IntArray,
        block: (id: Int, count: Int, params: IntArray, offset: Int) -> Unit,
    ) {
        if (stream.isEmpty()) return
        val params = stream.copyOf()
        var i = 0
        while (i < params.size) {
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
    data class PolygonRings(val outer: List<Position>, val holes: MutableList<List<Position>>)

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
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
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
        val cosLat = kotlin.math.cos(midLat * PI / 180.0)
        val dx = (b.longitude.inDegrees - a.longitude.inDegrees) * cosLat
        val dy = b.latitude.inDegrees - a.latitude.inDegrees
        // atan2 returns radians from east-counter-clockwise; convert to north-clockwise
        // (the cartographic bearing convention WorldWind's Label uses).
        var bearing = kotlin.math.atan2(dx, dy) * 180.0 / PI
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
