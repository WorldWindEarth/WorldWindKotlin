package earth.worldwind.layer.mvt

import earth.worldwind.render.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun MvtExpression<Float>.at(zoom: Int): Float =
    evaluate(MvtExpression.EvalContext(zoom.toDouble(), emptyMap()))!!

private fun MvtExpression<Color>.colorAt(zoom: Int): Color =
    evaluate(MvtExpression.EvalContext(zoom.toDouble(), emptyMap()))!!

class MvtZoomInterpTest {

    @Test fun floatBelowFirstStopClampsToFirst() {
        val ramp = MvtZoomInterp.floats(5 to 1.0f, 10 to 3.0f)
        assertEquals(1.0f, ramp.at(0))
        assertEquals(1.0f, ramp.at(5))
    }

    @Test fun floatAboveLastStopClampsToLast() {
        val ramp = MvtZoomInterp.floats(5 to 1.0f, 10 to 3.0f)
        assertEquals(3.0f, ramp.at(10))
        assertEquals(3.0f, ramp.at(99))
    }

    @Test fun floatLinearBetweenStops() {
        val ramp = MvtZoomInterp.floats(5 to 1.0f, 10 to 3.0f)
        // z=7: (7-5)/(10-5) = 0.4 → 1 + 0.4*2 = 1.8
        assertEquals(1.8f, ramp.at(7), absoluteTolerance = 1e-5f)
        // z=8: (8-5)/(10-5) = 0.6 → 1 + 0.6*2 = 2.2
        assertEquals(2.2f, ramp.at(8), absoluteTolerance = 1e-5f)
    }

    @Test fun multiSegmentRamps() {
        val ramp = MvtZoomInterp.floats(0 to 0f, 5 to 5f, 10 to 50f)
        assertEquals(5f, ramp.at(5))
        assertEquals(2.0f, ramp.at(2), absoluteTolerance = 1e-5f)
        assertEquals(32.0f, ramp.at(8), absoluteTolerance = 1e-5f)
    }

    @Test fun colorPerChannelLinearBlend() {
        val ramp = MvtZoomInterp.colors(
            5 to Color(0f, 0f, 0f, 1f),
            10 to Color(1f, 1f, 1f, 1f),
        )
        val mid = ramp.colorAt(7)
        assertEquals(0.4f, mid.red, absoluteTolerance = 1e-5f)
        assertEquals(0.4f, mid.green, absoluteTolerance = 1e-5f)
        assertEquals(0.4f, mid.blue, absoluteTolerance = 1e-5f)
        assertEquals(1.0f, mid.alpha, absoluteTolerance = 1e-5f)
    }

    @Test fun colorAlphaInterpolatesIndependently() {
        val ramp = MvtZoomInterp.colors(
            5 to Color(1f, 0f, 0f, 0f),
            10 to Color(1f, 0f, 0f, 1f),
        )
        assertEquals(0.4f, ramp.colorAt(7).alpha, absoluteTolerance = 1e-5f)
        assertEquals(1f, ramp.colorAt(7).red)
    }

    @Test fun constantRampReturnsSameValue() {
        val ramp = MvtZoomInterp.constant(2.5f)
        for (z in -5..20) assertEquals(2.5f, ramp.at(z))
    }

    @Test fun emptyStopsRejected() {
        assertFailsWith<IllegalArgumentException> {
            MvtExpression.Interpolate(
                interpolation = MvtExpression.Interpolation.Linear,
                input = MvtExpression.Zoom,
                stops = emptyList<Pair<Double, MvtExpression<Float>>>(),
                lerp = MvtExpression.Interpolators.FLOAT,
            )
        }
    }
}
