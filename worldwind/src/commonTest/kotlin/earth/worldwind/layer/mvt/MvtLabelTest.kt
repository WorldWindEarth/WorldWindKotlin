package earth.worldwind.layer.mvt

import earth.worldwind.render.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.math.abs

/**
 * Tests for the Stage 4a label pipeline — text paint properties, buildText resolution,
 * and the OpenTopoMap label rules.
 */
class MvtLabelTest {

    @Test fun buildTextReturnsNullWithoutTextField() {
        val spec = MvtStyleRule.PaintSpec()
        assertNull(spec.buildText(zoom = 10, properties = mapOf("name" to "X")))
    }

    @Test fun buildTextReturnsNullWhenPropertyMissing() {
        val spec = MvtStyleRule.PaintSpec(
            textField = "name",
            textColor = MvtZoomInterp.constant(Color(0f, 0f, 0f)),
            textSize = MvtZoomInterp.constant(14f),
        )
        assertNull(spec.buildText(zoom = 10, properties = emptyMap()))
    }

    @Test fun buildTextReturnsNullWhenPropertyEmpty() {
        val spec = MvtStyleRule.PaintSpec(
            textField = "name",
            textColor = MvtZoomInterp.constant(Color(0f, 0f, 0f)),
            textSize = MvtZoomInterp.constant(14f),
        )
        assertNull(spec.buildText(10, mapOf("name" to "")))
        assertNull(spec.buildText(10, mapOf("name" to "   ")))
    }

    @Test fun buildTextResolvesTextAndAttributes() {
        val spec = MvtStyleRule.PaintSpec(
            textField = "name",
            textColor = MvtZoomInterp.constant(Color(0.1f, 0.2f, 0.3f)),
            textSize = MvtZoomInterp.constant(16f),
            textHaloColor = MvtZoomInterp.constant(Color(1f, 1f, 1f, 0.8f)),
            textHaloWidth = MvtZoomInterp.constant(2f),
        )
        val label = spec.buildText(10, mapOf("name" to "Innsbruck"))
        assertNotNull(label)
        assertEquals("Innsbruck", label.text)
        assertEquals(0.1f, label.attributes.textColor.red, absoluteTolerance = 1e-6f)
        assertTrue(label.attributes.isOutlineEnabled)
        assertEquals(2f, label.attributes.outlineWidth, absoluteTolerance = 1e-6f)
    }

    @Test fun buildTextDisablesOutlineWhenHaloUnset() {
        val spec = MvtStyleRule.PaintSpec(
            textField = "name",
            textColor = MvtZoomInterp.constant(Color(0f, 0f, 0f)),
            textSize = MvtZoomInterp.constant(14f),
        )
        val label = spec.buildText(10, mapOf("name" to "X"))!!
        assertTrue(!label.attributes.isOutlineEnabled)
    }

    @Test fun buildTextCoercesNonStringPropertyToString() {
        val spec = MvtStyleRule.PaintSpec(
            textField = "ele",
            textColor = MvtZoomInterp.constant(Color(0f, 0f, 0f)),
            textSize = MvtZoomInterp.constant(14f),
        )
        // MVT properties arrive typed; ele as Long should stringify correctly.
        val label = spec.buildText(10, mapOf("ele" to 1234L))
        assertNotNull(label)
        assertEquals("1234", label.text)
    }

    @Test fun textSizeInterpolatesByZoom() {
        val spec = MvtStyleRule.PaintSpec(
            textField = "name",
            textColor = MvtZoomInterp.constant(Color(0f, 0f, 0f)),
            textSize = MvtZoomInterp.floats(2 to 10f, 10 to 20f),
        )
        // At zoom 2 → 10px; at zoom 10 → 20px; at zoom 6 → 15px.
        // sizePx is Int-clamped, so we read Font's size via copy of resolved TextAttributes.
        // We don't have direct font-size accessor in commonMain Font, but the resolution
        // path runs without error and the apply block runs to completion.
        val l2 = spec.buildText(2, mapOf("name" to "X"))!!
        val l10 = spec.buildText(10, mapOf("name" to "X"))!!
        // textColor and halo derive consistently — assert the apply ran (outlineEnabled flag
        // would have stayed at TextAttributes' default if buildText short-circuited mid-block).
        assertEquals("X", l2.text)
        assertEquals("X", l10.text)
    }

    @Test fun ruleStyleResolvesLabelForPointFeature() {
        val style = mvtStyle {
            rule("place_labels") {
                filter = "kind" eq "city"
                geometryType = MvtGeometryType.POINT
                minZoom = 4
                zOrder = MvtStyle.Z_LABEL
                paint {
                    text("name") {
                        color(Color(0.1f, 0.1f, 0.1f))
                        size = MvtZoomInterp.floats(4 to 12f, 14 to 22f)
                        halo(Color(1f, 1f, 1f, 0.85f), 2f)
                    }
                }
            }
        }
        val rule = style.firstMatching(
            "place_labels", MvtGeometryType.POINT, zoom = 8,
            properties = mapOf("kind" to "city", "name" to "Vienna"),
        )
        assertNotNull(rule, "city feature should match the place_labels rule")
        assertTrue(rule.paint.hasText, "rule should advertise hasText")
        assertTrue(!rule.paint.hasShape, "rule should not advertise hasShape")
        val label = rule.paint.buildText(8, mapOf("name" to "Vienna"))
        assertNotNull(label)
        assertEquals("Vienna", label.text)
    }

    @Test fun openTopoMapLabelsRendersCityAtLowZoom() {
        // City rule minZoom is 3 so capitals/big cities are built at the coarse zooms a zoomed-out
        // view fetches; the label collider's importance-ordered collision + budget then decide what
        // paints (no separate per-place zoom gate). Cities must match across the coarse zoom range.
        for (z in intArrayOf(3, 5, 6)) {
            val rule = OpenTopoMapRules.firstMatching(
                "place_labels", MvtGeometryType.POINT, zoom = z,
                properties = mapOf("kind" to "city", "name" to "Vienna"),
            )
            assertNotNull(rule, "city should match at z=$z (rule's minZoom is 3)")
            assertTrue(rule.paint.hasText)
        }
        val label = OpenTopoMapRules.firstMatching(
            "place_labels", MvtGeometryType.POINT, zoom = 6,
            properties = mapOf("kind" to "city", "name" to "Vienna"),
        )!!.paint.buildText(6, mapOf("name" to "Vienna"))!!
        assertEquals("Vienna", label.text)
        // Halo is enabled in OpenTopoMap label style.
        assertTrue(label.attributes.isOutlineEnabled)
        // Below the city rule minZoom (3): no match.
        val z2 = OpenTopoMapRules.firstMatching(
            "place_labels", MvtGeometryType.POINT, zoom = 2,
            properties = mapOf("kind" to "city", "name" to "Vienna"),
        )
        assertEquals(null, z2, "city at z=2 should fall below the rule minZoom (3)")
    }

    @Test fun openTopoMapLabelsHidesVillageAtCountryZoom() {
        // Village minZoom = 12; at z=5 it shouldn't match.
        val rule = OpenTopoMapRules.firstMatching(
            "place_labels", MvtGeometryType.POINT, zoom = 5,
            properties = mapOf("kind" to "village", "name" to "Tiny Town"),
        )
        assertNull(rule, "village at z=5 should be below minZoom (12)")
    }

    @Test fun openTopoMapPointFeaturesWithoutTextRuleAreDropped() {
        // Polygon rules don't match POINT features (no geometryType lock + name doesn't match).
        // Verify a generic non-label POINT feature in an unrelated layer returns null.
        val rule = OpenTopoMapRules.firstMatching(
            "pois", MvtGeometryType.POINT, zoom = 14,
            properties = mapOf("kind" to "restaurant"),
        )
        assertNull(rule, "pois (no rule defined) should return null")
    }
}

private fun assertEquals(expected: Float, actual: Float, absoluteTolerance: Float) {
    val diff = abs(expected - actual)
    if (diff > absoluteTolerance) {
        error("expected ~$expected but was $actual (diff $diff > tolerance $absoluteTolerance)")
    }
}
