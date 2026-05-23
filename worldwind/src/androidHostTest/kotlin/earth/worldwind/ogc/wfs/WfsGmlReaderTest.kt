package earth.worldwind.ogc.wfs

import earth.worldwind.ogc.wfs.WfsGmlReader.GmlGeometry
import kotlin.test.*

class WfsGmlReaderTest {
    private val delta = 1e-9

    @Test
    fun testParsesGml32Point() {
        val xml = """<wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs/2.0" xmlns:gml="http://www.opengis.net/gml/3.2">
              <wfs:member><Feature><geom>
                <gml:Point srsName="urn:ogc:def:crs:EPSG::4326">
                  <gml:pos>40.5 -73.9</gml:pos>
                </gml:Point>
              </geom></Feature></wfs:member>
            </wfs:FeatureCollection>"""
        val geoms = WfsGmlReader.parseGeometries(xml)
        assertEquals(1, geoms.size)
        val point = geoms[0] as GmlGeometry.PointGeom
        assertEquals(40.5, point.position.latitude.inDegrees, delta, "Lat-first URN form")
        assertEquals(-73.9, point.position.longitude.inDegrees, delta)
    }

    @Test
    fun testParsesGml32LineStringWithPosList() {
        val xml = """<wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs/2.0" xmlns:gml="http://www.opengis.net/gml/3.2">
              <wfs:member><Feature><geom>
                <gml:LineString srsName="urn:ogc:def:crs:EPSG::4326">
                  <gml:posList>10 20 11 21 12 22</gml:posList>
                </gml:LineString>
              </geom></Feature></wfs:member>
            </wfs:FeatureCollection>"""
        val geoms = WfsGmlReader.parseGeometries(xml)
        val line = geoms.single() as GmlGeometry.LineGeom
        assertEquals(3, line.positions.size)
        assertEquals(10.0, line.positions[0].latitude.inDegrees, delta)
        assertEquals(20.0, line.positions[0].longitude.inDegrees, delta)
        assertEquals(12.0, line.positions[2].latitude.inDegrees, delta)
    }

    @Test
    fun testParsesGml31LineStringWithCrs84LonLat() {
        // GML 3.1 namespace + CRS84 axis order (lon,lat)
        val xml = """<wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs" xmlns:gml="http://www.opengis.net/gml">
              <gml:featureMember><Feature><geom>
                <gml:LineString srsName="urn:ogc:def:crs:OGC::CRS84">
                  <gml:posList>-73.9 40.5 -73.0 41.0</gml:posList>
                </gml:LineString>
              </geom></Feature></gml:featureMember>
            </wfs:FeatureCollection>"""
        val geoms = WfsGmlReader.parseGeometries(xml)
        val p0 = (geoms.single() as GmlGeometry.LineGeom).positions.first()
        assertEquals(40.5, p0.latitude.inDegrees, delta, "CRS84 -> lon first, lat second")
        assertEquals(-73.9, p0.longitude.inDegrees, delta)
    }

    @Test
    fun testParsesGml32PolygonExteriorRing() {
        val xml = """<wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs/2.0" xmlns:gml="http://www.opengis.net/gml/3.2">
              <wfs:member><Feature><geom>
                <gml:Polygon srsName="urn:ogc:def:crs:EPSG::4326">
                  <gml:exterior><gml:LinearRing>
                    <gml:posList>0 0 0 10 10 10 10 0 0 0</gml:posList>
                  </gml:LinearRing></gml:exterior>
                  <gml:interior><gml:LinearRing>
                    <gml:posList>2 2 2 4 4 4 4 2 2 2</gml:posList>
                  </gml:LinearRing></gml:interior>
                </gml:Polygon>
              </geom></Feature></wfs:member>
            </wfs:FeatureCollection>"""
        val poly = WfsGmlReader.parseGeometries(xml).single() as GmlGeometry.PolygonGeom
        assertEquals(5, poly.exterior.size, "Five vertices on exterior")
        assertEquals(0.0, poly.exterior[0].latitude.inDegrees, delta)
    }

    @Test
    fun testParsesMultiSurfaceFlattensIntoTwoPolygons() {
        val xml = """<wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs/2.0" xmlns:gml="http://www.opengis.net/gml/3.2">
              <wfs:member><Feature><geom>
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
              </geom></Feature></wfs:member>
            </wfs:FeatureCollection>"""
        val geoms = WfsGmlReader.parseGeometries(xml)
        assertEquals(2, geoms.size)
        assertTrue(geoms.all { it is GmlGeometry.PolygonGeom })
    }

    @Test
    fun testEmptyFeatureCollection() {
        val xml = """<wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs/2.0" xmlns:gml="http://www.opengis.net/gml/3.2"/>"""
        assertEquals(0, WfsGmlReader.parseGeometries(xml).size)
    }
}
