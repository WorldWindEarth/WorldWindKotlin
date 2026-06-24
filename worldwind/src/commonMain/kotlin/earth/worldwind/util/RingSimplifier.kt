package earth.worldwind.util

/**
 * Polyline/ring simplification shared by the MVT decoder
 * ([earth.worldwind.layer.mvt.MvtGeometry]) and the tiled WFS feature path
 * ([earth.worldwind.layer.FeatureRenderer]).
 *
 * Pure global iterative Douglas–Peucker. Recursively keeps the vertex farthest from the chord
 * between the current endpoints whenever that distance exceeds the tolerance, so it preserves the
 * deepest cape/bay vertex at every level and never bridges across a feature. (An earlier
 * radial-distance pre-pass was removed: radial decimation at the full tolerance over-decimates and
 * produces spikes at continental scale.)
 *
 * Coordinate-space agnostic: the caller supplies vertex access ([xAt]/[yAt]) and the SQUARED
 * tolerance in that same space — integer tile units for MVT, degrees for WFS. Endpoints are always
 * kept (a ring's first == last vertex stays closed). Pass a reusable [stack] buffer (one per tile)
 * so the hot path allocates nothing per ring.
 */
object RingSimplifier {
    /**
     * Mark the survivors of simplifying the [n]-vertex ring/line in [keep] (size >= [n]); endpoints
     * are always kept. [tolSq] is the SQUARED tolerance in the coordinate space of [xAt]/[yAt].
     * [stack] (DP explicit-recursion) is a reusable buffer (cleared here).
     *
     * Inline so the per-vertex accessors fold into the hot loops with no allocation or virtual
     * dispatch on any target (the MVT decoder reads integer tile coords, WFS reads degree doubles).
     */
    inline fun simplify(
        n: Int,
        tolSq: Double,
        keep: BooleanArray,
        stack: IntList = IntList(),
        xAt: (Int) -> Double,
        yAt: (Int) -> Double,
    ) {
        if (n <= 0) return
        keep[0] = true
        if (n == 1) return
        keep[n - 1] = true
        if (n < 3) return
        // Pure global iterative Douglas–Peucker over all n vertices — no radial pre-pass (radial
        // decimation at the full tolerance over-decimates and produces spikes at continental scale).
        stack.clear()
        stack.add(0); stack.add(n - 1)
        while (stack.size > 0) {
            val last = stack[stack.size - 1]
            val first = stack[stack.size - 2]
            stack.removeLast(2)
            val ax = xAt(first); val ay = yAt(first)
            val bx = xAt(last); val by = yAt(last)
            val dx = bx - ax; val dy = by - ay
            val lenSq = dx * dx + dy * dy
            var maxDistSq = -1.0
            var index = -1
            for (k in first + 1 until last) {
                val px = xAt(k); val py = yAt(k)
                val distSq = if (lenSq == 0.0) {
                    val ex = px - ax; val ey = py - ay
                    ex * ex + ey * ey
                } else {
                    val cross = (px - ax) * dy - (py - ay) * dx
                    cross * cross / lenSq
                }
                if (distSq > maxDistSq) { maxDistSq = distSq; index = k }
            }
            if (index != -1 && maxDistSq > tolSq) {
                keep[index] = true
                stack.add(first); stack.add(index)
                stack.add(index); stack.add(last)
            }
        }
    }
}
