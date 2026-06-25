package earth.worldwind.layer.mvt

import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.RenderContext
import earth.worldwind.shape.Label

/**
 * Per-tile bundle of [Label]s for one slippy tile, plus parallel priority and pixel-size arrays that
 * the layer's cross-tile collision pass reads. The pass writes [enabledMask]; [doRender] then emits
 * only the masked survivors. All collision / dedup / budget logic lives in [MvtLabelCollider] as one
 * global pass per frame, so groups never collide per-tile.
 */
class MvtLabelGroup(
    internal val labels: List<Label>,
    internal val priorities: IntArray,
    internal val pixelSizes: IntArray,
    /** Measured rendered pixel width per label (exact glyph advances) for the collision pass. */
    internal val widths: FloatArray,
    /** Per-label data-driven minzoom (the slippy zoom at which the place becomes eligible). The collision
     *  pass's stage-1 gate shows a label only where the effective zoom at its distance reaches this. */
    internal val minZooms: IntArray,
    /** Per-label MVT feature id (0 when omitted) for the collision pass's cross-tile id dedup — a place
     *  clipped across a coarse parent + its finer child keeps one id → render once. */
    internal val featureIds: LongArray,
    displayName: String? = null,
) : AbstractRenderable(displayName) {

    init {
        require(
            labels.size == priorities.size && labels.size == pixelSizes.size &&
                labels.size == widths.size && labels.size == minZooms.size && labels.size == featureIds.size
        ) {
            "MvtLabelGroup: labels (${labels.size}), priorities (${priorities.size}), pixelSizes " +
                "(${pixelSizes.size}), widths (${widths.size}), minZooms (${minZooms.size}), featureIds " +
                "(${featureIds.size}) must match."
        }
        isPickEnabled = false
    }

    /** Set by [MvtVectorLayer]'s cross-tile collision pass each frame: render only labels whose bit
     *  is `true`. Null (no pass ran, or no labels) renders nothing. */
    var enabledMask: BooleanArray? = null

    override fun doRender(rc: RenderContext) {
        val mask = enabledMask ?: return
        for (i in labels.indices) if (mask[i]) labels[i].render(rc)
    }
}
