package earth.worldwind.layer.mvt

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Vec3
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.RenderContext
import earth.worldwind.shape.Label

/**
 * Per-tile label collision group. Holds every [Label] for one slippy tile + parallel
 * priority and pixel-size arrays, and decides on each render pass which labels are visible
 * based on screen-space bbox overlap. Greedy first-fit: project, estimate bbox, sort by
 * priority descending, accept each if it doesn't overlap any already-accepted.
 *
 * The layer's cross-tile pass overrides per-tile collision by writing [enabledMask].
 *
 * Bbox is estimated from `(text.length × pixelSize × 0.55) ✕ (pixelSize × 1.3)`. Tighter
 * than reality so collision over-suppresses slightly; exact metrics would require platform
 * glyph measurement.
 */
class MvtLabelGroup(
    internal val labels: List<Label>,
    internal val priorities: IntArray,
    internal val pixelSizes: IntArray,
    displayName: String? = null,
) : AbstractRenderable(displayName) {

    init {
        require(labels.size == priorities.size && labels.size == pixelSizes.size) {
            "MvtLabelGroup: labels (${labels.size}), priorities (${priorities.size}), and " +
                "pixelSizes (${pixelSizes.size}) must be the same length."
        }
        isPickEnabled = false
    }

    /**
     * When non-null, [doRender] renders only labels at indices where this mask is `true` —
     * set by [MvtVectorLayer]'s cross-tile collision pass. Null falls back to local
     * per-tile collision.
     */
    var enabledMask: BooleanArray? = null

    private val cartesian = Vec3()
    private val screen = Vec3()
    // Flat bbox storage: 4 floats per label (x1, y1, x2, y2). [Float.NaN] in slot 0 marks
    // an off-screen label (project failed or behind viewer).
    private val bboxes = FloatArray(labels.size * 4)
    // Accepted indices, packed; size tracked separately to avoid per-frame ArrayList alloc.
    private val acceptedIndices = IntArray(labels.size)
    // Indices sorted by priority descending (computed once at construction since priority is
    // immutable). Higher-priority labels take precedence on collision.
    private val sortedIndices: IntArray = labels.indices
        .sortedByDescending { priorities[it] }
        .toIntArray()

    override fun doRender(rc: RenderContext) {
        if (labels.isEmpty()) return

        // If the layer's cross-tile pass supplied a mask, render only the enabled subset.
        val mask = enabledMask
        if (mask != null) {
            for (i in labels.indices) if (mask[i]) labels[i].render(rc)
            return
        }

        // 1) Project each label and compute its screen-space bbox.
        for (i in labels.indices) {
            val label = labels[i]
            val pos = label.position
            // Project at sea level; faster than CLAMP_TO_GROUND (no elevation lookup) and
            // sufficient for screen-space bbox collision.
            rc.geographicToCartesian(
                pos.latitude, pos.longitude, 0.0,
                AltitudeMode.ABSOLUTE, cartesian, useEM = true,
            )
            if (!rc.project(cartesian, screen)) {
                bboxes[i * 4] = Float.NaN
                continue
            }
            val size = pixelSizes[i]
            val text = label.text
            val textLen = text?.length ?: 0
            if (textLen == 0) {
                bboxes[i * 4] = Float.NaN
                continue
            }
            val w = textLen * size * GLYPH_AVG_WIDTH_RATIO
            val h = size * LINE_HEIGHT_RATIO
            val cx = screen.x.toFloat()
            // [Label] uses `Offset.bottomCenter()` by default, so the anchor sits at the
            // baseline midpoint and text extends upward — bbox centre is half a height
            // above the anchor.
            val cy = screen.y.toFloat() - h * 0.5f
            bboxes[i * 4]     = cx - w * 0.5f - PADDING_PX
            bboxes[i * 4 + 1] = cy - h * 0.5f - PADDING_PX
            bboxes[i * 4 + 2] = cx + w * 0.5f + PADDING_PX
            bboxes[i * 4 + 3] = cy + h * 0.5f + PADDING_PX
        }

        // 2) Greedy accept-by-priority. Stored in [acceptedIndices][0..acceptedCount).
        var acceptedCount = 0
        for (idx in sortedIndices) {
            val x1 = bboxes[idx * 4]
            if (x1.isNaN()) continue
            val y1 = bboxes[idx * 4 + 1]
            val x2 = bboxes[idx * 4 + 2]
            val y2 = bboxes[idx * 4 + 3]
            var collides = false
            for (j in 0 until acceptedCount) {
                val aIdx = acceptedIndices[j]
                val ax1 = bboxes[aIdx * 4]
                val ay1 = bboxes[aIdx * 4 + 1]
                val ax2 = bboxes[aIdx * 4 + 2]
                val ay2 = bboxes[aIdx * 4 + 3]
                if (x1 < ax2 && x2 > ax1 && y1 < ay2 && y2 > ay1) {
                    collides = true
                    break
                }
            }
            if (!collides) acceptedIndices[acceptedCount++] = idx
        }

        for (k in 0 until acceptedCount) {
            labels[acceptedIndices[k]].render(rc)
        }
    }

    companion object {
        // Mean glyph width as a fraction of font size. Latin Sans-Serif at typical
        // proportional widths sits around 0.50-0.55; we err high so collision over-rejects
        // rather than under-rejects.
        private const val GLYPH_AVG_WIDTH_RATIO = 0.55f
        // Line height as a fraction of font size. Typical metrics span 1.2-1.4; 1.3 gives a
        // reasonable upper-bound bbox without claiming so much vertical space that adjacent
        // labels in different lines always collide.
        private const val LINE_HEIGHT_RATIO = 1.3f
        // Extra pixels added to each bbox side so two accepted labels don't visually touch.
        // Tuned to read with the LABEL_HALO_W_LARGE = 4 px halo without leaving visible
        // gaps disproportionate to font size.
        private const val PADDING_PX = 3f
    }
}
