package earth.worldwind.layer

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.layer.source.GeoJsonBulkFeatureSource
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Label
import earth.worldwind.shape.Path
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.Polygon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import earth.worldwind.shape.ShapeAttributes

/**
 * Round-trip tests: GeoJSON text → [GeoJsonBulkFeatureSource] → [BulkFeatureLayer] →
 * renderables. Each test feeds a small document into a layer with the configuration under
 * test, runs [BulkFeatureLayer.load], and asserts the resulting renderables match the
 * legacy `GeoJsonLayerFactory`'s observable output (renderable types, counts, styling
 * defaults).
 */
class BulkFeatureLayerTest {

    @Test
    fun point_with_name_only_becomes_label() = runTest {
        val layer = layerOver(
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[10.0,20.0]},"properties":{"name":"Kyiv"}}"""
        )
        assertEquals(1, layer.renderables.size)
        val label = layer.renderables[0] as Label
        assertEquals("Kyiv", label.text)
    }

    @Test
    fun point_with_icon_becomes_placemark() = runTest {
        val layer = layerOver(
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[10.0,20.0]},
            | "properties":{"name":"Kyiv","icon":"https://example.com/pin.png"}}""".trimMargin()
        )
        assertTrue(layer.renderables[0] is Placemark)
    }

    @Test
    fun point_with_neither_name_nor_icon_still_emits_placemark() = runTest {
        val layer = layerOver(
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[10.0,20.0]},"properties":{}}"""
        )
        assertTrue(layer.renderables[0] is Placemark)
    }

    @Test
    fun name_property_aliases_are_recognised_for_label_emission() = runTest {
        // WFS / MapServer GeoJSON uses uppercase NAME — must still produce a Label not an
        // unlabeled Placemark.
        val layer = layerOver(
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[0.0,0.0]},"properties":{"NAME":"Cap"}}"""
        )
        assertTrue(layer.renderables[0] is Label)
    }

    @Test
    fun linestring_becomes_a_path() = runTest {
        val layer = layerOver(
            """{"type":"LineString","coordinates":[[0.0,0.0],[1.0,1.0],[2.0,2.0]]}"""
        )
        assertEquals(1, layer.renderables.size)
        assertTrue(layer.renderables[0] is Path)
    }

    @Test
    fun polygon_becomes_a_single_polygon_renderable() = runTest {
        val layer = layerOver(
            """{"type":"Polygon","coordinates":[[[0.0,0.0],[10.0,0.0],[10.0,10.0],[0.0,10.0],[0.0,0.0]]]}"""
        )
        assertEquals(1, layer.renderables.size)
        assertTrue(layer.renderables[0] is Polygon)
    }

    @Test
    fun multipolygon_expands_to_n_polygons_sharing_one_properties_map() = runTest {
        var lambdaInvocations = 0
        val layer = BulkFeatureLayer(
            source = GeoJsonBulkFeatureSource(
                """{"type":"Feature",
                | "geometry":{"type":"MultiPolygon","coordinates":[
                |   [[[0.0,0.0],[1.0,0.0],[1.0,1.0],[0.0,1.0],[0.0,0.0]]],
                |   [[[2.0,2.0],[3.0,2.0],[3.0,3.0],[2.0,3.0],[2.0,2.0]]],
                |   [[[5.0,5.0],[6.0,5.0],[6.0,6.0],[5.0,6.0],[5.0,5.0]]]
                | ]},
                | "properties":{"name":"Cluster"}}""".trimMargin()
            ),
            customLogicToApplyProperties = { lambdaInvocations++ },
        )
        layer.load()
        assertEquals(3, layer.renderables.size, "one Polygon per inner polygon")
        assertTrue(layer.renderables.all { it is Polygon })
        assertEquals(3, lambdaInvocations, "customLogic runs once per emitted renderable, not once per row")
    }

    @Test
    fun feature_collection_emits_renderables_in_document_order() = runTest {
        val layer = layerOver(
            """{"type":"FeatureCollection","features":[
                |{"type":"Feature","geometry":{"type":"Point","coordinates":[0.0,0.0]},"properties":{"name":"a"}},
                |{"type":"Feature","geometry":{"type":"LineString","coordinates":[[0.0,0.0],[1.0,1.0]]},"properties":{}},
                |{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[0.0,0.0],[1.0,0.0],[1.0,1.0],[0.0,0.0]]]},"properties":{}}
            |]}""".trimMargin()
        )
        assertEquals(3, layer.renderables.size)
        assertTrue(layer.renderables[0] is Label)
        assertTrue(layer.renderables[1] is Path)
        assertTrue(layer.renderables[2] is Polygon)
    }

    // ---------- Styling -----------------------------------------------------------------

    @Test
    fun shapeAttributes_template_color_survives_auto_style_when_no_simplestyle_props() = runTest {
        // Regression: Shapefile-style consumers pass a `shapeAttributes` template (e.g.
        // translucent blue) at construction. With autoApplyStyle = true (default) the
        // template must survive when the source has no `stroke` / `fill` properties —
        // because the layer's defaultLineColor / defaultFillColor are null by default, so
        // applyFeatureStyle has nothing to write.
        val template = ShapeAttributes().apply {
            interiorColor.set(red = 0.0f, green = 0.0f, blue = 1.0f, alpha = 0.5f)
            outlineColor.set(red = 1.0f, green = 1.0f, blue = 1.0f, alpha = 1.0f)
        }
        val layer = BulkFeatureLayer(
            source = GeoJsonBulkFeatureSource(
                """{"type":"Feature",
                | "geometry":{"type":"Polygon","coordinates":[[[0.0,0.0],[1.0,0.0],[1.0,1.0],[0.0,0.0]]]},
                | "properties":{"POP_EST":1000}}""".trimMargin()
            ),
            shapeAttributes = template,
        )
        layer.load()
        val polygon = layer.renderables[0] as Polygon
        assertEquals(1.0f, polygon.attributes.interiorColor.blue, "template translucent blue preserved")
        assertEquals(0.5f, polygon.attributes.interiorColor.alpha, "template alpha preserved")
    }

    @Test
    fun simplestyle_stroke_and_fill_apply_via_auto_style() = runTest {
        val layer = layerOver(
            """{"type":"Feature",
            | "geometry":{"type":"Polygon","coordinates":[[[0.0,0.0],[1.0,0.0],[1.0,1.0],[0.0,0.0]]]},
            | "properties":{"stroke":"#FF0000","fill":"#00FF00","stroke-width":3}}""".trimMargin()
        )
        val polygon = layer.renderables[0] as Polygon
        val attrs = polygon.attributes
        assertEquals(1.0f, attrs.outlineColor.red, "stroke=#FF0000 → red outline")
        assertEquals(0.0f, attrs.outlineColor.green)
        assertEquals(1.0f, attrs.interiorColor.green, "fill=#00FF00 → green fill")
        assertEquals(3.0f, attrs.outlineWidth)
    }

    @Test
    fun autoApplyStyle_false_skips_simplestyle_attribute_writes() = runTest {
        val layer = BulkFeatureLayer(
            source = GeoJsonBulkFeatureSource(
                """{"type":"Feature",
                | "geometry":{"type":"Polygon","coordinates":[[[0.0,0.0],[1.0,0.0],[1.0,1.0],[0.0,0.0]]]},
                | "properties":{"stroke-width":99}}""".trimMargin()
            ),
            autoApplyStyle = false,
        )
        layer.load()
        val polygon = layer.renderables[0] as Polygon
        // With autoApplyStyle = false, the stroke-width=99 in properties is NOT applied —
        // outlineWidth stays at Polygon's own default (which is much less than 99).
        assertNotEquals(99.0f, polygon.attributes.outlineWidth)
    }

    @Test
    fun custom_logic_runs_after_auto_style_and_can_override() = runTest {
        var sawAutoStyleApplied = false
        val layer = BulkFeatureLayer(
            source = GeoJsonBulkFeatureSource(
                """{"type":"Feature",
                | "geometry":{"type":"Polygon","coordinates":[[[0.0,0.0],[1.0,0.0],[1.0,1.0],[0.0,0.0]]]},
                | "properties":{"fill":"#00FF00"}}""".trimMargin()
            ),
            customLogicToApplyProperties = { props ->
                if (this is Polygon) {
                    // Inside the lambda, auto-style has already run for this renderable —
                    // green fill should be visible before we override it.
                    if (attributes.interiorColor.green == 1.0f) sawAutoStyleApplied = true
                    attributes.interiorColor.set(red = 0.0f, green = 0.0f, blue = 1.0f, alpha = 1.0f)
                }
            },
        )
        layer.load()
        val polygon = layer.renderables[0] as Polygon
        assertTrue(sawAutoStyleApplied, "customLogic observed auto-style's effect before overwriting")
        assertEquals(1.0f, polygon.attributes.interiorColor.blue, "customLogic's override wins")
    }

    // ---------- Layer-level params ------------------------------------------------------

    @Test
    fun label_visibility_threshold_propagates_to_emitted_labels() = runTest {
        val layer = BulkFeatureLayer(
            source = GeoJsonBulkFeatureSource(
                """{"type":"Feature","geometry":{"type":"Point","coordinates":[0.0,0.0]},"properties":{"name":"x"}}"""
            ),
            labelVisibilityThreshold = 123.0,
        )
        layer.load()
        val label = layer.renderables[0] as Label
        assertEquals(123.0, label.visibilityThreshold)
    }

    @Test
    fun default_altitude_mode_override_forces_all_renderables() = runTest {
        // A Point with a non-zero z would default to ABSOLUTE under the auto rule; the
        // override pins it to CLAMP_TO_GROUND.
        val layer = BulkFeatureLayer(
            source = GeoJsonBulkFeatureSource(
                """{"type":"Point","coordinates":[0.0,0.0,500.0]}"""
            ),
            defaultAltitudeMode = AltitudeMode.CLAMP_TO_GROUND,
        )
        layer.load()
        val placemark = layer.renderables[0] as Placemark
        assertEquals(AltitudeMode.CLAMP_TO_GROUND, placemark.altitudeMode)
    }

    @Test
    fun null_default_altitude_mode_uses_z_based_auto_detection() = runTest {
        // 2D geometry (no z) → CLAMP_TO_GROUND.
        val layer2d = layerOver("""{"type":"Point","coordinates":[0.0,0.0]}""")
        assertEquals(AltitudeMode.CLAMP_TO_GROUND, (layer2d.renderables[0] as Placemark).altitudeMode)

        // 3D geometry (non-zero z) → ABSOLUTE.
        val layer3d = layerOver("""{"type":"Point","coordinates":[0.0,0.0,500.0]}""")
        assertEquals(AltitudeMode.ABSOLUTE, (layer3d.renderables[0] as Placemark).altitudeMode)
    }

    @Test
    fun density_multiplier_applies_to_placemark_icon_scale() = runTest {
        // Without an `icon` property the Placemark uses DEFAULT_PLACEMARK_ICON_SIZE × density.
        val layer = BulkFeatureLayer(
            source = GeoJsonBulkFeatureSource(
                """{"type":"Feature","geometry":{"type":"Point","coordinates":[0.0,0.0]},
                | "properties":{"icon-scale":2.0}}""".trimMargin()
            ),
            density = 3.0f,
        )
        layer.load()
        val placemark = layer.renderables[0] as Placemark
        // icon-scale (2.0) × density (3.0f) = 6.0
        assertEquals(6.0, placemark.attributes.imageScale)
    }

    @Test
    fun load_replaces_previous_renderables_atomically() = runTest {
        val layer = layerOver("""{"type":"Point","coordinates":[0.0,0.0]}""")
        assertEquals(1, layer.renderables.size)
        // Re-loading from a different source should atomically replace the prior renderable.
        layer.source = GeoJsonBulkFeatureSource(
            """{"type":"FeatureCollection","features":[
                |{"type":"Feature","geometry":{"type":"Point","coordinates":[1.0,1.0]},"properties":{"name":"a"}},
                |{"type":"Feature","geometry":{"type":"Point","coordinates":[2.0,2.0]},"properties":{"name":"b"}}
            |]}""".trimMargin()
        )
        layer.load()
        assertEquals(2, layer.renderables.size, "old renderables dropped, new set installed")
    }

    @Test
    fun null_geometry_row_is_skipped_silently() = runTest {
        // A Feature with no geometry key parses to no row; nothing to render. Documenting
        // that this doesn't throw or produce a degenerate renderable.
        val layer = layerOver(
            """{"type":"Feature","properties":{"name":"orphan"}}"""
        )
        assertEquals(0, layer.renderables.size)
    }

    // ---------- helpers -----------------------------------------------------------------

    private suspend fun layerOver(geoJson: String): BulkFeatureLayer {
        val layer = BulkFeatureLayer(source = GeoJsonBulkFeatureSource(geoJson))
        layer.load()
        return layer
    }
}

private val BulkFeatureLayer.renderables: List<Renderable>
    get() = toList()

private fun runTest(body: suspend () -> Unit) {
    kotlinx.coroutines.test.runTest { body() }
}
