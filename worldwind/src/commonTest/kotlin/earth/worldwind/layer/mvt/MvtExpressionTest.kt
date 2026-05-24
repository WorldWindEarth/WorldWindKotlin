package earth.worldwind.layer.mvt

import earth.worldwind.render.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MvtExpressionTest {

    private fun ctx(zoom: Double = 0.0, vararg props: Pair<String, Any?>) =
        MvtExpression.EvalContext(zoom, props.toMap())

    @Test fun literalReturnsConstantValue() {
        assertEquals(42, MvtExpression.Literal(42).evaluate(ctx()))
        assertEquals("hi", MvtExpression.Literal("hi").evaluate(ctx()))
        assertEquals(true, MvtExpression.Literal(true).evaluate(ctx()))
    }

    @Test fun zoomSourceReadsContextZoom() {
        assertEquals(5.5, MvtExpression.Zoom.evaluate(ctx(zoom = 5.5)))
    }

    @Test fun getReadsProperty() {
        val expr = MvtExpression.Get("kind")
        assertEquals("motorway", expr.evaluate(ctx(props = arrayOf("kind" to "motorway"))))
        assertNull(expr.evaluate(ctx()))
    }

    @Test fun hasChecksPresence() {
        val expr = MvtExpression.Has("name")
        assertEquals(true, expr.evaluate(ctx(props = arrayOf("name" to "Foo"))))
        assertEquals(true, expr.evaluate(ctx(props = arrayOf("name" to null))))
        assertEquals(false, expr.evaluate(ctx()))
    }

    @Test fun comparisonNumeric() {
        val lt = MvtExpression.Lt(MvtExpression.Literal(3), MvtExpression.Literal(5))
        assertEquals(true, lt.evaluate(ctx()))
        val gt = MvtExpression.Gt(MvtExpression.Literal(3), MvtExpression.Literal(5))
        assertEquals(false, gt.evaluate(ctx()))
    }

    @Test fun comparisonCrossTypeNumeric() {
        // Mapbox semantics: numbers compare by toDouble.
        val eq = MvtExpression.Eq(MvtExpression.Literal(3), MvtExpression.Literal(3.0))
        assertEquals(true, eq.evaluate(ctx()))
    }

    @Test fun allShortCircuitsOnFirstFalse() {
        val expr = MvtExpression.AllOf(listOf(
            MvtExpression.Literal(true),
            MvtExpression.Literal(false),
            MvtExpression.Literal(true),
        ))
        assertEquals(false, expr.evaluate(ctx()))
    }

    @Test fun anyShortCircuitsOnFirstTrue() {
        val expr = MvtExpression.AnyOf(listOf(
            MvtExpression.Literal(false),
            MvtExpression.Literal(true),
            MvtExpression.Literal(false),
        ))
        assertEquals(true, expr.evaluate(ctx()))
    }

    @Test fun arithmeticSum() {
        val expr = MvtExpression.Add(listOf(
            MvtExpression.Literal(1),
            MvtExpression.Literal(2),
            MvtExpression.Literal(3.5),
        ))
        assertEquals(6.5, expr.evaluate(ctx()))
    }

    @Test fun arithmeticSubLeftFold() {
        // 10 - 3 - 2 = 5
        val expr = MvtExpression.Sub(listOf(
            MvtExpression.Literal(10),
            MvtExpression.Literal(3),
            MvtExpression.Literal(2),
        ))
        assertEquals(5.0, expr.evaluate(ctx()))
    }

    @Test fun caseFirstMatchWins() {
        val expr = MvtExpression.Case(
            branches = listOf(
                MvtExpression.Case.Branch(MvtExpression.Literal(false), MvtExpression.Literal("A")),
                MvtExpression.Case.Branch(MvtExpression.Literal(true), MvtExpression.Literal("B")),
                MvtExpression.Case.Branch(MvtExpression.Literal(true), MvtExpression.Literal("C")),
            ),
            default = MvtExpression.Literal("D"),
        )
        assertEquals("B", expr.evaluate(ctx()))
    }

    @Test fun caseFallsThroughToDefault() {
        val expr = MvtExpression.Case(
            branches = listOf(
                MvtExpression.Case.Branch(MvtExpression.Literal(false), MvtExpression.Literal("A")),
            ),
            default = MvtExpression.Literal("D"),
        )
        assertEquals("D", expr.evaluate(ctx()))
    }

    @Test fun matchPicksLabelBranch() {
        val expr = MvtExpression.Match(
            input = MvtExpression.Get("kind"),
            branches = listOf(
                MvtExpression.Match.Branch(listOf("motorway", "trunk"), MvtExpression.Literal(3.0f)),
                MvtExpression.Match.Branch(listOf("primary"), MvtExpression.Literal(2.0f)),
            ),
            default = MvtExpression.Literal(1.0f),
        )
        assertEquals(3.0f, expr.evaluate(ctx(props = arrayOf("kind" to "motorway"))))
        assertEquals(3.0f, expr.evaluate(ctx(props = arrayOf("kind" to "trunk"))))
        assertEquals(2.0f, expr.evaluate(ctx(props = arrayOf("kind" to "primary"))))
        assertEquals(1.0f, expr.evaluate(ctx(props = arrayOf("kind" to "residential"))))
    }

    @Test fun stepPiecewiseConstant() {
        // base = 0, stops: 5→10, 10→20
        val expr = MvtExpression.Step(
            input = MvtExpression.Zoom,
            base = MvtExpression.Literal(0.0f),
            stops = listOf(
                5.0 to MvtExpression.Literal(10.0f),
                10.0 to MvtExpression.Literal(20.0f),
            ),
        )
        assertEquals(0.0f, expr.evaluate(ctx(zoom = 0.0)))
        assertEquals(0.0f, expr.evaluate(ctx(zoom = 4.9)))
        assertEquals(10.0f, expr.evaluate(ctx(zoom = 5.0)))
        assertEquals(10.0f, expr.evaluate(ctx(zoom = 9.9)))
        assertEquals(20.0f, expr.evaluate(ctx(zoom = 10.0)))
        assertEquals(20.0f, expr.evaluate(ctx(zoom = 100.0)))
    }

    @Test fun interpolateLinearFloat() {
        val expr = MvtExpression.Interpolate(
            interpolation = MvtExpression.Interpolation.Linear,
            input = MvtExpression.Zoom,
            stops = listOf(
                5.0 to MvtExpression.Literal(0.0f),
                10.0 to MvtExpression.Literal(10.0f),
            ),
            lerp = MvtExpression.Interpolators.FLOAT,
        )
        assertEquals(0.0f, expr.evaluate(ctx(zoom = 5.0)))
        assertEquals(5.0f, expr.evaluate(ctx(zoom = 7.5))!!, absoluteTolerance = 1e-5f)
        assertEquals(10.0f, expr.evaluate(ctx(zoom = 10.0)))
    }

    @Test fun interpolateExponentialFloat() {
        // Exponential base=2: t = (2^(x-lo) - 1) / (2^(hi-lo) - 1)
        // For x=7.5, lo=5, hi=10: t = (2^2.5 - 1)/(2^5 - 1) = (5.6569 - 1) / 31 ≈ 0.1502
        val expr = MvtExpression.Interpolate(
            interpolation = MvtExpression.Interpolation.Exponential(2.0),
            input = MvtExpression.Zoom,
            stops = listOf(
                5.0 to MvtExpression.Literal(0.0f),
                10.0 to MvtExpression.Literal(10.0f),
            ),
            lerp = MvtExpression.Interpolators.FLOAT,
        )
        // Linear would give 5.0; exponential base=2 weights toward the lo end.
        val v = expr.evaluate(ctx(zoom = 7.5))!!
        assertTrue(v < 5.0f, "exponential base 2 should weight toward lo, got $v")
        assertTrue(v > 1.0f, "exponential should still be > 1 by z=7.5, got $v")
    }

    @Test fun interpolateClampsAtBothEnds() {
        val expr = MvtExpression.Interpolate(
            interpolation = MvtExpression.Interpolation.Linear,
            input = MvtExpression.Zoom,
            stops = listOf(
                5.0 to MvtExpression.Literal(1.0f),
                10.0 to MvtExpression.Literal(2.0f),
            ),
            lerp = MvtExpression.Interpolators.FLOAT,
        )
        assertEquals(1.0f, expr.evaluate(ctx(zoom = 0.0)))
        assertEquals(2.0f, expr.evaluate(ctx(zoom = 99.0)))
    }

    @Test fun interpolateColor() {
        val expr = MvtExpression.Interpolate(
            interpolation = MvtExpression.Interpolation.Linear,
            input = MvtExpression.Zoom,
            stops = listOf(
                0.0 to MvtExpression.Literal(Color(0f, 0f, 0f, 1f)),
                10.0 to MvtExpression.Literal(Color(1f, 1f, 1f, 1f)),
            ),
            lerp = MvtExpression.Interpolators.COLOR,
        )
        val mid = expr.evaluate(ctx(zoom = 5.0))!!
        assertEquals(0.5f, mid.red, absoluteTolerance = 1e-5f)
        assertEquals(0.5f, mid.green, absoluteTolerance = 1e-5f)
        assertEquals(0.5f, mid.blue, absoluteTolerance = 1e-5f)
    }

    @Test fun toNumberCoercesStringsAndBooleans() {
        assertEquals(3.5, MvtExpression.ToNumber(MvtExpression.Literal("3.5")).evaluate(ctx()))
        assertEquals(1.0, MvtExpression.ToNumber(MvtExpression.Literal(true)).evaluate(ctx()))
        assertEquals(0.0, MvtExpression.ToNumber(MvtExpression.Literal(false)).evaluate(ctx()))
        assertNull(MvtExpression.ToNumber(MvtExpression.Literal("nope")).evaluate(ctx()))
    }

    @Test fun toStringDefaultsToString() {
        assertEquals("3.5", MvtExpression.ToString(MvtExpression.Literal(3.5)).evaluate(ctx()))
        assertEquals("true", MvtExpression.ToString(MvtExpression.Literal(true)).evaluate(ctx()))
    }

    @Test fun toBooleanCoercion() {
        assertEquals(true, MvtExpression.ToBoolean(MvtExpression.Literal("nonempty")).evaluate(ctx()))
        assertEquals(false, MvtExpression.ToBoolean(MvtExpression.Literal("")).evaluate(ctx()))
        assertEquals(true, MvtExpression.ToBoolean(MvtExpression.Literal(1)).evaluate(ctx()))
        assertEquals(false, MvtExpression.ToBoolean(MvtExpression.Literal(0)).evaluate(ctx()))
    }
}
