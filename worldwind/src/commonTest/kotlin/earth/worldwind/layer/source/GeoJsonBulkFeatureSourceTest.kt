package earth.worldwind.layer.source

import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parser-level parity for the new GeoJSON pipeline. Each test feeds a small GeoJSON document
 * into [GeoJsonBulkFeatureSource] and asserts the emitted [CachedFeatureRow]s have the right
 * geometry kind, geometry contents, and properties-JSON. These tests don't touch
 * [earth.worldwind.layer.BulkFeatureLayer] — they pin the source → row contract that the
 * layer pipeline depends on.
 */
class GeoJsonBulkFeatureSourceTest {

    @Test
    fun point_geometry_emits_one_row_with_cached_point() = runTest {
        val rows = GeoJsonBulkFeatureSource(
            """{"type":"Point","coordinates":[10.0,20.0]}"""
        ).fetchAll().toList()
        assertEquals(1, rows.size)
        val point = rows[0].geometry as CachedGeometry.Point
        assertEquals(10.0, point.x)
        assertEquals(20.0, point.y)
        assertNull(point.z, "z absent when only [x, y] are present")
    }

    @Test
    fun point_with_z_preserves_altitude() = runTest {
        val rows = GeoJsonBulkFeatureSource(
            """{"type":"Point","coordinates":[10.0,20.0,123.0]}"""
        ).fetchAll().toList()
        val point = rows[0].geometry as CachedGeometry.Point
        assertEquals(123.0, point.z)
    }

    @Test
    fun line_string_emits_one_row_with_points() = runTest {
        val rows = GeoJsonBulkFeatureSource(
            """{"type":"LineString","coordinates":[[0.0,0.0],[1.0,1.0],[2.0,2.0]]}"""
        ).fetchAll().toList()
        assertEquals(1, rows.size)
        val line = rows[0].geometry as CachedGeometry.LineString
        assertEquals(3, line.points.size)
        assertEquals(2.0, line.points[2].x)
    }

    @Test
    fun polygon_with_hole_keeps_outer_then_inner_rings() = runTest {
        val rows = GeoJsonBulkFeatureSource(
            """{"type":"Polygon","coordinates":[
                |[[0.0,0.0],[10.0,0.0],[10.0,10.0],[0.0,10.0],[0.0,0.0]],
                |[[2.0,2.0],[4.0,2.0],[4.0,4.0],[2.0,4.0],[2.0,2.0]]
            |]}""".trimMargin()
        ).fetchAll().toList()
        val polygon = rows[0].geometry as CachedGeometry.Polygon
        assertEquals(2, polygon.rings.size, "outer + 1 hole")
        assertEquals(5, polygon.rings[0].points.size, "outer ring with closing vertex")
    }

    @Test
    fun multipolygon_emits_multipolygon_not_flattened() = runTest {
        val rows = GeoJsonBulkFeatureSource(
            """{"type":"MultiPolygon","coordinates":[
                |[[[0.0,0.0],[1.0,0.0],[1.0,1.0],[0.0,1.0],[0.0,0.0]]],
                |[[[2.0,2.0],[3.0,2.0],[3.0,3.0],[2.0,3.0],[2.0,2.0]]],
                |[[[5.0,5.0],[6.0,5.0],[6.0,6.0],[5.0,6.0],[5.0,5.0]]]
            |]}""".trimMargin()
        ).fetchAll().toList()
        assertEquals(1, rows.size, "one row per feature, even for MultiPolygon")
        val multi = rows[0].geometry as CachedGeometry.MultiPolygon
        assertEquals(3, multi.polygons.size, "all sub-polygons preserved")
    }

    @Test
    fun multipoint_and_multilinestring_preserve_all_sub_geometries() = runTest {
        val pointRows = GeoJsonBulkFeatureSource(
            """{"type":"MultiPoint","coordinates":[[1.0,2.0],[3.0,4.0]]}"""
        ).fetchAll().toList()
        val multiPoint = pointRows[0].geometry as CachedGeometry.MultiPoint
        assertEquals(2, multiPoint.points.size, "all sub-points preserved")

        val lineRows = GeoJsonBulkFeatureSource(
            """{"type":"MultiLineString","coordinates":[[[0.0,0.0],[1.0,1.0]],[[5.0,5.0],[6.0,6.0]]]}"""
        ).fetchAll().toList()
        val multiLine = lineRows[0].geometry as CachedGeometry.MultiLineString
        assertEquals(2, multiLine.lines.size, "all sub-lines preserved")
    }

    @Test
    fun geometry_collection_preserves_member_geometries() = runTest {
        val rows = GeoJsonBulkFeatureSource(
            """{"type":"GeometryCollection","geometries":[
                |{"type":"Point","coordinates":[1.0,2.0]},
                |{"type":"LineString","coordinates":[[0.0,0.0],[1.0,1.0]]}
            |]}""".trimMargin()
        ).fetchAll().toList()
        val collection = rows[0].geometry as CachedGeometry.GeometryCollection
        assertEquals(2, collection.geometries.size, "all member geometries preserved")
        assertTrue(collection.geometries[0] is CachedGeometry.Point)
        assertTrue(collection.geometries[1] is CachedGeometry.LineString)
    }

    @Test
    fun feature_with_properties_round_trips_properties_as_json() = runTest {
        val rows = GeoJsonBulkFeatureSource(
            """{"type":"Feature",
            | "geometry":{"type":"Point","coordinates":[0.0,0.0]},
            | "properties":{"name":"Kyiv","population":2962180,"isCapital":true}}""".trimMargin()
        ).fetchAll().toList()
        val props = rows[0].properties
        assertNotNull(props)
        assertTrue(""""name":"Kyiv"""" in props)
        assertTrue(""""population":2962180""" in props)
        assertTrue(""""isCapital":true""" in props)
    }

    @Test
    fun feature_collection_preserves_order_and_emits_one_row_per_feature() = runTest {
        val rows = GeoJsonBulkFeatureSource(
            """{"type":"FeatureCollection","features":[
                |{"type":"Feature","geometry":{"type":"Point","coordinates":[1.0,1.0]},"properties":{"id":1}},
                |{"type":"Feature","geometry":{"type":"LineString","coordinates":[[0.0,0.0],[2.0,2.0]]},"properties":{"id":2}},
                |{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[0.0,0.0],[1.0,0.0],[1.0,1.0],[0.0,0.0]]]},"properties":{"id":3}}
            |]}""".trimMargin()
        ).fetchAll().toList()
        assertEquals(3, rows.size)
        assertTrue(rows[0].geometry is CachedGeometry.Point)
        assertTrue(rows[1].geometry is CachedGeometry.LineString)
        assertTrue(rows[2].geometry is CachedGeometry.Polygon)
        assertTrue(""""id":1""" in rows[0].properties!!)
        assertTrue(""""id":3""" in rows[2].properties!!)
    }

    @Test
    fun bare_geometry_emits_a_property_less_row() = runTest {
        val rows = GeoJsonBulkFeatureSource(
            """{"type":"Point","coordinates":[7.0,8.0]}"""
        ).fetchAll().toList()
        assertEquals(1, rows.size)
        assertNull(rows[0].properties, "bare Geometry has no properties")
    }

    @Test
    fun malformed_json_yields_empty_flow() = runTest {
        val rows = GeoJsonBulkFeatureSource("not valid json").fetchAll().toList()
        assertTrue(rows.isEmpty())
    }

    @Test
    fun empty_multipolygon_yields_no_geometry() = runTest {
        val rows = GeoJsonBulkFeatureSource(
            """{"type":"MultiPolygon","coordinates":[]}"""
        ).fetchAll().toList()
        // An empty MultiPolygon has no geometry to represent — the row is dropped.
        assertTrue(rows.isEmpty())
    }
}

/** Tiny suspend test runner — uses kotlinx-coroutines-test's runTest under the hood
 *  via its public name to stay portable across JVM / JS / iOS test runners. */
private fun runTest(body: suspend () -> Unit) {
    kotlinx.coroutines.test.runTest { body() }
}
