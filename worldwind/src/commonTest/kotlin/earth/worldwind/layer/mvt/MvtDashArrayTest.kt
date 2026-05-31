package earth.worldwind.layer.mvt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import earth.worldwind.render.Color

class MvtDashArrayTest {

    @Test fun emptyDashArrayProducesSolidPattern() {
        val (factor, pattern) = MvtStyleRule.PaintSpec.dashArrayToStipple(floatArrayOf())
        assertEquals(1, factor)
        assertEquals(0xFFFF.toShort(), pattern)
    }

    @Test fun evenDashAndGapProducesAlternatingPattern() {
        // [2, 2]: half the 16-bit window is on, half off. Cycle starts with dash.
        val (factor, pattern) = MvtStyleRule.PaintSpec.dashArrayToStipple(floatArrayOf(2f, 2f))
        assertEquals(2, factor)
        // 8 bits on followed by 8 bits off (with bit 0 = least-significant) → 0x00FF.
        assertEquals(0x00FF, pattern.toInt() and 0xFFFF)
    }

    @Test fun longerDashThanGapHasMoreLitBits() {
        // [3, 1]: 75% lit. 12 of 16 bits → 0x0FFF.
        val (_, pattern) = MvtStyleRule.PaintSpec.dashArrayToStipple(floatArrayOf(3f, 1f))
        val onCount = (pattern.toInt() and 0xFFFF).countOneBits()
        assertTrue(onCount >= 11, "expected ~12 lit bits for 3:1 dash, got $onCount")
    }

    @Test fun integratesThroughPaintSpecBuildOutlineImageSource() {
        // PaintSpec.build should populate outlineImageSource when lineDashArray is set.
        val paint = MvtStyleRule.PaintSpec(
            lineColor = MvtExpression.Literal(Color(1f, 0f, 0f)),
            lineWidth = MvtExpression.Literal(2f),
            lineDashArray = floatArrayOf(4f, 2f),
        )
        val attrs = paint.build(zoom = 12)
        assertTrue(attrs.outlineImageSource != null, "dasharray should set outlineImageSource")
    }
}
