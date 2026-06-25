package earth.worldwind.layer.mvt

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Offset
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec2
import earth.worldwind.geom.Vec3
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.RenderContext
import earth.worldwind.shape.Label
import earth.worldwind.shape.OrientationMode
import earth.worldwind.shape.TextAttributes

/**
 * Per-glyph baseline-following text label for one MVT LINESTRING feature. Each render-pass
 * projects the polyline's geographic waypoints into screen space, runs the arc-length
 * placer to produce one [MvtCurvedTextPlacer.GlyphRun] per repeat, then emits a single-
 * character [Label] per glyph with its own rotation matching the local tangent.
 *
 * This is the "snake-along-the-curve" treatment that Mapbox's `symbol-placement: line`
 * implements — visible improvement over [MvtLabelGroup]'s single line-center label on
 * sinuous features (rivers, winding roads). Repeats every [repeatGapPx] pixels of arc
 * length so a long feature carries multiple legibility points.
 *
 * Collision: each [Label] participates in [MvtLabelGroup]'s screen-space collision via its
 * own bounding box. Per-glyph collision would tear adjacent characters of the same word
 * apart, so a single run is treated as one collision candidate via the average pixel size
 * and width across its glyphs; callers can pass these to the global collision pass.
 *
 * Construction is cheap (just holds inputs). The expensive work — projection, the arc-length
 * walk and glyph placement — happens in [doRender]. The per-glyph [Label]s and the projection
 * [Vec2]s are pooled and mutated in place across frames (the same persistent-Label pattern
 * [MvtLabelGroup] uses), so steady-state per-frame allocation is bounded to the placer's transient
 * output rather than the ~120 short-lived objects/label/frame a fresh-allocate path would churn.
 */
class MvtCurvedLineLabel(
    val coords: DoubleArray,  // flat [lon°, lat°, …] waypoints (surface altitude 0)
    val text: String,
    val attributes: TextAttributes,
    /** Per-character pixel widths produced by the source font's [earth.worldwind.render.Font.measureChars]. */
    val charWidths: FloatArray,
    val textWidth: Float,
    val pixelSize: Int,
    /** Collision priority (higher wins) by road/water class; read by [MvtLabelCollider]'s line phase. */
    val priority: Int = 0,
    /** Source feature id (OSM way id), 0 when the source omits it. The collision pass dedups by id
     *  (a way clipped across tiles keeps one id) when non-zero, else by text + screen proximity. */
    val featureId: Long = 0L,
    val repeatGapPx: Float = 1100f, // wide (Mapbox default 250) so a long road shows its name 1–2×, not a chain
    displayName: String? = null,
) : AbstractRenderable(displayName) {

    init { isPickEnabled = false }

    /** Set each frame by [MvtLabelCollider]'s line collision pass BEFORE render: false culls this label.
     *  Defaults true so labels still show if no pass runs (the pass sets all false, then survivors true). */
    var collisionEnabled = true

    // Reusable scratch + pools — per-frame projection runs on the render thread, so single-instance
    // scratch is safe. [vecPool] and [labelPool] grow to the max seen and are mutated in place each frame
    // (never reallocated); [Label.render] consumes position+text by value, so cross-frame reuse is safe.
    private val projected = ArrayList<Vec2>()          // screen waypoints — references into [vecPool]
    private val vecPool = ArrayList<Vec2>()            // grow-and-reuse projected-waypoint Vec2 instances
    private val projectedGeo = ArrayList<Position>()   // geographic source, parallel to [projected]
    private val geoPool = ArrayList<Position>()        // grow-and-reuse geographic-waypoint Position instances
    private val labelPool = ArrayList<Label>()         // one reused Label per glyph slot, mutated per frame
    private val charText = HashMap<Char, String>()     // single-char Label text, built once per distinct char
    private val cartesian = Vec3()
    private val screen = Vec3()

    // Line labels sit centered ON the line, not above it. The default Offset.bottomCenter (correct for a
    // city name floating above its dot) anchors the glyph's bottom on the line, pushing the name off to one
    // side; center() runs the line through the glyph's middle (and pivots rotation about its centre).
    private val centeredAttributes = TextAttributes(attributes).apply { textOffset = Offset.center() }

    override fun doRender(rc: RenderContext) {
        if (!collisionEnabled) return // culled this frame by the cross-tile line collision pass
        if (coords.size < 4 || text.isEmpty() || charWidths.isEmpty()) return

        // Project geographic waypoints into screen-space. Drop any waypoint that fails to
        // project (camera behind or outside far plane); subsequent placement walks the
        // remaining contiguous run.
        projected.clear()
        projectedGeo.clear()
        var pc = 0
        val vc = coords.size / 2  // flat [lon°, lat°, …]: lon = coords[i*2], lat = coords[i*2+1]
        for (i in 0 until vc) {
            val lon = coords[i * 2]
            val lat = coords[i * 2 + 1]
            rc.geographicToCartesian(
                lat.degrees, lon.degrees, 0.0,
                AltitudeMode.ABSOLUTE, cartesian, useEM = true,
            )
            if (rc.project(cartesian, screen)) {
                val v = if (pc < vecPool.size) vecPool[pc] else Vec2().also { vecPool.add(it) }
                v.x = screen.x
                v.y = screen.y
                projected.add(v)
                // Keep geographic source parallel so glyphs interpolate, not snap (pooled to avoid a Position alloc per waypoint per frame).
                val g = if (pc < geoPool.size) geoPool[pc] else Position().also { geoPool.add(it) }
                g.setDegrees(lat, lon, 0.0)
                projectedGeo.add(g)
                pc++
            }
        }
        if (projected.size < 2) return
        // Normalize reading direction: reverse a net right-to-left line so glyphs read left-to-right and
        // upright (else mirrored/upside-down text). projectedGeo reversed in lockstep to keep the mapping.
        if (projected.last().x < projected.first().x) {
            projected.reverse()
            projectedGeo.reverse()
        }

        // HiDPI: JVM/iOS render glyph bitmaps at densityFactor, but charWidths are logical advances;
        // scale all pixel-unit placement inputs to match so glyphs don't condense/overlap.
        val gs = curvedGlyphAdvanceScale(rc)
        val placeWidths = if (gs == 1f) charWidths else FloatArray(charWidths.size) { charWidths[it] * gs }
        val runs = MvtCurvedTextPlacer.place(projected, text, placeWidths, textWidth * gs, repeatGapPx * gs)
        if (runs.isEmpty()) return

        // Emit one pooled Label per glyph, its position interpolated along the source segment (so it stays
        // put when the next frame reprojects). [gi] is a flat slot index across all runs/glyphs, so each
        // glyph this frame gets a distinct pooled Label; the pool persists and is mutated across frames.
        var gi = 0
        for (run in runs) {
            for (g in run.glyphs) {
                val seg = g.segIndex
                if (seg < 0 || seg + 1 >= projectedGeo.size) continue
                val a = projectedGeo[seg]
                val b = projectedGeo[seg + 1]
                val u = g.segU
                val lat = a.latitude.inDegrees + (b.latitude.inDegrees - a.latitude.inDegrees) * u
                val lon = a.longitude.inDegrees + (b.longitude.inDegrees - a.longitude.inDegrees) * u
                val label = pooledLabel(gi++)
                label.position.setDegrees(lat, lon, 0.0)
                label.text = charText.getOrPut(g.char) { g.char.toString() }
                // Convert screen-space tangent angle (radians, +Y = south/down in screen) to globe-space
                // rotation (degrees, clockwise from north); the sign flip corrects for screen-Y-down.
                label.rotation = (-Math.toDegreesShim(g.angleRad.toDouble())).degrees
                label.render(rc)
            }
        }
    }

    /**
     * Pooled single-glyph [Label] for flat slot [idx], grown on demand and reused across frames. Fixed
     * per-glyph state (attributes, altitude + rotation mode) is set once at creation; position, text and
     * rotation are mutated each frame. The caller interpolates the glyph's exact geographic anchor from
     * [projectedGeo] (parallel to the placer's screen polyline) — no nearest-vertex snapping, which piled
     * every glyph between two vertices onto one point = stacked characters.
     */
    private fun pooledLabel(idx: Int): Label {
        while (idx >= labelPool.size) {
            labelPool.add(
                Label(Position.fromDegrees(0.0, 0.0, 0.0), null, centeredAttributes).apply {
                    altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                    rotationMode = OrientationMode.RELATIVE_TO_SCREEN
                }
            )
        }
        return labelPool[idx]
    }
}

/** kotlin.math.toDegrees isn't multiplatform; shim for K/N + JS. */
private object Math {
    fun toDegreesShim(rad: Double): Double = rad * 57.29577951308232
}
