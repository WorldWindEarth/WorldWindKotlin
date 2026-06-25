package earth.worldwind.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the SoA [Earcut] against area conservation: a correct, non-overlapping triangulation's
 * triangle areas sum exactly to the polygon's area (outer minus holes). Also checks index validity
 * and that one reused instance (arena reset between calls) gives identical results to a fresh one.
 */
class EarcutTest {

    private val eps = 1e-9

    /** Shoelace area of one flat [x,y,…] ring over [start,end) vertex range. */
    private fun ringArea(data: DoubleArray, start: Int, end: Int): Double {
        var sum = 0.0
        var j = end - 1
        for (i in start until end) {
            sum += (data[j * 2] + data[i * 2]) * (data[j * 2 + 1] - data[i * 2 + 1])
            j = i
        }
        return abs(sum) / 2.0
    }

    /** Sum of |area| of every emitted triangle — equals the polygon area iff the cover is valid. */
    private fun triAreaSum(data: DoubleArray, tris: IntList): Double {
        var sum = 0.0
        var t = 0
        while (t < tris.size) {
            val a = tris[t]; val b = tris[t + 1]; val c = tris[t + 2]
            sum += abs((data[b * 2] - data[a * 2]) * (data[c * 2 + 1] - data[a * 2 + 1]) -
                (data[c * 2] - data[a * 2]) * (data[b * 2 + 1] - data[a * 2 + 1])) / 2.0
            t += 3
        }
        return sum
    }

    private fun assertValidIndices(tris: IntList, vertexCount: Int) {
        for (i in 0 until tris.size) assertTrue(tris[i] in 0 until vertexCount, "index ${tris[i]} out of range")
        assertEquals(0, tris.size % 3, "triangle list not a multiple of 3")
    }

    @Test
    fun unitSquare() {
        val data = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)
        val tris = Earcut().triangulate(data)
        assertEquals(2 * 3, tris.size, "square should make 2 triangles")
        assertValidIndices(tris, 4)
        assertEquals(1.0, triAreaSum(data, tris), eps)
    }

    @Test
    fun concaveLShape() {
        val data = doubleArrayOf(0.0, 0.0, 2.0, 0.0, 2.0, 1.0, 1.0, 1.0, 1.0, 2.0, 0.0, 2.0)
        val tris = Earcut().triangulate(data)
        assertEquals(4 * 3, tris.size, "6-vertex polygon should make 4 triangles")
        assertValidIndices(tris, 6)
        assertEquals(3.0, triAreaSum(data, tris), eps) // 2x2 minus the 1x1 notch
    }

    @Test
    fun squareWithHole() {
        // outer CCW (area 16) + an inner square hole (area 4) → fill area 12.
        val data = doubleArrayOf(
            0.0, 0.0, 4.0, 0.0, 4.0, 4.0, 0.0, 4.0,   // outer, verts 0..3
            1.0, 1.0, 3.0, 1.0, 3.0, 3.0, 1.0, 3.0,   // hole,  verts 4..7
        )
        val tris = Earcut().triangulate(data, intArrayOf(4))
        assertValidIndices(tris, 8)
        val expected = ringArea(data, 0, 4) - ringArea(data, 4, 8)
        assertEquals(12.0, expected, eps)
        assertEquals(expected, triAreaSum(data, tris), eps)
    }

    @Test
    fun largeCircleUsesHashedPath() {
        // 200 vertices > the 80-vertex z-order-hashing threshold; exercises isEarHashed/indexCurve.
        val n = 200
        val data = DoubleArray(n * 2)
        for (i in 0 until n) {
            val a = 2.0 * PI * i / n
            data[i * 2] = cos(a); data[i * 2 + 1] = sin(a)
        }
        val tris = Earcut().triangulate(data)
        assertEquals((n - 2) * 3, tris.size, "n-gon should make n-2 triangles")
        assertValidIndices(tris, n)
        assertEquals(ringArea(data, 0, n), triAreaSum(data, tris), 1e-9)
    }

    @Test
    fun largeConcaveStarUsesHashedPath() {
        // A deeply concave star with 60 spikes = 120 verts (> 80 hashing threshold). Unlike a convex
        // circle, every other vertex is reflex, so isEarHashed MUST reject ears that contain other
        // vertices — the path that, if the z-order chain is wrong, emits spanning "spike" triangles.
        // Area conservation catches any spike (area outside the polygon) or overlap (double-counted).
        val spikes = 60
        val n = spikes * 2
        val data = DoubleArray(n * 2)
        for (i in 0 until n) {
            val a = 2.0 * PI * i / n
            val r = if (i % 2 == 0) 1.0 else 0.35 // outer / inner radius
            data[i * 2] = r * cos(a); data[i * 2 + 1] = r * sin(a)
        }
        val tris = Earcut().triangulate(data)
        assertValidIndices(tris, n)
        assertEquals(ringArea(data, 0, n), triAreaSum(data, tris), 1e-9)
    }

    @Test
    fun largeConcaveCombUsesHashedPath() {
        // A "comb" of deep slots — a classic earcut stress shape. 100+ verts, many reflex notches.
        val teeth = 24
        val pts = ArrayList<Double>()
        // bottom edge left→right
        pts.add(0.0); pts.add(0.0)
        for (t in 0 until teeth) {
            val x0 = 1.0 + t * 2.0
            pts.add(x0); pts.add(0.0)
            pts.add(x0); pts.add(3.0)      // up into a tooth
            pts.add(x0 + 1.0); pts.add(3.0)
            pts.add(x0 + 1.0); pts.add(0.0)
        }
        pts.add(teeth * 2.0 + 1.0); pts.add(0.0)
        pts.add(teeth * 2.0 + 1.0); pts.add(-1.0) // base
        pts.add(0.0); pts.add(-1.0)
        val data = pts.toDoubleArray()
        val n = data.size / 2
        val tris = Earcut().triangulate(data)
        assertValidIndices(tris, n)
        assertEquals(ringArea(data, 0, n), triAreaSum(data, tris), 1e-6)
    }

    @Test
    fun largeRingWithHoleUsesHashedPath() {
        // 100-vertex outer circle (hashed) with a 40-vertex inner hole — exercises hole bridging on the
        // z-order path, where bridge nodes duplicate ordinals and must be z-indexed correctly.
        val outerN = 100
        val holeN = 40
        val data = DoubleArray((outerN + holeN) * 2)
        for (i in 0 until outerN) {
            val a = 2.0 * PI * i / outerN
            data[i * 2] = 3.0 * cos(a); data[i * 2 + 1] = 3.0 * sin(a)
        }
        for (i in 0 until holeN) {
            val a = -2.0 * PI * i / holeN // opposite winding for a hole
            val k = outerN + i
            data[k * 2] = 1.0 * cos(a); data[k * 2 + 1] = 1.0 * sin(a)
        }
        val tris = Earcut().triangulate(data, intArrayOf(outerN))
        assertValidIndices(tris, outerN + holeN)
        val expected = ringArea(data, 0, outerN) - ringArea(data, outerN, outerN + holeN)
        assertEquals(expected, triAreaSum(data, tris), 1e-6)
    }

    @Test
    fun selfIntersectingLargeRingEmitsNoSpikes() {
        // A large non-simple ring (a figure-eight-ish self-crossing star) forces cure/split passes,
        // possibly exhausting the split op budget. Whatever it emits, every triangle must lie within the outer
        // bbox and the total area must NOT exceed the ring's |area| — a "spike into the ocean" would
        // blow the sum past it. Bailing on the residue (missing fill) is allowed; garbage is not.
        val n = 400
        val data = DoubleArray(n * 2)
        for (i in 0 until n) {
            // wind the angle 2x so the ring crosses itself repeatedly
            val a = 4.0 * PI * i / n
            val r = if (i % 2 == 0) 1.0 else 0.3
            data[i * 2] = r * cos(a); data[i * 2 + 1] = r * sin(a)
        }
        val tris = Earcut().triangulate(data)
        assertValidIndices(tris, n)
        // total emitted area must not exceed the (gross) polygon extent — no spanning spikes/overlaps.
        val bboxArea = run {
            var x0 = data[0]; var x1 = data[0]; var y0 = data[1]; var y1 = data[1]
            for (i in 0 until n) {
                x0 = minOf(x0, data[i * 2]); x1 = maxOf(x1, data[i * 2])
                y0 = minOf(y0, data[i * 2 + 1]); y1 = maxOf(y1, data[i * 2 + 1])
            }
            (x1 - x0) * (y1 - y0)
        }
        assertTrue(triAreaSum(data, tris) <= bboxArea + 1e-6,
            "emitted ${triAreaSum(data, tris)} exceeds bbox $bboxArea — spanning/overlapping triangles")
    }

    @Test
    fun wideShortSliverConservesArea() {
        // Extreme aspect ratio (a Mercator polar tile: ~90° lon × ~1° lat). With a single invSize that
        // normalizes BOTH axes by the long side, the short axis collapses to near-zero z-order resolution,
        // isEarHashed misses a real blocking vertex, and a triangle fans across the sliver (the Arctic
        // Ocean spike). A concave comb >80 verts forces ear rejections on the hashed path; area
        // conservation catches any spanning/overlapping triangle.
        val teeth = 40
        val pts = ArrayList<Double>()
        pts.add(0.0); pts.add(0.0)
        for (t in 0 until teeth) {
            val x0 = 25.0 + t * 50.0
            pts.add(x0); pts.add(0.0)
            pts.add(x0); pts.add(1.0)        // tooth height 1 — the collapsed short axis
            pts.add(x0 + 25.0); pts.add(1.0)
            pts.add(x0 + 25.0); pts.add(0.0)
        }
        val w = teeth * 50.0 + 50.0           // width ~2050 vs height ~2 → ~1000:1 aspect
        pts.add(w); pts.add(0.0)
        pts.add(w); pts.add(-1.0)
        pts.add(0.0); pts.add(-1.0)
        val data = pts.toDoubleArray()
        val n = data.size / 2
        val tris = Earcut().triangulate(data)
        assertValidIndices(tris, n)
        assertEquals(ringArea(data, 0, n), triAreaSum(data, tris), 1e-6)
    }

    @Test
    fun wideShortJaggedSliverConservesArea() {
        // A thin band (extreme aspect) with an irregular jagged top edge — densely packed in x AND y,
        // the worst case for the collapsed-short-axis z-order. Bottom edge straight at y=0; top edge
        // weaves y∈[0.05,1] with occasional vertical steps. Many reflex vertices. Area conservation
        // catches any spanning triangle the hashed neighbor-walk would leak.
        val n = 200
        val pts = ArrayList<Double>()
        // bottom edge, left→right
        for (i in 0..n) { pts.add(i * 10.0); pts.add(0.0) }
        // top edge, right→left, jagged (deterministic pseudo-noise)
        for (i in n downTo 0) {
            val y = 0.05 + 0.95 * (((i * 73 + 17) % 19) / 19.0) // weaves between ~0.05 and ~1.0
            pts.add(i * 10.0); pts.add(y)
        }
        val data = pts.toDoubleArray()
        val m = data.size / 2
        val tris = Earcut().triangulate(data)
        assertValidIndices(tris, m)
        assertEquals(ringArea(data, 0, m), triAreaSum(data, tris), 1e-6)
    }

    @Test
    fun reusedInstanceMatchesFresh() {
        val ec = Earcut()
        val square = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)
        val lshape = doubleArrayOf(0.0, 0.0, 2.0, 0.0, 2.0, 1.0, 1.0, 1.0, 1.0, 2.0, 0.0, 2.0)
        // Drive several polygons through one arena, then re-run the first — arena reset must isolate runs.
        ec.triangulate(square)
        ec.triangulate(lshape)
        ec.release() // free + regrow path
        val reused = ec.triangulate(square).toIntArray()
        val fresh = Earcut().triangulate(square).toIntArray()
        assertTrue(reused.contentEquals(fresh), "reused arena produced different triangulation")
        assertEquals(1.0, triAreaSum(square, IntList().also { for (v in reused) it.add(v) }), eps)
    }

    @Test
    fun multipleHolesConserveArea() {
        // Outer 10×10 (area 100) with THREE holes fed in ONE call — exercises eliminateHoles' holeQueue
        // sort + sequential multi-bridge (the "fan across the ocean" path a real MVT fill hits). Holes 1&2
        // nearly touch (0.2 gap) to stress adjacent bridges; hole 3 is separate. Both current hole tests
        // pass only a single hole, so this is the first multi-bridge coverage.
        val data = doubleArrayOf(
            0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0,   // outer, verts 0..3  (area 100)
            1.0, 1.0, 2.0, 1.0, 2.0, 2.0, 1.0, 2.0,       // hole 1, verts 4..7  (area 1)
            2.2, 1.0, 3.2, 1.0, 3.2, 2.0, 2.2, 2.0,       // hole 2, verts 8..11 (area 1, ~touches hole 1)
            5.0, 5.0, 8.0, 5.0, 8.0, 8.0, 5.0, 8.0,       // hole 3, verts 12..15 (area 9)
        )
        val tris = Earcut().triangulate(data, intArrayOf(4, 8, 12))
        assertValidIndices(tris, 16)
        val expected = ringArea(data, 0, 4) -
            ringArea(data, 4, 8) - ringArea(data, 8, 12) - ringArea(data, 12, 16)
        assertEquals(89.0, expected, eps)
        assertEquals(expected, triAreaSum(data, tris), eps)
    }

    @Test
    fun clockwiseOuterRingMatchesCcw() {
        // earcut normalizes winding (MvtBatchedPolygonTile relies on "input rings need no orientation"):
        // a CW-wound outer ring must conserve the same fill area as its CCW reverse.
        val ccw = doubleArrayOf(0.0, 0.0, 2.0, 0.0, 2.0, 1.0, 1.0, 1.0, 1.0, 2.0, 0.0, 2.0) // L-shape, area 3
        val n = ccw.size / 2
        val cw = DoubleArray(ccw.size)
        for (i in 0 until n) { // reverse vertex order → opposite winding
            cw[i * 2] = ccw[(n - 1 - i) * 2]
            cw[i * 2 + 1] = ccw[(n - 1 - i) * 2 + 1]
        }
        val trisCcw = Earcut().triangulate(ccw)
        val trisCw = Earcut().triangulate(cw)
        assertValidIndices(trisCw, n)
        assertEquals(3.0, triAreaSum(ccw, trisCcw), eps)
        assertEquals(3.0, triAreaSum(cw, trisCw), eps) // CW outer → identical fill area
    }

    @Test
    fun collinearAndDegenerateRingsDoNotCrash() {
        // Collinear points on an edge must not break ear-clipping; the fill area is unaffected.
        val collinear = doubleArrayOf(
            0.0, 0.0, 1.0, 0.0, 2.0, 0.0, 3.0, 0.0,   // 4 collinear points along the bottom edge
            3.0, 2.0, 0.0, 2.0,                       // top edge → a 3×2 rectangle (area 6)
        )
        val tris = Earcut().triangulate(collinear)
        assertValidIndices(tris, 6)
        assertEquals(6.0, triAreaSum(collinear, tris), eps)

        // Zero-area degenerate ring (all points collinear): must not crash, and emits zero area however
        // it resolves (no triangles, or only zero-area ones).
        val degenerate = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 2.0, 0.0)
        val degTris = Earcut().triangulate(degenerate)
        assertValidIndices(degTris, 3)
        assertEquals(0.0, triAreaSum(degenerate, degTris), eps)
    }
}
