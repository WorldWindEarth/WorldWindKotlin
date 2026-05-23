package earth.worldwind.ogc.gpkg

import earth.worldwind.geom.Position
import earth.worldwind.ogc.wfs.WfsGmlReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import mil.nga.sf.LineString
import mil.nga.sf.Point
import mil.nga.sf.Polygon
import kotlin.test.*

class FeatureConvertersTest {
    private val delta = 1e-9

    // ----- GeoJSON → mil.nga.sf -----

    @Test
    fun testGeoJsonPoint() {
        val geometry = Json.parseToJsonElement("""{"type":"Point","coordinates":[10.5,40.5]}""") as JsonObject
        val sf = geoJsonGeometryToSf(geometry) as Point
        assertEquals(10.5, sf.x, delta, "x = longitude")
        assertEquals(40.5, sf.y, delta, "y = latitude")
    }

    @Test
    fun testGeoJsonLineString() {
        val geometry = Json.parseToJsonElement(
            """{"type":"LineString","coordinates":[[0,0],[1,1],[2,2]]}"""
        ) as JsonObject
        val sf = geoJsonGeometryToSf(geometry) as LineString
        assertEquals(3, sf.numPoints())
        assertEquals(2.0, sf.points[2].x, delta)
    }

    @Test
    fun testGeoJsonPolygonWithHole() {
        val geometry = Json.parseToJsonElement(
            """{"type":"Polygon","coordinates":[
                  [[0,0],[10,0],[10,10],[0,10],[0,0]],
                  [[2,2],[4,2],[4,4],[2,4],[2,2]]
                ]}"""
        ) as JsonObject
        val sf = geoJsonGeometryToSf(geometry) as Polygon
        assertEquals(2, sf.numRings(), "Exterior + one interior")
        assertEquals(5, sf.exteriorRing.numPoints())
    }

    @Test
    fun testGeoJsonMultiPolygonExpands() {
        val geometry = Json.parseToJsonElement(
            """{"type":"MultiPolygon","coordinates":[
                  [[[0,0],[1,0],[1,1],[0,1],[0,0]]],
                  [[[5,5],[6,5],[6,6],[5,6],[5,5]]]
                ]}"""
        ) as JsonObject
        val sf = geoJsonGeometryToSf(geometry) as mil.nga.sf.MultiPolygon
        assertEquals(2, sf.numPolygons())
    }

    @Test
    fun testGeoJsonMalformedReturnsNull() {
        // No coordinates
        val geometry = Json.parseToJsonElement("""{"type":"Point"}""") as JsonObject
        assertNull(geoJsonGeometryToSf(geometry))
    }

    // ----- GML → mil.nga.sf -----

    @Test
    fun testGmlPointFromFeatureRecord() {
        val rec = WfsGmlReader.FeatureRecord(
            WfsGmlReader.GmlGeometry.PointGeom(Position.fromDegrees(40.5, 10.5, 0.0)),
            mapOf("name" to "Test", "POP" to "42"),
        )
        val (geom, props) = gmlFeatureRecordsToSf(listOf(rec)).single()
        val pt = geom as Point
        assertEquals(10.5, pt.x, delta, "GML position swap: longitude into x")
        assertEquals(40.5, pt.y, delta, "GML position swap: latitude into y")
        // Properties travel as a JSON object string so both decode paths share the
        // same column schema and the replay path can re-parse them uniformly.
        assertNotNull(props)
        assertTrue(props.contains("\"name\":\"Test\""))
        assertTrue(props.contains("\"POP\":\"42\""))
    }

    @Test
    fun testGmlPolygonRoundTripsInteriorRings() {
        val exterior = listOf(
            Position.fromDegrees(0.0, 0.0, 0.0),
            Position.fromDegrees(0.0, 10.0, 0.0),
            Position.fromDegrees(10.0, 10.0, 0.0),
            Position.fromDegrees(10.0, 0.0, 0.0),
            Position.fromDegrees(0.0, 0.0, 0.0),
        )
        val hole = listOf(
            Position.fromDegrees(2.0, 2.0, 0.0),
            Position.fromDegrees(2.0, 4.0, 0.0),
            Position.fromDegrees(4.0, 4.0, 0.0),
            Position.fromDegrees(4.0, 2.0, 0.0),
            Position.fromDegrees(2.0, 2.0, 0.0),
        )
        val rec = WfsGmlReader.FeatureRecord(
            WfsGmlReader.GmlGeometry.PolygonGeom(exterior, listOf(hole)),
            emptyMap(),
        )
        val (geom, props) = gmlFeatureRecordsToSf(listOf(rec)).single()
        val poly = geom as Polygon
        assertEquals(2, poly.numRings())
        assertNull(props, "Empty properties map serializes to null, not an empty JSON object")
    }

    @Test
    fun testGmlDegenerateGeometryDropped() {
        // Two-point ring isn't a valid LinearRing — convertor drops the record.
        val rec = WfsGmlReader.FeatureRecord(
            WfsGmlReader.GmlGeometry.PolygonGeom(
                exterior = listOf(Position.fromDegrees(0.0, 0.0, 0.0), Position.fromDegrees(1.0, 1.0, 0.0)),
            ),
            emptyMap(),
        )
        assertTrue(gmlFeatureRecordsToSf(listOf(rec)).isEmpty())
    }
}
