package earth.worldwind.layer.mvt

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Vec3
import earth.worldwind.render.RenderContext
import earth.worldwind.shape.Label

/**
 * Cross-tile label collision pass for [MvtVectorLayer]. Walks every label across every
 * visible [MvtLabelGroup], computes a screen-space bbox per label, sorts by priority
 * (with a stickiness bonus for last-frame survivors), then greedily accepts
 * non-overlapping bboxes top-down. Each group's [MvtLabelGroup.enabledMask] is updated
 * to reflect the accepted set so the group's render path emits exactly the survivors.
 *
 * Stickiness exists because at borderline overlaps a tiny camera move can flip which
 * label "wins" the collision, making the loser blink between frames. Last-frame-visible
 * labels get a small priority bonus so they keep winning ties — see [STICKINESS_BONUS].
 *
 * Scratch buffers are grown geometrically and reused frame-to-frame; the per-frame label
 * sets are double-buffered so [endFrame] just swaps references. Zero per-frame allocation
 * under steady state.
 *
 * O(N²) where N = total labels across visible tiles. Caller should fall back to per-tile
 * collision when N exceeds a budget (the global pass would dominate the frame budget at
 * unbounded N).
 */
internal class MvtLabelCollider {

    private var capacity = 0
    private var groupIdx = IntArray(0)
    private var labelIdx = IntArray(0)
    private var effPrio = IntArray(0)
    private var order = IntArray(0)
    private var bboxX1 = FloatArray(0)
    private var bboxY1 = FloatArray(0)
    private var bboxX2 = FloatArray(0)
    private var bboxY2 = FloatArray(0)
    private var visible = BooleanArray(0)
    private var accX1 = FloatArray(0)
    private var accY1 = FloatArray(0)
    private var accX2 = FloatArray(0)
    private var accY2 = FloatArray(0)

    /** Labels accepted in the previous frame, read by this frame's stickiness check. */
    private var lastFrameVisible: HashSet<Label> = HashSet()
    /** Labels accepted in this frame. Swapped with [lastFrameVisible] by [endFrame]. */
    private var thisFrameVisible: HashSet<Label> = HashSet()

    private val cartesian = Vec3()
    private val screen = Vec3()

    /**
     * Run the collision pass. Updates each group's `enabledMask` in place. Caller is
     * expected to have computed [totalLabels] (summed across [activeLabelGroups]) so we
     * can right-size scratch arrays in one go.
     */
    fun run(rc: RenderContext, activeLabelGroups: List<MvtLabelGroup>, totalLabels: Int) {
        if (totalLabels == 0) {
            for (g in activeLabelGroups) g.enabledMask = null
            return
        }
        ensureCapacity(totalLabels)

        // Viewport bounds for the off-screen early-out. A label whose anchor sits more than
        // VIEWPORT_LABEL_MARGIN pixels past any edge is dropped before bbox compute —
        // perfectly invisible without wasting collision cycles on it.
        val vp = rc.viewport
        val vpMinX = vp.x - VIEWPORT_LABEL_MARGIN
        val vpMinY = vp.y - VIEWPORT_LABEL_MARGIN
        val vpMaxX = vp.x + vp.width + VIEWPORT_LABEL_MARGIN
        val vpMaxY = vp.y + vp.height + VIEWPORT_LABEL_MARGIN

        var ci = 0
        for (gi in activeLabelGroups.indices) {
            val group = activeLabelGroups[gi]
            val existing = group.enabledMask
            val mask = if (existing != null && existing.size == group.labels.size) existing
            else BooleanArray(group.labels.size).also { group.enabledMask = it }
            for (i in mask.indices) mask[i] = false

            for (li in group.labels.indices) {
                val label = group.labels[li]
                val pos = label.position
                rc.geographicToCartesian(
                    pos.latitude, pos.longitude, 0.0,
                    AltitudeMode.ABSOLUTE, cartesian, useEM = true,
                )
                groupIdx[ci] = gi
                labelIdx[ci] = li
                val stickyBonus = if (label in lastFrameVisible) STICKINESS_BONUS else 0
                effPrio[ci] = group.priorities[li] + stickyBonus

                if (!rc.project(cartesian, screen)) {
                    visible[ci] = false; ci++; continue
                }
                val sx = screen.x
                val sy = screen.y
                if (sx < vpMinX || sx > vpMaxX || sy < vpMinY || sy > vpMaxY) {
                    visible[ci] = false; ci++; continue
                }
                val size = group.pixelSizes[li]
                val textLen = label.text?.length ?: 0
                if (textLen == 0) {
                    visible[ci] = false; ci++; continue
                }
                val w = textLen * size * COLLISION_GLYPH_W
                val h = size * COLLISION_LINE_H
                val cx = sx.toFloat()
                val cy = sy.toFloat() - h * 0.5f
                bboxX1[ci] = cx - w * 0.5f - COLLISION_PAD
                bboxY1[ci] = cy - h * 0.5f - COLLISION_PAD
                bboxX2[ci] = cx + w * 0.5f + COLLISION_PAD
                bboxY2[ci] = cy + h * 0.5f + COLLISION_PAD
                visible[ci] = true
                ci++
            }
        }

        // Insertion sort over [order] by [effPrio] descending; primitive-typed indices
        // keep this allocation-free (sortedByDescending would box Int to Integer).
        for (i in 0 until totalLabels) order[i] = i
        for (i in 1 until totalLabels) {
            val cur = order[i]
            val curPrio = effPrio[cur]
            var j = i - 1
            while (j >= 0 && effPrio[order[j]] < curPrio) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = cur
        }

        // Greedy accept-if-no-overlap.
        var accCount = 0
        for (k in 0 until totalLabels) {
            val idx = order[k]
            if (!visible[idx]) continue
            val x1 = bboxX1[idx]
            val y1 = bboxY1[idx]
            val x2 = bboxX2[idx]
            val y2 = bboxY2[idx]
            var collides = false
            for (j in 0 until accCount) {
                if (x1 < accX2[j] && x2 > accX1[j] && y1 < accY2[j] && y2 > accY1[j]) {
                    collides = true; break
                }
            }
            if (collides) continue
            accX1[accCount] = x1; accY1[accCount] = y1
            accX2[accCount] = x2; accY2[accCount] = y2
            accCount++
            val gi = groupIdx[idx]
            val li = labelIdx[idx]
            activeLabelGroups[gi].enabledMask!![li] = true
            thisFrameVisible += activeLabelGroups[gi].labels[li]
        }
    }

    /** Force every group's enabledMask to null — used when the caller falls back to
     *  per-tile collision because the candidate count exceeded budget. */
    fun disableAll(activeLabelGroups: List<MvtLabelGroup>) {
        for (g in activeLabelGroups) g.enabledMask = null
    }

    /** Swap the visibility sets so this frame's accepted labels become next frame's
     *  stickiness candidates. Call after the group render pass. */
    fun endFrame() {
        val tmp = lastFrameVisible
        lastFrameVisible = thisFrameVisible
        thisFrameVisible = tmp
        thisFrameVisible.clear()
    }

    private fun ensureCapacity(n: Int) {
        if (n <= capacity) return
        // Grow geometrically (2x) so collision sets that gradually expand don't repeatedly
        // hit the resize path.
        val cap = maxOf(n, capacity * 2, 16)
        capacity = cap
        groupIdx = IntArray(cap)
        labelIdx = IntArray(cap)
        effPrio = IntArray(cap)
        order = IntArray(cap)
        bboxX1 = FloatArray(cap)
        bboxY1 = FloatArray(cap)
        bboxX2 = FloatArray(cap)
        bboxY2 = FloatArray(cap)
        visible = BooleanArray(cap)
        accX1 = FloatArray(cap)
        accY1 = FloatArray(cap)
        accX2 = FloatArray(cap)
        accY2 = FloatArray(cap)
    }

    private companion object {
        // Collision bbox sizing ratios. Mirrored from [MvtLabelGroup]'s local pass — kept
        // duplicated rather than shared because the global pass uses a larger padding
        // (visible separation between tile-border labels matters more here).
        const val COLLISION_GLYPH_W = 0.55f
        const val COLLISION_LINE_H = 1.3f
        // Padding around each accepted bbox; visible separation without over-suppression.
        const val COLLISION_PAD = 5f
        // Priority bonus given to last-frame-visible labels during this frame's collision.
        // 1 is enough to break ties between same-priority candidates without overriding the
        // hierarchy across actual zOrder bands (band spacing is 10 in MvtStyle.Z_*).
        const val STICKINESS_BONUS = 1
        // Pixels past each viewport edge that a label anchor must sit before we cull it
        // from the collision pass. Generous margin so a label whose bbox straddles the
        // edge still gets considered (could be partially visible).
        const val VIEWPORT_LABEL_MARGIN: Double = 80.0
    }
}
