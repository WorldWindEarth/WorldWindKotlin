package earth.worldwind.util

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reproduces the osm:water_areas river winding: GeoServer emits the exterior ring CLOCKWISE
 * (negative shoelace area in lon/lat Y-up space) and island holes COUNTER-CLOCKWISE (positive) —
 * the opposite of RFC 7946. Verifies earcut still fills outer-minus-holes (the river), not the holes.
 */
class EarcutWindingTest {

    private fun triangulatedArea(coords: DoubleArray, holeIndices: IntArray?): Double {
        val tris = Earcut().triangulate(coords, holeIndices, 2)
        var a = 0.0
        var t = 0
        while (t < tris.size) {
            val i = tris[t] * 2; val j = tris[t + 1] * 2; val k = tris[t + 2] * 2
            a += kotlin.math.abs(
                (coords[j] - coords[i]) * (coords[k + 1] - coords[i + 1]) -
                    (coords[k] - coords[i]) * (coords[j + 1] - coords[i + 1])
            ) / 2.0
            t += 3
        }
        return a
    }

    @Test
    fun cwOuterWithCcwHoleFillsRingNotHole() {
        // Outer 10x10 square wound CLOCKWISE (negative area, like the river exterior).
        // Hole 2x2 square wound COUNTER-CLOCKWISE (positive area, like an island).
        val outerCw = doubleArrayOf(0.0,0.0, 0.0,10.0, 10.0,10.0, 10.0,0.0)
        val holeCcw = doubleArrayOf(4.0,4.0, 5.0,4.0, 5.0,5.0, 4.0,5.0)
        val coords = outerCw + holeCcw
        val area = triangulatedArea(coords, intArrayOf(outerCw.size / 2))
        // Correct: river (100) minus island (1) = 99. Inverted would be ~1.
        assertTrue(area in 98.0..100.0, "expected river fill ≈99, got $area (≈1 ⇒ islands filled instead)")
    }
}
