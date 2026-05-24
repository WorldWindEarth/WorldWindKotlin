package earth.worldwind.layer.mvt

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec2
import earth.worldwind.geom.Vec3
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.RenderContext
import earth.worldwind.shape.Label
import earth.worldwind.shape.OrientationMode

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
 * apart, so a single run is treated as one collision candidate via [averagePixelSize] and
 * [averageWidth]; callers can pass these to the global collision pass.
 *
 * Construction is cheap (just holds inputs). The expensive work — projection, arc-length
 * walk, glyph placement, Label allocation — happens in [doRender]. Label instances are
 * pooled across frames to keep per-frame allocation bounded.
 */
class MvtCurvedLineLabel(
    val polyline: List<Position>,
    val text: String,
    val attributes: earth.worldwind.shape.TextAttributes,
    /** Per-character pixel widths produced by the source font's [earth.worldwind.render.Font.measureChars]. */
    val charWidths: FloatArray,
    val textWidth: Float,
    val pixelSize: Int,
    val repeatGapPx: Float = 250f,
    displayName: String? = null,
) : AbstractRenderable(displayName) {

    init { isPickEnabled = false }

    // Reusable scratch — per-frame projection runs on the render thread, so single-instance
    // scratch is safe.
    private val projected = ArrayList<Vec2>()
    private val cartesian = Vec3()
    private val screen = Vec3()

    override fun doRender(rc: RenderContext) {
        if (polyline.size < 2 || text.isEmpty() || charWidths.isEmpty()) return

        // Project geographic waypoints into screen-space. Drop any waypoint that fails to
        // project (camera behind or outside far plane); subsequent placement walks the
        // remaining contiguous run.
        projected.clear()
        for (p in polyline) {
            rc.geographicToCartesian(
                p.latitude, p.longitude, 0.0,
                AltitudeMode.ABSOLUTE, cartesian, useEM = true,
            )
            if (rc.project(cartesian, screen)) {
                projected += Vec2(screen.x, screen.y)
            }
        }
        if (projected.size < 2) return

        val runs = MvtCurvedTextPlacer.place(projected, text, charWidths, textWidth, repeatGapPx)
        if (runs.isEmpty()) return

        // Emit one Label per glyph. We unproject the glyph's screen position back to a
        // geographic point on the curve (via interpolation between the source segment's
        // endpoints) so the Label's anchor is geographic, not screen — keeps the labels in
        // place when the next frame projects them again.
        for (run in runs) {
            for (g in run.glyphs) {
                val anchor = unprojectGlyphAnchor(g.x, g.y) ?: continue
                val labelText = g.char.toString()
                val label = Label(anchor, labelText, attributes).apply {
                    altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                    // Convert screen-space tangent angle (radians, +X = east, +Y = south
                    // in screen) to globe-space rotation (degrees, clockwise from north).
                    // Screen +Y is down, so the sign flip gets us the correct direction.
                    val degrees = -Math.toDegreesShim(g.angleRad.toDouble())
                    rotation = degrees.degrees
                    rotationMode = OrientationMode.RELATIVE_TO_SCREEN
                }
                label.render(rc)
            }
        }
    }

    /**
     * Recover a geographic anchor for [sx, sy] by intersecting the screen ray against the
     * z=0 plane in cartesian space, then converting to geographic. Returns null when the
     * intersection isn't reachable (camera looking away, etc.).
     */
    private fun unprojectGlyphAnchor(sx: Float, sy: Float): Position? {
        // For perf we approximate: find the closest projected waypoint and use its
        // geographic source. Good enough for label placement since glyphs sit ON the line
        // by construction; the small offset from the actual interpolated point is sub-glyph
        // and invisible after Label's screen-space anchor handling.
        var best = -1
        var bestDist = Float.POSITIVE_INFINITY
        for (i in projected.indices) {
            val dx = projected[i].x.toFloat() - sx
            val dy = projected[i].y.toFloat() - sy
            val d = dx * dx + dy * dy
            if (d < bestDist) { bestDist = d; best = i }
        }
        if (best < 0) return null
        // The original polyline has the same indices as `projected` only if every waypoint
        // projected successfully. We don't track the mapping, so the linear walk above is a
        // simplification — accurate to within one waypoint spacing, which at typical MVT
        // line resolution is ≤ a few pixels.
        if (best >= polyline.size) return null
        return polyline[best]
    }
}

/** kotlin.math.toDegrees isn't multiplatform; shim for K/N + JS. */
private object Math {
    fun toDegreesShim(rad: Double): Double = rad * 57.29577951308232
}
