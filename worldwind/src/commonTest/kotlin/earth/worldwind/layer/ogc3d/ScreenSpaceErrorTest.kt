package earth.worldwind.layer.ogc3d

import earth.worldwind.layer.ogc3d.traverse.ScreenSpaceError
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreenSpaceErrorTest {
    private val fov60 = PI / 3.0  // 60 deg vertical FOV

    @Test fun zeroDistanceForcesRefine() {
        val sse = ScreenSpaceError.compute(100.0, 0.0, 1080.0, fov60)
        assertEquals(Double.POSITIVE_INFINITY, sse)
    }

    @Test fun farDistanceCollapsesToZero() {
        val sse = ScreenSpaceError.compute(100.0, 1e9, 1080.0, fov60)
        assertTrue(sse < 1e-3, "expected SSE -> 0 at huge distance, got $sse")
    }

    @Test fun zeroGeometricErrorIsZero() {
        val sse = ScreenSpaceError.compute(0.0, 1000.0, 1080.0, fov60)
        assertEquals(0.0, sse)
    }

    @Test fun matchesSpecFormula() {
        // sse = error * h / (d * 2 * tan(fov/2)). Hand-compute with FOV = 60 deg, h = 1080,
        // tan(30 deg) = 1/sqrt(3); pick distance + error that give a round result.
        val height = 1080.0
        val distance = 500.0
        val error = 10.0
        val expected = (error * height) / (distance * 2.0 * tan(fov60 / 2.0))
        val actual = ScreenSpaceError.compute(error, distance, height, fov60)
        assertTrue(abs(expected - actual) < 1e-9, "expected $expected, got $actual")
    }

    @Test fun monotonicallyDecreasingWithDistance() {
        val near = ScreenSpaceError.compute(100.0, 100.0, 1080.0, fov60)
        val far = ScreenSpaceError.compute(100.0, 1000.0, 1080.0, fov60)
        assertTrue(near > far)
    }
}
