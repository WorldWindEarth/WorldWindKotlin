package earth.worldwind.layer.mvt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MvtSpriteAtlasTest {

    private val mapboxStyleManifest = """
        {
          "mountain-peak": { "width": 24, "height": 24, "x": 0,  "y": 0,  "pixelRatio": 1 },
          "airport":       { "width": 32, "height": 32, "x": 24, "y": 0,  "pixelRatio": 1 },
          "transit-stop":  { "width": 16, "height": 16, "x": 0,  "y": 24, "pixelRatio": 2 }
        }
    """.trimIndent()

    @Test fun parsesMapboxManifest() {
        val entries = MvtSpriteAtlas.parseManifest(mapboxStyleManifest)
        assertEquals(3, entries.size)
        val peak = assertNotNull(entries["mountain-peak"])
        assertEquals(0, peak.x); assertEquals(0, peak.y)
        assertEquals(24, peak.width); assertEquals(24, peak.height)
        assertEquals(1.0, peak.pixelRatio)
        val transit = assertNotNull(entries["transit-stop"])
        assertEquals(2.0, transit.pixelRatio)
    }

    @Test fun missingEntryReturnsNull() {
        val atlas = MvtSpriteAtlas(
            MvtSpriteAtlas.parseManifest(mapboxStyleManifest),
            imageBytes = ByteArray(0),
        )
        assertNotNull(atlas.entry("airport"))
        assertNull(atlas.entry("nonexistent"))
    }

    @Test fun iconFactoryReturnsNullForMissingEntry() {
        val atlas = MvtSpriteAtlas(
            MvtSpriteAtlas.parseManifest(mapboxStyleManifest),
            imageBytes = ByteArray(0),
        )
        assertNotNull(atlas.iconFactory("mountain-peak"))
        assertNull(atlas.iconFactory("nonexistent"))
    }

    @Test fun manifestSkipsMalformedEntries() {
        // Missing required fields → entry dropped. Extra fields → ignored. Float coords clamp.
        val json = """
            {
              "valid":     { "width": 10, "height": 10, "x": 0, "y": 0 },
              "no-width":  { "height": 5, "x": 0, "y": 0 },
              "no-height": { "width": 5, "x": 0, "y": 0 },
              "no-x":      { "width": 5, "height": 5, "y": 0 },
              "extra-ok":  { "width": 8, "height": 8, "x": 0, "y": 0, "metadata": "ignored" }
            }
        """.trimIndent()
        val entries = MvtSpriteAtlas.parseManifest(json)
        assertEquals(2, entries.size)
        assertNotNull(entries["valid"])
        assertNotNull(entries["extra-ok"])
    }

    @Test fun substituteTemplateExpandsCurlyBracePlaceholders() {
        val props = mapOf<String, Any?>("kind" to "motorway", "name" to "I-280")
        assertEquals("poi-motorway", MvtStyleRule.PaintSpec.substituteTemplate("poi-{kind}", props))
        assertEquals("motorway / I-280", MvtStyleRule.PaintSpec.substituteTemplate("{kind} / {name}", props))
        // Missing key expands to empty string per Mapbox semantics.
        assertEquals("a-", MvtStyleRule.PaintSpec.substituteTemplate("a-{missing}", props))
        // No placeholders → identity.
        assertEquals("plain", MvtStyleRule.PaintSpec.substituteTemplate("plain", props))
    }

    @Test fun buildIconResolvesNameSizeAnchor() {
        val paint = MvtStyleRule.PaintSpec(
            iconImage = MvtExpression.Literal("airport"),
            iconSize = MvtExpression.Literal(1.5f),
            iconAnchor = MvtExpression.Literal("bottom"),
        )
        val spec = assertNotNull(paint.buildIcon(zoom = 12, properties = emptyMap()))
        assertEquals("airport", spec.name)
        assertEquals(1.5f, spec.size)
        assertEquals("bottom", spec.anchor)
        assertEquals(0f, spec.offset) // default
    }

    @Test fun buildIconAppliesTemplateSubstitution() {
        val paint = MvtStyleRule.PaintSpec(
            iconImage = MvtExpression.Literal("poi-{kind}"),
        )
        val spec = assertNotNull(paint.buildIcon(zoom = 12, mapOf("kind" to "restaurant")))
        assertEquals("poi-restaurant", spec.name)
    }

    @Test fun buildIconSkipsEmptyName() {
        val paint = MvtStyleRule.PaintSpec(iconImage = MvtExpression.Literal(""))
        assertNull(paint.buildIcon(zoom = 12, emptyMap()))
    }
}
