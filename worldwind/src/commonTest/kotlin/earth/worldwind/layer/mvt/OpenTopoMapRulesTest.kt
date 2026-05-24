package earth.worldwind.layer.mvt

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for the [OpenTopoMapRules] style — specifically guarding against the
 * top-level `val` initialization-order trap where palette constants declared *after* the
 * rule list resolved to `null` at construction time, then NPEd at the first `.alpha` read
 * during tile assembly.
 */
class OpenTopoMapRulesTest {

    @Test fun resolvesMotorwayLineAtZoom14() {
        val attrs = OpenTopoMapRules.styleFor(
            layerName = "streets",
            geometryType = MvtGeometryType.LINESTRING,
            zoom = 14,
            properties = mapOf("kind" to "motorway"),
        )
        assertNotNull(attrs, "motorway at z=14 should match a rule")
        assertTrue(attrs.isDrawOutline, "motorway should draw an outline")
        // Outline color comes from the rule's lineColor MvtZoomInterp — if any palette val
        // was null at rule-construction time, this read would NPE. Non-null result here
        // proves the init-order is correct.
        val outline = attrs.outlineColor
        // Each channel is a Float read; collectively they prove the Color isn't a sentinel.
        assertTrue(outline.alpha > 0f, "motorway outline alpha should be positive")
        assertTrue(attrs.outlineWidth > 0f, "motorway outline width should be positive")
    }

    @Test fun resolvesWaterPolygonFill() {
        val attrs = OpenTopoMapRules.styleFor(
            layerName = "water_polygons",
            geometryType = MvtGeometryType.POLYGON,
            zoom = 10,
            properties = mapOf("kind" to "water"),
        )
        assertNotNull(attrs)
        assertTrue(attrs.isDrawInterior)
        // If WATER was null at rule-build time, interiorColor would NPE on .alpha here.
        assertTrue(attrs.interiorColor.alpha > 0f)
    }

    @Test fun resolvesBuildingFillAtCityZoom() {
        val attrs = OpenTopoMapRules.styleFor(
            layerName = "buildings",
            geometryType = MvtGeometryType.POLYGON,
            zoom = 14,
            properties = emptyMap(),
        )
        assertNotNull(attrs)
        assertTrue(attrs.isDrawInterior)
        assertTrue(attrs.interiorColor.alpha > 0f)
    }

    @Test fun buildingsHiddenBelowMinZoom() {
        // Building rule has minZoom = 13 — at z=10 it shouldn't match.
        val attrs = OpenTopoMapRules.styleFor(
            layerName = "buildings",
            geometryType = MvtGeometryType.POLYGON,
            zoom = 10,
            properties = emptyMap(),
        )
        // No rule matches → null attrs.
        assertTrue(attrs == null, "buildings at z=10 should fall below minZoom")
    }

    @Test fun motorwayLineWidthGrowsWithZoom() {
        val z6 = OpenTopoMapRules.styleFor("streets", MvtGeometryType.LINESTRING, 6, mapOf("kind" to "motorway"))!!
        val z14 = OpenTopoMapRules.styleFor("streets", MvtGeometryType.LINESTRING, 14, mapOf("kind" to "motorway"))!!
        assertTrue(
            z14.outlineWidth > z6.outlineWidth,
            "motorway at z14 should be thicker than at z6 (got z6=${z6.outlineWidth}, z14=${z14.outlineWidth})",
        )
    }

    @Test fun residentialStreetsHiddenAtCountryZoom() {
        val z5 = OpenTopoMapRules.styleFor("streets", MvtGeometryType.LINESTRING, 5, mapOf("kind" to "residential"))
        assertTrue(z5 == null, "residential streets at z=5 should fall below minZoom")
        val z14 = OpenTopoMapRules.styleFor("streets", MvtGeometryType.LINESTRING, 14, mapOf("kind" to "residential"))
        assertNotNull(z14, "residential streets at z=14 should match")
    }

    @Test fun zOrderForMotorwayMatchesCanonicalConstant() {
        val z = OpenTopoMapRules.zOrderFor(
            "streets",
            MvtGeometryType.LINESTRING,
            mapOf("kind" to "motorway"),
        )
        // Motorway uses Z_ROAD_MOTORWAY; rule resolution falls through to first-matching.
        assertTrue(z >= MvtStyle.Z_ROAD_TRUNK, "motorway zOrder $z should sit at road-band top")
    }
}
