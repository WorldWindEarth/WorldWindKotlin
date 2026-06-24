package earth.worldwind.layer.source

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the feature-cache dedup contract: a GeoJSON feature's `id` member must survive into the
 * cached row's properties JSON under the key `"id"`, because `GpkgFeatureStore.extractFeatureUid`
 * keys the upsert (and the cross-tile dedup) off it. Without this the parser dropped `id`, every
 * row's uid was null, and whole unclipped WFS features were delete+reinserted across overlapping
 * tiles (the cache churn the Perfetto trace exposed).
 */
class GeoJsonFeatureIdTest {

    @Test
    fun feature_id_is_carried_into_properties_json() {
        val rows = parseGeoJsonAsFeatureRows(WFS_LIKE)
        assertTrue(rows.size == 1, "one feature parsed")
        val props = rows[0].properties ?: error("properties present")
        assertTrue("\"id\":\"ne_10m_roads.36193\"" in props, "feature id carried into properties: $props")
    }

    @Test
    fun missing_id_leaves_properties_unchanged() {
        val rows = parseGeoJsonAsFeatureRows(NO_ID)
        assertTrue(rows.size == 1)
        val props = rows[0].properties ?: error("properties present")
        assertTrue("\"id\"" !in props, "no id injected when feature has none: $props")
    }

    companion object {
        private val WFS_LIKE = """{"type":"FeatureCollection","features":[
            |{"type":"Feature","id":"ne_10m_roads.36193",
            | "geometry":{"type":"LineString","coordinates":[[1.0,1.0],[2.0,2.0]]},
            | "properties":{"type":"Major Highway","scalerank":3}}
        |]}""".trimMargin()

        private val NO_ID = """{"type":"FeatureCollection","features":[
            |{"type":"Feature",
            | "geometry":{"type":"LineString","coordinates":[[1.0,1.0],[2.0,2.0]]},
            | "properties":{"type":"Road"}}
        |]}""".trimMargin()
    }
}
