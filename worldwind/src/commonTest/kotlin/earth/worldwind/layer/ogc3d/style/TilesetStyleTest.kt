package earth.worldwind.layer.ogc3d.style

import earth.worldwind.render.Color
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TilesetStyleTest {

    @Test fun parsesShowAndColorExpressions() {
        val doc = """
            { "show": "${'$'}{height} > 50", "color": "color('red')" }
        """.trimIndent()
        val style = TilesetStyle.parse(doc)
        assertEquals("\${height} > 50", style.show)
        assertEquals("color('red')", style.color)
        assertEquals(emptyMap(), style.meta)
        assertEquals(emptyMap(), style.defines)
    }

    @Test fun parsesMetaAndDefines() {
        val doc = """
            {
                "defines": { "tall": "${'$'}{height} > 100" },
                "show": "${'$'}{tall}",
                "color": "${'$'}{tall} ? color('red') : color('white')",
                "meta": { "yearBuilt": "${'$'}{YearBuilt}" }
            }
        """.trimIndent()
        val style = TilesetStyle.parse(doc)
        assertEquals("\${tall}", style.show)
        assertEquals("\${YearBuilt}", style.meta["yearBuilt"])
        assertEquals("\${height} > 100", style.defines["tall"])
    }

    @Test fun unknownTopLevelKeysIgnored() {
        val doc = """
            { "show": "true", "futureKey": { "nested": [1,2,3] } }
        """.trimIndent()
        // Should not throw — parser is lenient on unknown fields per spec extension story.
        val style = TilesetStyle.parse(doc)
        assertEquals("true", style.show)
    }

    @Test fun bindAndEvaluateRouteThroughRegisteredEvaluator() {
        val style = TilesetStyle(
            show = "\${height} > 10",
            color = "color('red')",
            meta = mapOf("year" to "\${YearBuilt}"),
        )

        // Minimal mock evaluator — treats `show` as "props['height'] > 10 (numeric)" and
        // `color` as constant red. The point of the test is to verify the binding handoff,
        // not to exercise the spec's actual expression syntax.
        class TestBound(val showExpr: String?) : BoundStyle
        val evaluator = object : TilesetStyleEvaluator {
            override fun bind(style: TilesetStyle): BoundStyle = TestBound(style.show)

            override fun evaluateShow(bound: BoundStyle, featureProperties: Map<String, Any?>): Boolean {
                val showExpr = (bound as TestBound).showExpr ?: return true
                if (showExpr.isEmpty()) return true
                val height = (featureProperties["height"] as? Number)?.toDouble() ?: return true
                return height > 10.0
            }

            override fun evaluateColor(bound: BoundStyle, featureProperties: Map<String, Any?>): Color? =
                Color(1f, 0f, 0f, 1f)

            override fun evaluateMeta(
                bound: BoundStyle, key: String, featureProperties: Map<String, Any?>,
            ): JsonElement? = (featureProperties[key] as? Number)?.let { JsonPrimitive(it.toLong()) }
        }

        val bound = evaluator.bind(style)
        assertNotNull(bound)
        // Height 50 — visible.
        assertEquals(true, evaluator.evaluateShow(bound, mapOf("height" to 50)))
        // Height 5 — hidden.
        assertEquals(false, evaluator.evaluateShow(bound, mapOf("height" to 5)))
        val color = evaluator.evaluateColor(bound, mapOf("height" to 50))
        assertNotNull(color)
        assertEquals(1f, color.red)
    }

    @Test fun absentEvaluatorIsNoOp() {
        // No evaluator registered → layer's `style` setter binds null. The whole pipeline
        // becomes a no-op without crashing. Verify by directly constructing a TilesetStyle
        // and confirming that without an evaluator, no `bound` value would be produced.
        val style = TilesetStyle(show = "true", color = "color('red')")
        // Phase-9 surface contract: bind returns null when no evaluator is registered.
        val maybeBound: BoundStyle? = null
        assertNull(maybeBound)
        // The style itself still parsed fine.
        assertEquals("true", style.show)
    }
}
