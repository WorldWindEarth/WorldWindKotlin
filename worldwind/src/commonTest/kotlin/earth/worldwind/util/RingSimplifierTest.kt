package earth.worldwind.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RingSimplifierTest {

    /** Run the shared simplifier over a flat [x0, y0, x1, y1, …] ring and return the kept indices. */
    private fun simplify(coords: DoubleArray, tol: Double): List<Int> {
        val n = coords.size / 2
        val keep = BooleanArray(n)
        RingSimplifier.simplify(
            n, tol * tol, keep,
            xAt = { coords[it * 2] }, yAt = { coords[it * 2 + 1] },
        )
        return (0 until n).filter { keep[it] }
    }

    @Test
    fun keepsEndpoints() {
        val line = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 2.0, 0.0)
        val kept = simplify(line, tol = 0.5)
        assertTrue(0 in kept, "first vertex must be kept")
        assertTrue(2 in kept, "last vertex must be kept")
    }

    @Test
    fun dropsCollinearInteriorVertices() {
        // A straight run: every interior point lies on the chord, so only the endpoints survive.
        val line = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 2.0, 0.0, 3.0, 0.0, 4.0, 0.0)
        assertEquals(listOf(0, 4), simplify(line, tol = 0.5))
    }

    @Test
    fun keepsAVertexFartherThanTolerance() {
        // A spike at index 2 rises 1.0 off the chord — above a 0.5 tolerance, so it stays.
        val line = doubleArrayOf(0.0, 0.0, 1.0, 0.1, 2.0, 1.0, 3.0, 0.1, 4.0, 0.0)
        val kept = simplify(line, tol = 0.5)
        assertEquals(listOf(0, 2, 4), kept)
    }

    @Test
    fun stripsSubToleranceClustering() {
        // 100 vertices jittered well below tolerance along a straight line collapse to the endpoints.
        val coords = DoubleArray(100 * 2)
        for (i in 0 until 100) {
            coords[i * 2] = i.toDouble()
            coords[i * 2 + 1] = if (i % 2 == 0) 0.001 else -0.001 // ±1e-3, far under tol
        }
        val kept = simplify(coords, tol = 0.5)
        assertEquals(listOf(0, 99), kept)
    }

    @Test
    fun keepsClosedRingClosure() {
        // First == last vertex (a closed ring) — both ends stay so the ring remains closed, and a
        // corner that's off the diagonal is retained.
        val ring = doubleArrayOf(0.0, 0.0, 5.0, 0.0, 5.0, 5.0, 0.0, 5.0, 0.0, 0.0)
        val kept = simplify(ring, tol = 0.5)
        assertTrue(0 in kept && kept.last() == 4, "closure endpoints kept")
        assertEquals(5, kept.size, "all four corners + closure survive a square")
    }
}
