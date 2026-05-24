package earth.worldwind.layer.mvt

import earth.worldwind.render.Color
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MvtLineGradientTest {

    private fun ctx(progress: Double) = MvtExpression.EvalContext(
        zoom = 0.0, properties = emptyMap(),
        lineProgress = progress,
    )

    @Test fun lineProgressReadsFromEvalContext() {
        assertEquals(0.42, MvtExpression.LineProgress.evaluate(ctx(0.42)))
    }

    @Test fun parserAcceptsLineProgress() {
        val expr = MvtExpressionParser.parseAny(
            Json.parseToJsonElement("""["line-progress"]"""),
            parseColor = null,
        )
        assertNotNull(expr)
        assertEquals(0.75, expr.evaluate(ctx(0.75)))
    }

    @Test fun gradientInterpolationSamplesByProgress() {
        // [interpolate, linear, line-progress, 0, black, 1, white]
        val gradient = MvtExpression.Interpolate(
            interpolation = MvtExpression.Interpolation.Linear,
            input = MvtExpression.LineProgress,
            stops = listOf(
                0.0 to MvtExpression.Literal(Color(0f, 0f, 0f)),
                1.0 to MvtExpression.Literal(Color(1f, 1f, 1f)),
            ),
            lerp = MvtExpression.Interpolators.COLOR,
        )
        val paint = MvtStyleRule.PaintSpec(
            lineColor = MvtExpression.Literal(Color(0f, 0f, 0f)),
            lineWidth = MvtExpression.Literal(2f),
            lineGradient = gradient,
        )
        assertTrue(paint.hasLineGradient)
        val mid = paint.buildGradientColor(zoom = 12, progress = 0.5)!!
        assertEquals(0.5f, mid.red, 1e-3f)
        val end = paint.buildGradientColor(zoom = 12, progress = 1.0)!!
        assertEquals(1f, end.red, 1e-3f)
    }

    @Test fun parserAcceptsLineGradientExpression() {
        val expr = MvtExpressionParser.parseColor(
            Json.parseToJsonElement("""
                ["interpolate", ["linear"], ["line-progress"],
                  0, "#000000",
                  0.5, "#888888",
                  1, "#ffffff"]
            """),
            parseColor = ::hexColor,
        )
        assertNotNull(expr)
        val zeroProgressCtx = MvtExpression.EvalContext(0.0, emptyMap(), lineProgress = 0.0)
        val midProgressCtx = MvtExpression.EvalContext(0.0, emptyMap(), lineProgress = 0.5)
        assertEquals(0f, expr.evaluate(zeroProgressCtx)!!.red, 1e-3f)
        assertEquals(0x88 / 255f, expr.evaluate(midProgressCtx)!!.red, 0.01f)
    }

    private fun hexColor(s: String): Color? {
        if (!s.startsWith("#") || s.length != 7) return null
        return Color(
            s.substring(1, 3).toInt(16),
            s.substring(3, 5).toInt(16),
            s.substring(5, 7).toInt(16),
        )
    }
}
