package earth.worldwind.layer.mvt

import earth.worldwind.render.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MvtExpressionParserTest {

    private fun parse(json: String): JsonElement = Json.parseToJsonElement(json)

    private fun parseHex(s: String): Color? {
        if (!s.startsWith("#") || s.length != 7) return null
        return Color(
            s.substring(1, 3).toInt(16),
            s.substring(3, 5).toInt(16),
            s.substring(5, 7).toInt(16),
        )
    }

    private fun ctx(zoom: Double = 0.0, vararg props: Pair<String, Any?>) =
        MvtExpression.EvalContext(zoom, props.toMap())

    @Test fun parsesModernInterpolateLinearFloat() {
        val expr = MvtExpressionParser.parseFloat(parse("""
            ["interpolate", ["linear"], ["zoom"], 5, 0.0, 10, 10.0]
        """))
        assertNotNull(expr)
        assertEquals(5.0f, expr.evaluate(ctx(zoom = 7.5))!!, absoluteTolerance = 1e-4f)
    }

    @Test fun parsesModernInterpolateExponentialFloat() {
        val expr = MvtExpressionParser.parseFloat(parse("""
            ["interpolate", ["exponential", 2], ["zoom"], 5, 0.0, 10, 10.0]
        """))
        assertNotNull(expr)
        // Exponential base 2 returns a value <5 at the midpoint (lo-biased).
        val v = expr.evaluate(ctx(zoom = 7.5))!!
        assertEquals(true, v < 5.0f)
    }

    @Test fun parsesCaseExpression() {
        val expr = MvtExpressionParser.parseFloat(parse("""
            ["case",
                ["==", ["get", "kind"], "motorway"], 3.0,
                ["==", ["get", "kind"], "trunk"], 2.0,
                1.0]
        """))
        assertNotNull(expr)
        assertEquals(3.0f, expr.evaluate(ctx(props = arrayOf("kind" to "motorway"))))
        assertEquals(2.0f, expr.evaluate(ctx(props = arrayOf("kind" to "trunk"))))
        assertEquals(1.0f, expr.evaluate(ctx(props = arrayOf("kind" to "residential"))))
    }

    @Test fun parsesMatchExpression() {
        val expr = MvtExpressionParser.parseFloat(parse("""
            ["match", ["get", "class"],
                ["motorway", "trunk"], 3.0,
                "primary", 2.0,
                1.0]
        """))
        assertNotNull(expr)
        assertEquals(3.0f, expr.evaluate(ctx(props = arrayOf("class" to "motorway"))))
        assertEquals(3.0f, expr.evaluate(ctx(props = arrayOf("class" to "trunk"))))
        assertEquals(2.0f, expr.evaluate(ctx(props = arrayOf("class" to "primary"))))
        assertEquals(1.0f, expr.evaluate(ctx(props = arrayOf("class" to "service"))))
    }

    @Test fun parsesStepExpression() {
        val expr = MvtExpressionParser.parseFloat(parse("""
            ["step", ["zoom"], 0.5, 10, 1.0, 14, 2.0]
        """))
        assertNotNull(expr)
        assertEquals(0.5f, expr.evaluate(ctx(zoom = 5.0)))
        assertEquals(1.0f, expr.evaluate(ctx(zoom = 12.0)))
        assertEquals(2.0f, expr.evaluate(ctx(zoom = 14.0)))
    }

    @Test fun parsesColorInterpolation() {
        val expr = MvtExpressionParser.parseColor(parse("""
            ["interpolate", ["linear"], ["zoom"], 0, "#000000", 10, "#ffffff"]
        """), ::parseHex)
        assertNotNull(expr)
        val mid = expr.evaluate(ctx(zoom = 5.0))!!
        assertEquals(0.5f, mid.red, absoluteTolerance = 1e-2f)
    }

    @Test fun unsupportedExpressionReturnsNull() {
        // Unknown operator → parser returns null.
        val expr = MvtExpressionParser.parseFloat(parse("""
            ["totally-not-an-operator", 1, 2]
        """))
        assertNull(expr)
    }

    @Test fun parsesNestedAllAndComparison() {
        val expr = MvtExpressionParser.parseFloat(parse("""
            ["case",
                ["all", ["==", ["get", "kind"], "motorway"], [">=", ["zoom"], 6]], 4.0,
                1.0]
        """))
        assertNotNull(expr)
        assertEquals(4.0f, expr.evaluate(ctx(zoom = 8.0, props = arrayOf("kind" to "motorway"))))
        assertEquals(1.0f, expr.evaluate(ctx(zoom = 4.0, props = arrayOf("kind" to "motorway"))))
        assertEquals(1.0f, expr.evaluate(ctx(zoom = 8.0, props = arrayOf("kind" to "primary"))))
    }
}
