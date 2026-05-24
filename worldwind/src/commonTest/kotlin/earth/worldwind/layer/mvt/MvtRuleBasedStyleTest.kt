package earth.worldwind.layer.mvt

import earth.worldwind.render.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MvtRuleBasedStyleTest {

    private val style = mvtStyle {
        rule("water_polygons") {
            zOrder = MvtStyle.Z_WATER
            paint { fill(Color(0f, 0.5f, 1f)) }
        }
        rule("streets") {
            filter = "kind" eq "motorway"
            minZoom = 6
            zOrder = MvtStyle.Z_ROAD_MOTORWAY
            paint {
                lineColor = MvtZoomInterp.constant(Color(1f, 0f, 0f))
                lineWidth = MvtZoomInterp.floats(6 to 1f, 12 to 3f)
            }
        }
        rule("streets") {
            filter = "kind" eq "residential"
            minZoom = 12
            zOrder = MvtStyle.Z_ROAD_MINOR
            paint {
                lineColor = MvtZoomInterp.constant(Color(0f, 0f, 0f))
                lineWidth = MvtZoomInterp.constant(0.5f)
            }
        }
    }

    @Test fun matchesByLayerName() {
        val rule = style.firstMatching("water_polygons", MvtGeometryType.POLYGON, 5, emptyMap())
        assertNotNull(rule)
        assertEquals(MvtStyle.Z_WATER, rule.zOrder)
    }

    @Test fun missesUnknownLayer() {
        val rule = style.firstMatching("unknown_layer", MvtGeometryType.POLYGON, 5, emptyMap())
        assertNull(rule)
    }

    @Test fun filterGatesByProperty() {
        // streets + kind=motorway matches the first streets rule.
        val motorwayProps = mapOf<String, Any?>("kind" to "motorway")
        val r = style.firstMatching("streets", MvtGeometryType.LINESTRING, zoom = 10, motorwayProps)
        assertNotNull(r)
        assertEquals(MvtStyle.Z_ROAD_MOTORWAY, r.zOrder)
        // streets + kind=residential at z=10 fails the residential rule's minZoom — null.
        val resProps = mapOf<String, Any?>("kind" to "residential")
        assertNull(style.firstMatching("streets", MvtGeometryType.LINESTRING, zoom = 10, resProps))
        // ...but at z=12 it matches.
        val r2 = style.firstMatching("streets", MvtGeometryType.LINESTRING, zoom = 12, resProps)
        assertNotNull(r2)
        assertEquals(MvtStyle.Z_ROAD_MINOR, r2.zOrder)
    }

    @Test fun minZoomGatesEntireRule() {
        // streets + motorway at z=3 is below the rule's minZoom=6 — no match.
        val motorwayProps = mapOf<String, Any?>("kind" to "motorway")
        assertNull(style.firstMatching("streets", MvtGeometryType.LINESTRING, zoom = 3, motorwayProps))
        assertNotNull(style.firstMatching("streets", MvtGeometryType.LINESTRING, zoom = 6, motorwayProps))
    }

    @Test fun firstMatchingWinsRulesAreOrderSensitive() {
        // Two rules for the same layer where the FIRST has no filter — it'd always win.
        val s = mvtStyle {
            rule("streets") {
                zOrder = 1
                paint { line(Color(1f, 0f, 0f)) }
            }
            rule("streets") {
                filter = "kind" eq "motorway"
                zOrder = 2
                paint { line(Color(0f, 1f, 0f)) }
            }
        }
        val r = s.firstMatching("streets", MvtGeometryType.LINESTRING, 5, mapOf("kind" to "motorway"))
        assertEquals(1, r?.zOrder) // first rule wins despite second being more specific
    }

    @Test fun paintResolutionAppliesInterpolation() {
        val motorwayProps = mapOf<String, Any?>("kind" to "motorway")
        // At z=6, line width should be 1f; at z=12, 3f; at z=9, 2f.
        val attrsAt6 = style.styleFor("streets", MvtGeometryType.LINESTRING, zoom = 6, motorwayProps)
        val attrsAt9 = style.styleFor("streets", MvtGeometryType.LINESTRING, zoom = 9, motorwayProps)
        val attrsAt12 = style.styleFor("streets", MvtGeometryType.LINESTRING, zoom = 12, motorwayProps)
        assertEquals(1f, attrsAt6?.outlineWidth)
        assertEquals(2f, attrsAt9?.outlineWidth, absoluteTolerance = 1e-5f)
        assertEquals(3f, attrsAt12?.outlineWidth)
    }

    @Test fun mvtStyleZoomBlindStyleForFallsBack() {
        // Zoom-blind overload routes through zoom=0 — used by callers that don't know how
        // to pass tile zoom. Should still match rules whose minZoom <= 0.
        val s = mvtStyle {
            rule("streets") {
                filter = "kind" eq "motorway"
                paint { line(Color(1f, 0f, 0f)) }
            }
        }
        val attrs = (s as MvtStyle).styleFor(
            "streets", MvtGeometryType.LINESTRING, mapOf("kind" to "motorway")
        )
        assertNotNull(attrs)
        assertTrue(attrs.isDrawOutline)
    }

    @Test fun geometryTypeGate() {
        val s = mvtStyle {
            rule("streets") {
                geometryType = MvtGeometryType.LINESTRING
                paint { line(Color(0f, 0f, 0f)) }
            }
        }
        assertNotNull(s.firstMatching("streets", MvtGeometryType.LINESTRING, 5, emptyMap()))
        assertNull(s.firstMatching("streets", MvtGeometryType.POLYGON, 5, emptyMap()))
    }
}

// Helper for absoluteTolerance signature when KMP's kotlin.test doesn't infer the Float overload.
private fun assertEquals(expected: Float, actual: Float?, absoluteTolerance: Float) {
    if (actual == null) error("expected $expected, got null")
    val diff = kotlin.math.abs(expected - actual)
    if (diff > absoluteTolerance) {
        error("expected ~$expected but was $actual (diff $diff > tolerance $absoluteTolerance)")
    }
}
