package earth.worldwind.layer.mvt

import earth.worldwind.render.Color
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for the Mapbox-parity expression operators added on top of the v1 set:
 * cubic-bezier interpolation, rgb/rgba/to-color, concat/downcase/upcase/length, and
 * feature-state.
 */
class MvtExpressionFullParityTest {

    private fun ctx(zoom: Double = 0.0, props: Map<String, Any?> = emptyMap(), state: Map<String, Any?>? = null) =
        MvtExpression.EvalContext(zoom, props, state)

    @Test fun cubicBezierInterpolationProducesNonLinearShape() {
        // Standard "ease-in-out" control points — at midpoint the curve should sit close
        // to 0.5 but biased by the easing.
        val expr = MvtExpression.Interpolate(
            interpolation = MvtExpression.Interpolation.CubicBezier(0.42, 0.0, 0.58, 1.0),
            input = MvtExpression.Zoom,
            stops = listOf(
                0.0 to MvtExpression.Literal(0.0f),
                10.0 to MvtExpression.Literal(10.0f),
            ),
            lerp = MvtExpression.Interpolators.FLOAT,
        )
        // Endpoints clamp exactly.
        assertEquals(0.0f, expr.evaluate(ctx(zoom = 0.0))!!, absoluteTolerance = 1e-4f)
        assertEquals(10.0f, expr.evaluate(ctx(zoom = 10.0))!!, absoluteTolerance = 1e-4f)
        // Midpoint of ease-in-out is roughly 5.0 (the curve is symmetric around (0.5, 0.5)).
        val mid = expr.evaluate(ctx(zoom = 5.0))!!
        assertTrue(abs(mid - 5.0f) < 1.0f, "ease-in-out midpoint should be ~5, got $mid")
    }

    @Test fun rgbConstructsColorFromBytes() {
        val expr = MvtExpression.Rgb(
            MvtExpression.Literal(255),
            MvtExpression.Literal(128),
            MvtExpression.Literal(0),
        )
        val c = assertNotNull(expr.evaluate(ctx()))
        assertEquals(1f, c.red)
        assertEquals(128f / 255f, c.green, 1e-3f)
        assertEquals(0f, c.blue)
        assertEquals(1f, c.alpha)
    }

    @Test fun rgbaIncludesAlphaInRange0to1() {
        val expr = MvtExpression.Rgba(
            MvtExpression.Literal(0),
            MvtExpression.Literal(0),
            MvtExpression.Literal(0),
            MvtExpression.Literal(0.5),
        )
        val c = assertNotNull(expr.evaluate(ctx()))
        assertEquals(0.5f, c.alpha, 1e-3f)
    }

    @Test fun concatJoinsStringCoercedOperands() {
        val expr = MvtExpression.Concat(listOf(
            MvtExpression.Literal("hello "),
            MvtExpression.Literal(42),
            MvtExpression.Literal(" world"),
        ))
        assertEquals("hello 42 world", expr.evaluate(ctx()))
    }

    @Test fun downcaseAndUpcase() {
        val mixed = MvtExpression.Literal("MiXeD")
        assertEquals("mixed", MvtExpression.Downcase(mixed).evaluate(ctx()))
        assertEquals("MIXED", MvtExpression.Upcase(mixed).evaluate(ctx()))
    }

    @Test fun lengthOnStringAndList() {
        assertEquals(5.0, MvtExpression.Length(MvtExpression.Literal("hello")).evaluate(ctx()))
        assertEquals(3.0, MvtExpression.Length(MvtExpression.Literal(listOf(1, 2, 3))).evaluate(ctx()))
        assertNull(MvtExpression.Length(MvtExpression.Literal(42)).evaluate(ctx()))
    }

    @Test fun featureStateReadsFromEvalContext() {
        val expr = MvtExpression.FeatureState("hover")
        assertEquals(true, expr.evaluate(ctx(state = mapOf("hover" to true))))
        assertNull(expr.evaluate(ctx(state = mapOf("other" to true))))
        assertNull(expr.evaluate(ctx(state = null)))
    }

    @Test fun parserAcceptsRgbExpression() {
        val expr = MvtExpressionParser.parseColor(
            Json.parseToJsonElement("""["rgb", 200, 100, 50]"""),
            parseColor = { null },
        )
        val c = assertNotNull(expr).evaluate(ctx())
        assertNotNull(c)
        assertEquals(200f / 255f, c.red, 1e-3f)
    }

    @Test fun parserAcceptsConcatAndDowncase() {
        val expr = MvtExpressionParser.parseAny(
            Json.parseToJsonElement("""["downcase", ["concat", ["get", "kind"], "-PATH"]]"""),
            parseColor = null,
        )
        assertNotNull(expr)
        val result = expr.evaluate(MvtExpression.EvalContext(0.0, mapOf("kind" to "Motorway")))
        assertEquals("motorway-path", result)
    }

    @Test fun parserAcceptsCubicBezierInterpolate() {
        val expr = MvtExpressionParser.parseFloat(
            Json.parseToJsonElement("""
                ["interpolate", ["cubic-bezier", 0.42, 0.0, 0.58, 1.0], ["zoom"], 0, 0.0, 10, 10.0]
            """),
        )
        assertNotNull(expr)
        // Curve passes through endpoints exactly.
        assertEquals(0.0f, expr.evaluate(ctx(zoom = 0.0))!!, absoluteTolerance = 1e-4f)
        assertEquals(10.0f, expr.evaluate(ctx(zoom = 10.0))!!, absoluteTolerance = 1e-4f)
    }

    @Test fun parserAcceptsFeatureState() {
        val expr = MvtExpressionParser.parseAny(
            Json.parseToJsonElement("""["feature-state", "selected"]"""),
            parseColor = null,
        )
        assertNotNull(expr)
        assertEquals(true, expr.evaluate(MvtExpression.EvalContext(0.0, emptyMap(), mapOf("selected" to true))))
    }
}
