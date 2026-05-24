package earth.worldwind.layer.mvt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MvtMapboxStyleLoaderTest {

    @Test fun parsesEmptyStyle() {
        val style = MvtMapboxStyleLoader.parse("""{"version":8,"layers":[]}""")
        assertEquals(0, style.rules.size)
    }

    @Test fun parsesSimpleFillLayer() {
        val style = MvtMapboxStyleLoader.parse(
            """
            {
              "version": 8,
              "layers": [
                {
                  "id": "water",
                  "type": "fill",
                  "source": "openmaptiles",
                  "source-layer": "water",
                  "paint": { "fill-color": "#aaccee" }
                }
              ]
            }
            """.trimIndent()
        )
        val rule = style.rules.single()
        assertEquals("water", rule.sourceLayer)
        val attrs = rule.resolve(zoom = 10)
        assertTrue(attrs.isDrawInterior)
        // #aaccee → roughly (0.667, 0.8, 0.933).
        assertEquals(0xaa / 255f, attrs.interiorColor.red, 1e-3f)
        assertEquals(0xcc / 255f, attrs.interiorColor.green, 1e-3f)
        assertEquals(0xee / 255f, attrs.interiorColor.blue, 1e-3f)
    }

    @Test fun parsesLineLayerWithZoomStops() {
        val style = MvtMapboxStyleLoader.parse(
            """
            {
              "version": 8,
              "layers": [
                {
                  "id": "roads",
                  "type": "line",
                  "source-layer": "transportation",
                  "paint": {
                    "line-color": "#ff8866",
                    "line-width": { "stops": [[6, 1.0], [12, 3.0]] }
                  }
                }
              ]
            }
            """.trimIndent()
        )
        val rule = style.rules.single()
        // Width interpolates between zoom 6 (1.0) and zoom 12 (3.0). At zoom 9: 2.0.
        assertEquals(1f, rule.resolve(6).outlineWidth, 1e-3f)
        assertEquals(2f, rule.resolve(9).outlineWidth, 1e-3f)
        assertEquals(3f, rule.resolve(12).outlineWidth, 1e-3f)
    }

    @Test fun parsesLegacyFilters() {
        val style = MvtMapboxStyleLoader.parse(
            """
            {"version": 8, "layers": [
              {
                "id": "motorway",
                "type": "line",
                "source-layer": "transportation",
                "filter": ["==", "class", "motorway"],
                "paint": { "line-color": "#ff0000", "line-width": 2 }
              }
            ]}
            """.trimIndent()
        )
        val rule = style.rules.single()
        assertTrue(rule.matches("transportation", MvtGeometryType.LINESTRING, 10, mapOf("class" to "motorway")))
        assertTrue(!rule.matches("transportation", MvtGeometryType.LINESTRING, 10, mapOf("class" to "trunk")))
    }

    @Test fun parsesAllAnyFilters() {
        val style = MvtMapboxStyleLoader.parse(
            """
            {"version": 8, "layers": [
              {
                "id": "ok",
                "type": "line",
                "source-layer": "transportation",
                "filter": ["all", ["==", "class", "motorway"], [">=", "lanes", 4]],
                "paint": { "line-color": "#0000ff", "line-width": 1 }
              }
            ]}
            """.trimIndent()
        )
        val rule = style.rules.single()
        assertTrue(rule.matches("transportation", MvtGeometryType.LINESTRING, 10, mapOf("class" to "motorway", "lanes" to 4L)))
        assertTrue(!rule.matches("transportation", MvtGeometryType.LINESTRING, 10, mapOf("class" to "motorway", "lanes" to 2L)))
    }

    @Test fun parsesInAndNotIn() {
        val style = MvtMapboxStyleLoader.parse(
            """
            {"version": 8, "layers": [
              {
                "id": "majors",
                "type": "line",
                "source-layer": "transportation",
                "filter": ["in", "class", "motorway", "trunk", "primary"],
                "paint": { "line-color": "#ff0", "line-width": 1 }
              },
              {
                "id": "not-majors",
                "type": "line",
                "source-layer": "transportation",
                "filter": ["!in", "class", "motorway", "trunk", "primary"],
                "paint": { "line-color": "#00f", "line-width": 1 }
              }
            ]}
            """.trimIndent()
        )
        val majors = style.rules[0]
        val notMajors = style.rules[1]
        assertTrue(majors.matches("transportation", MvtGeometryType.LINESTRING, 10, mapOf("class" to "trunk")))
        assertTrue(notMajors.matches("transportation", MvtGeometryType.LINESTRING, 10, mapOf("class" to "service")))
    }

    @Test fun parsesSymbolLayer() {
        val style = MvtMapboxStyleLoader.parse(
            """
            {"version": 8, "layers": [
              {
                "id": "city-labels",
                "type": "symbol",
                "source-layer": "place",
                "filter": ["==", "class", "city"],
                "layout": {
                  "text-field": "{name}",
                  "text-size": { "stops": [[6, 14], [12, 22]] },
                  "text-font": ["Open Sans Bold"]
                },
                "paint": {
                  "text-color": "#000000",
                  "text-halo-color": "#ffffff",
                  "text-halo-width": 2
                }
              }
            ]}
            """.trimIndent()
        )
        val rule = style.rules.single()
        assertTrue(rule.paint.hasText)
        val label = rule.paint.buildText(10, mapOf("name" to "Vienna", "class" to "city"))
        assertNotNull(label)
        assertEquals("Vienna", label.text)
        // z=10 between 6→14 and 12→22: 14 + (10-6)/(12-6) * (22-14) = 14 + 5.33 ≈ 19.33 → 19
        assertEquals(19, label.pixelSize)
    }

    @Test fun skipsLayerWithVisibilityNone() {
        val style = MvtMapboxStyleLoader.parse(
            """
            {"version": 8, "layers": [
              {
                "id": "hidden",
                "type": "fill",
                "source-layer": "water",
                "layout": { "visibility": "none" },
                "paint": { "fill-color": "#fff" }
              }
            ]}
            """.trimIndent()
        )
        assertEquals(0, style.rules.size)
    }

    @Test fun parsesModernInterpolateExpression() {
        val style = MvtMapboxStyleLoader.parse(
            """
            {"version": 8, "layers": [
              {
                "id": "x",
                "type": "line",
                "source-layer": "x",
                "paint": {
                  "line-color": "#000000",
                  "line-width": ["interpolate", ["linear"], ["zoom"], 6, 1.0, 12, 3.0]
                }
              }
            ]}
            """.trimIndent()
        )
        val rule = style.rules.single()
        val width = rule.paint.lineWidth!!
        // z=6 → 1, z=12 → 3, linear midpoint z=9 → 2.
        assertEquals(1f, width.evaluate(MvtExpression.EvalContext(6.0, emptyMap()))!!, absoluteTolerance = 1e-4f)
        assertEquals(2f, width.evaluate(MvtExpression.EvalContext(9.0, emptyMap()))!!, absoluteTolerance = 1e-4f)
        assertEquals(3f, width.evaluate(MvtExpression.EvalContext(12.0, emptyMap()))!!, absoluteTolerance = 1e-4f)
    }

    @Test fun parsesShortHexAndRgba() {
        val c3 = MvtMapboxStyleLoader.parseColor("#abc")
        assertNotNull(c3)
        assertEquals(0xaa / 255f, c3.red, 1e-3f)
        assertEquals(0xbb / 255f, c3.green, 1e-3f)
        assertEquals(0xcc / 255f, c3.blue, 1e-3f)

        val c8 = MvtMapboxStyleLoader.parseColor("#10203040")
        assertNotNull(c8)
        assertEquals(0x40 / 255f, c8.alpha, 1e-3f)

        val rgba = MvtMapboxStyleLoader.parseColor("rgba(255, 128, 64, 0.5)")
        assertNotNull(rgba)
        assertEquals(1f, rgba.red, 1e-3f)
        assertEquals(128 / 255f, rgba.green, 1e-3f)
        assertEquals(64 / 255f, rgba.blue, 1e-3f)
        // 0.5 alpha goes through .toInt() * 255 round-trip — tolerance 0.5/255 ≈ 0.002.
        assertEquals(0.5f, rgba.alpha, 0.005f)

        val bad = MvtMapboxStyleLoader.parseColor("rebeccapurple")
        assertNull(bad)
    }

    @Test fun parsesNumericComparisonFilters() {
        val style = MvtMapboxStyleLoader.parse(
            """
            {"version": 8, "layers": [
              {
                "id": "high-pop",
                "type": "symbol",
                "source-layer": "place",
                "filter": [">", "population", 100000],
                "layout": { "text-field": "{name}", "text-size": 14 },
                "paint": { "text-color": "#000" }
              }
            ]}
            """.trimIndent()
        )
        val rule = style.rules.single()
        assertTrue(rule.matches("place", MvtGeometryType.POINT, 10, mapOf("population" to 500000L, "name" to "X")))
        assertTrue(!rule.matches("place", MvtGeometryType.POINT, 10, mapOf("population" to 50000L, "name" to "X")))
    }
}

private fun assertEquals(expected: Float, actual: Float, tol: Float) {
    if (kotlin.math.abs(expected - actual) > tol)
        error("expected ~$expected but was $actual (tol $tol)")
}
