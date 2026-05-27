package earth.worldwind.layer.mvt

import earth.worldwind.geom.Vec2
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Arc-length parameterised glyph placement along a polyline. Given a polyline in screen
 * coordinates, a text string with its per-character advance widths in pixels, and a desired
 * label-repeat interval, produces one [GlyphRun] per repeat position covering as much of
 * the line as fits.
 *
 * Each [GlyphRun] holds the per-glyph (screen position, tangent angle) pairs so the caller
 * can emit one rotated [earth.worldwind.shape.Label] per glyph. The first repeat starts
 * with enough margin from the line's start that the label's left edge sits on or after the
 * line's first vertex; subsequent repeats begin at `previousRunEnd + repeatGapPx`.
 *
 * Curvature handling: each glyph's tangent angle is sampled at its position along the curve
 * (the segment containing its arc-length offset). On a sinuous river this produces a
 * snake-along-the-curve effect; on a straight road every glyph in a run shares the same
 * angle.
 *
 * The placer is pure: no global state, no allocations beyond the returned list.
 */
object MvtCurvedTextPlacer {

    class GlyphPlacement(
        val char: Char,
        val x: Float,
        val y: Float,
        /** Tangent angle in radians, measured clockwise from +X (screen east). */
        val angleRad: Float,
    )

    class GlyphRun(
        val glyphs: List<GlyphPlacement>,
        /** Centroid of the run's glyph positions — used for collision bbox center. */
        val centerX: Float,
        val centerY: Float,
    )

    /**
     * Lay out [text] along [polyline] using one [GlyphRun] per repeat. Returns empty when
     * the polyline is too short to fit a single full repeat (length < textWidth).
     *
     * @param polyline    Screen-space waypoints (typically projected from geographic via
     *                    `rc.project(...)`). Z is ignored; we treat the curve as 2D in screen.
     *                    At least 2 points required, otherwise returns empty list.
     * @param charWidths  Per-character advance widths in pixels, parallel to [text].
     * @param textWidth   Total width of [text] in pixels (sum of [charWidths]). Passed
     *                    separately to avoid recomputation by callers that already have it.
     * @param repeatGapPx Gap between the end of one label's last glyph and the start of the
     *                    next repeat's first glyph. Mapbox's `symbol-spacing` default is 250 px.
     */
    fun place(
        polyline: List<Vec2>,
        text: String,
        charWidths: FloatArray,
        textWidth: Float,
        repeatGapPx: Float = 250f,
    ): List<GlyphRun> {
        if (polyline.size < 2 || text.isEmpty() || textWidth <= 0f) return emptyList()
        val n = polyline.size

        // Pre-compute cumulative arc length so we can map (arc-length t) → (segment i, t in [0,1]).
        val cum = FloatArray(n)
        var total = 0f
        for (i in 1 until n) {
            val dx = (polyline[i].x - polyline[i - 1].x).toFloat()
            val dy = (polyline[i].y - polyline[i - 1].y).toFloat()
            total += sqrt(dx * dx + dy * dy)
            cum[i] = total
        }
        if (total < textWidth) return emptyList()

        // Compute the number of labels that fit at `textWidth + repeatGapPx` per cycle, then
        // distribute leftover length as equal padding at each end so the labels are visually
        // centred. For a single-fit line this collapses to the same centred placement; for a
        // long line it packs N labels with matched margins at both ends.
        val cycle = textWidth + repeatGapPx
        val maxLabels = ((total + repeatGapPx) / cycle).toInt().coerceAtLeast(1)
        val occupied = maxLabels * cycle - repeatGapPx
        var cursor = (total - occupied).coerceAtLeast(0f) * 0.5f
        val runs = mutableListOf<GlyphRun>()
        while (cursor + textWidth <= total) {
            val glyphs = ArrayList<GlyphPlacement>(text.length)
            var sumX = 0f; var sumY = 0f
            var glyphCursor = cursor
            for (ci in text.indices) {
                val w = charWidths[ci]
                // Glyph anchor sits at its left-edge along the curve. Sample the curve at
                // that arc length; angle from the segment containing that point.
                val (sx, sy, ang) = sampleCurve(polyline, cum, glyphCursor + w * 0.5f)
                glyphs += GlyphPlacement(text[ci], sx, sy, ang)
                sumX += sx; sumY += sy
                glyphCursor += w
            }
            runs += GlyphRun(
                glyphs = glyphs,
                centerX = sumX / glyphs.size,
                centerY = sumY / glyphs.size,
            )
            cursor = glyphCursor + repeatGapPx
        }
        return runs
    }

    /**
     * Returns (x, y, angle) at arc-length [t] along [polyline]. [cum] is the cumulative-
     * length table built by the caller. Handles t outside [0, total] by clamping.
     */
    private fun sampleCurve(
        polyline: List<Vec2>, cum: FloatArray, t: Float,
    ): Triple<Float, Float, Float> {
        val total = cum.last()
        val clamped = t.coerceIn(0f, total)
        // Binary search for the segment whose cumulative-length window contains [clamped].
        var lo = 0
        var hi = cum.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cum[mid + 1] < clamped) lo = mid + 1 else hi = mid
        }
        // lo is now the segment index s.t. cum[lo] <= clamped <= cum[lo+1].
        val segStart = cum[lo]
        val segLen = cum[lo + 1] - segStart
        val u = if (segLen == 0f) 0f else (clamped - segStart) / segLen
        val ax = polyline[lo].x.toFloat()
        val ay = polyline[lo].y.toFloat()
        val bx = polyline[lo + 1].x.toFloat()
        val by = polyline[lo + 1].y.toFloat()
        val x = ax + (bx - ax) * u
        val y = ay + (by - ay) * u
        val angle = atan2(by - ay, bx - ax)
        return Triple(x, y, angle)
    }

    /**
     * Rotate the per-glyph anchor offsets into screen space. Useful when emitting one
     * Label per glyph and you need to compute the glyph's screen bbox for collision.
     * Returns (dx, dy) screen offsets from the anchor [centerX, centerY] for a glyph that
     * would otherwise sit at the anchor with width [glyphWidth] and height [glyphHeight].
     */
    fun rotatedGlyphCorners(
        anchorX: Float, anchorY: Float, angleRad: Float,
        glyphWidth: Float, glyphHeight: Float,
        out: FloatArray,
    ) {
        val c = cos(angleRad)
        val s = sin(angleRad)
        val hw = glyphWidth * 0.5f
        val hh = glyphHeight * 0.5f
        // Corner order: TL, TR, BR, BL.
        val cornersX = floatArrayOf(-hw, hw, hw, -hw)
        val cornersY = floatArrayOf(-hh, -hh, hh, hh)
        for (i in 0 until 4) {
            out[i * 2] = anchorX + cornersX[i] * c - cornersY[i] * s
            out[i * 2 + 1] = anchorY + cornersX[i] * s + cornersY[i] * c
        }
    }
}
