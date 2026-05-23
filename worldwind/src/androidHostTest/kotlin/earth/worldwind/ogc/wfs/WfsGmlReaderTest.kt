package earth.worldwind.ogc.wfs

import earth.worldwind.ogc.wfs.WfsGmlReader.GmlGeometry
import kotlin.test.*

class WfsGmlReaderTest {
    private val delta = 1e-9

    @Test
    fun testParsesGml32Point() {
        val xml = """<wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs/2.0" xmlns:gml="http://www.opengis.net/gml/3.2">
              <wfs:member><topp:Cities xmlns:topp="x">
                <topp:the_geom>
                  <gml:Point srsName="urn:ogc:def:crs:EPSG::4326">
                    <gml:pos>40.5 -73.9</gml:pos>
                  </gml:Point>
                </topp:the_geom>
              </topp:Cities></wfs:member>
            </wfs:FeatureCollection>"""
        val records = WfsGmlReader.parseFeatureRecords(xml)
        assertEquals(1, records.size)
        val point = records[0].geometry as GmlGeometry.PointGeom
        assertEquals(40.5, point.position.latitude.inDegrees, delta, "Lat-first URN form")
        assertEquals(-73.9, point.position.longitude.inDegrees, delta)
    }

    @Test
    fun testParsesGml32LineStringWithPosList() {
        val xml = """<wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs/2.0" xmlns:gml="http://www.opengis.net/gml/3.2">
              <wfs:member><topp:Roads xmlns:topp="x"><topp:the_geom>
                <gml:LineString srsName="urn:ogc:def:crs:EPSG::4326">
                  <gml:posList>10 20 11 21 12 22</gml:posList>
                </gml:LineString>
              </topp:the_geom></topp:Roads></wfs:member>
            </wfs:FeatureCollection>"""
        val record = WfsGmlReader.parseFeatureRecords(xml).single()
        val line = record.geometry as GmlGeometry.LineGeom
        assertEquals(3, line.positions.size)
        assertEquals(10.0, line.positions[0].latitude.inDegrees, delta)
        assertEquals(20.0, line.positions[0].longitude.inDegrees, delta)
        assertEquals(12.0, line.positions[2].latitude.inDegrees, delta)
    }

    @Test
    fun testParsesGml31LineStringWithCrs84LonLat() {
        val xml = """<wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs" xmlns:gml="http://www.opengis.net/gml">
              <gml:featureMember><topp:Roads xmlns:topp="x"><topp:the_geom>
                <gml:LineString srsName="urn:ogc:def:crs:OGC::CRS84">
                  <gml:posList>-73.9 40.5 -73.0 41.0</gml:posList>
                </gml:LineString>
              </topp:the_geom></topp:Roads></gml:featureMember>
            </wfs:FeatureCollection>"""
        val record = WfsGmlReader.parseFeatureRecords(xml).single()
        val p0 = (record.geometry as GmlGeometry.LineGeom).positions.first()
        assertEquals(40.5, p0.latitude.inDegrees, delta, "CRS84 -> lon first, lat second")
        assertEquals(-73.9, p0.longitude.inDegrees, delta)
    }

    @Test
    fun testParsesGml32PolygonWithInteriorRing() {
        val xml = """<wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs/2.0" xmlns:gml="http://www.opengis.net/gml/3.2">
              <wfs:member><topp:Land xmlns:topp="x"><topp:the_geom>
                <gml:Polygon srsName="urn:ogc:def:crs:EPSG::4326">
                  <gml:exterior><gml:LinearRing>
                    <gml:posList>0 0 0 10 10 10 10 0 0 0</gml:posList>
                  </gml:LinearRing></gml:exterior>
                  <gml:interior><gml:LinearRing>
                    <gml:posList>2 2 2 4 4 4 4 2 2 2</gml:posList>
                  </gml:LinearRing></gml:interior>
                </gml:Polygon>
              </topp:the_geom></topp:Land></wfs:member>
            </wfs:FeatureCollection>"""
        val poly = WfsGmlReader.parseFeatureRecords(xml).single().geometry as GmlGeometry.PolygonGeom
        assertEquals(5, poly.exterior.size, "Exterior has five vertices")
        assertEquals(1, poly.interiors.size, "One interior ring captured")
        assertEquals(5, poly.interiors[0].size, "Interior ring has five vertices")
    }

    @Test
    fun testParsesMultiSurfaceFlattensIntoTwoPolygons() {
        val xml = """<wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs/2.0" xmlns:gml="http://www.opengis.net/gml/3.2">
              <wfs:member><topp:Land xmlns:topp="x"><topp:the_geom>
                <gml:MultiSurface srsName="urn:ogc:def:crs:EPSG::4326">
                  <gml:surfaceMember>
                    <gml:Polygon><gml:exterior><gml:LinearRing>
                      <gml:posList>0 0 0 1 1 1 1 0 0 0</gml:posList>
                    </gml:LinearRing></gml:exterior></gml:Polygon>
                  </gml:surfaceMember>
                  <gml:surfaceMember>
                    <gml:Polygon><gml:exterior><gml:LinearRing>
                      <gml:posList>2 2 2 3 3 3 3 2 2 2</gml:posList>
                    </gml:LinearRing></gml:exterior></gml:Polygon>
                  </gml:surfaceMember>
                </gml:MultiSurface>
              </topp:the_geom></topp:Land></wfs:member>
            </wfs:FeatureCollection>"""
        val records = WfsGmlReader.parseFeatureRecords(xml)
        assertEquals(2, records.size, "MultiSurface fans out to two records")
        assertTrue(records.all { it.geometry is GmlGeometry.PolygonGeom })
    }

    @Test
    fun testCapturesFeatureProperties() {
        val xml = """<wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs/2.0" xmlns:gml="http://www.opengis.net/gml/3.2">
              <wfs:member><topp:Cities xmlns:topp="x">
                <topp:NAME>Springfield</topp:NAME>
                <topp:POP>21714</topp:POP>
                <topp:the_geom>
                  <gml:Point srsName="urn:ogc:def:crs:EPSG::4326">
                    <gml:pos>40.0 -89.0</gml:pos>
                  </gml:Point>
                </topp:the_geom>
              </topp:Cities></wfs:member>
            </wfs:FeatureCollection>"""
        val record = WfsGmlReader.parseFeatureRecords(xml).single()
        assertEquals("Springfield", record.properties["NAME"])
        assertEquals("21714", record.properties["POP"])
        // the_geom wrapped the geometry — its text is empty, so it shouldn't appear as a property.
        assertFalse(record.properties.containsKey("the_geom"))
    }

    @Test
    fun testMultiGeometryRecordsSharePropertiesOfParentFeature() {
        val xml = """<wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs/2.0" xmlns:gml="http://www.opengis.net/gml/3.2">
              <wfs:member><topp:Land xmlns:topp="x">
                <topp:NAME>Archipelago</topp:NAME>
                <topp:the_geom>
                  <gml:MultiSurface srsName="urn:ogc:def:crs:EPSG::4326">
                    <gml:surfaceMember><gml:Polygon><gml:exterior><gml:LinearRing>
                      <gml:posList>0 0 0 1 1 1 1 0 0 0</gml:posList>
                    </gml:LinearRing></gml:exterior></gml:Polygon></gml:surfaceMember>
                    <gml:surfaceMember><gml:Polygon><gml:exterior><gml:LinearRing>
                      <gml:posList>2 2 2 3 3 3 3 2 2 2</gml:posList>
                    </gml:LinearRing></gml:exterior></gml:Polygon></gml:surfaceMember>
                  </gml:MultiSurface>
                </topp:the_geom>
              </topp:Land></wfs:member>
            </wfs:FeatureCollection>"""
        val records = WfsGmlReader.parseFeatureRecords(xml)
        assertEquals(2, records.size)
        assertTrue(records.all { it.properties["NAME"] == "Archipelago" }, "All inner geometries inherit parent feature's properties")
    }

    @Test
    fun testEmptyFeatureCollection() {
        val xml = """<wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs/2.0" xmlns:gml="http://www.opengis.net/gml/3.2"/>"""
        assertEquals(0, WfsGmlReader.parseFeatureRecords(xml).size)
    }
}
