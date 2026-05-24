package earth.worldwind.layer.mvt

import earth.worldwind.render.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MvtZoomInterpTest {

    @Test fun floatBelowFirstStopClampsToFirst() {
        val ramp = MvtZoomInterp.floats(5 to 1.0f, 10 to 3.0f)
        assertEquals(1.0f, ramp.valueAt(0))
        assertEquals(1.0f, ramp.valueAt(5))
    }

    @Test fun floatAboveLastStopClampsToLast() {
        val ramp = MvtZoomInterp.floats(5 to 1.0f, 10 to 3.0f)
        assertEquals(3.0f, ramp.valueAt(10))
        assertEquals(3.0f, ramp.valueAt(99))
    }

    @Test fun floatLinearBetweenStops() {
        val ramp = MvtZoomInterp.floats(5 to 1.0f, 10 to 3.0f)
        // Midpoint at z=7.5 → since zoom is Int, test z=7 and z=8.
        // z=7: (7-5)/(10-5) = 0.4 → 1 + 0.4*2 = 1.8
        assertEquals(1.8f, ramp.valueAt(7), absoluteTolerance = 1e-5f)
        // z=8: (8-5)/(10-5) = 0.6 → 1 + 0.6*2 = 2.2
        assertEquals(2.2f, ramp.valueAt(8), absoluteTolerance = 1e-5f)
    }

    @Test fun multiSegmentRamps() {
        val ramp = MvtZoomInterp.floats(0 to 0f, 5 to 5f, 10 to 50f)
        // Each segment interpolates independently within its own (zoomLo, zoomHi) window.
        assertEquals(5f, ramp.valueAt(5))
        // z=2 in segment [0..5]: t=2/5=0.4 → 0 + 0.4*(5-0) = 2.0
        assertEquals(2.0f, ramp.valueAt(2), absoluteTolerance = 1e-5f)
        // z=8 in segment [5..10]: t=(8-5)/5=0.6 → 5 + 0.6*(50-5) = 32.0
        assertEquals(32.0f, ramp.valueAt(8), absoluteTolerance = 1e-5f)
    }

    @Test fun colorPerChannelLinearBlend() {
        val ramp = MvtZoomInterp.colors(
            5 to Color(0f, 0f, 0f, 1f),
            10 to Color(1f, 1f, 1f, 1f),
        )
        // z=7: t=0.4 → all channels = 0.4
        val mid = ramp.valueAt(7)
        assertEquals(0.4f, mid.red, absoluteTolerance = 1e-5f)
        assertEquals(0.4f, mid.green, absoluteTolerance = 1e-5f)
        assertEquals(0.4f, mid.blue, absoluteTolerance = 1e-5f)
        // Alpha stayed at 1 throughout.
        assertEquals(1.0f, mid.alpha, absoluteTolerance = 1e-5f)
    }

    @Test fun colorAlphaInterpolatesIndependently() {
        val ramp = MvtZoomInterp.colors(
            5 to Color(1f, 0f, 0f, 0f),
            10 to Color(1f, 0f, 0f, 1f),
        )
        // RGB constant; alpha fades in.
        assertEquals(0.4f, ramp.valueAt(7).alpha, absoluteTolerance = 1e-5f)
        assertEquals(1f, ramp.valueAt(7).red)
    }

    @Test fun constantRampReturnsSameValue() {
        val ramp = MvtZoomInterp.constant(2.5f)
        for (z in -5..20) assertEquals(2.5f, ramp.valueAt(z))
    }

    @Test fun emptyStopsRejected() {
        // Constructor `require` should fire — kotlin.test doesn't have assertFailsWith<IllegalArgumentException>
        // built-in name overloads, use the generic form.
        try {
            MvtZoomInterp.Floats(emptyList())
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("at least one"))
        }
    }
}
