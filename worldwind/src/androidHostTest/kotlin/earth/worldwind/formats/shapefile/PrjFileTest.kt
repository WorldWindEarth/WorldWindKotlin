package earth.worldwind.formats.shapefile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrjFileTest {

    @Test
    fun recognizesWgs84Geographic() {
        val wkt = """GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563]]]"""
        val prj = PrjFile(wkt)
        assertTrue(prj.isGeographicCoordinateSystem)
        assertFalse(prj.isProjectedCoordinateSystem)
        assertEquals(PrjFile.CoordinateSystem.GEOGRAPHIC, prj.coordinateSystem)
    }

    @Test
    fun recognizesProjectedUtm() {
        val wkt = """PROJCS["WGS 84 / UTM zone 33N",GEOGCS["WGS 84",...]]"""
        val prj = PrjFile(wkt)
        assertTrue(prj.isProjectedCoordinateSystem)
        assertFalse(prj.isGeographicCoordinateSystem)
    }

    @Test
    fun emptyTextIsUnknown() {
        val prj = PrjFile("")
        assertTrue(prj.isUnknownCoordinateSystem)
        assertFalse(prj.isKnownCoordinateSystem)
    }

    @Test
    fun unknownKeywordIsUnknown() {
        val prj = PrjFile("LOCAL_CS[\"some local frame\"]")
        assertTrue(prj.isUnknownCoordinateSystem)
    }

    @Test
    fun parsesUtmZoneFromName() {
        val wkt = """PROJCS["WGS 84 / UTM zone 33N",GEOGCS["WGS 84",DATUM["WGS_1984"]]]"""
        val prj = PrjFile(wkt)
        assertEquals(33, prj.utmZone)
        assertEquals(earth.worldwind.geom.coords.Hemisphere.N, prj.utmHemisphere)
        assertTrue(prj.projection is PrjFile.Projection.Utm)
    }

    @Test
    fun parsesUtmZoneFromEpsgAuthority() {
        // EPSG:32733 is WGS 84 / UTM zone 33S
        val wkt = """PROJCS["foo",GEOGCS["bar"],AUTHORITY["EPSG","32733"]]"""
        val prj = PrjFile(wkt)
        assertEquals(33, prj.utmZone)
        assertEquals(earth.worldwind.geom.coords.Hemisphere.S, prj.utmHemisphere)
    }

    @Test
    fun utmInverseRoundtripsThroughGeographic() {
        // EPSG:32633 is WGS 84 / UTM zone 33N. Take a known point in Berlin (13.4° E, 52.5° N),
        // forward-project to (easting, northing), then ask PrjFile to invert back.
        val lat = earth.worldwind.geom.Angle.fromDegrees(52.5)
        val lon = earth.worldwind.geom.Angle.fromDegrees(13.4)
        val coord = earth.worldwind.geom.coords.UTMCoord.fromLatLon(lat, lon)
        val prj = PrjFile("""PROJCS["WGS 84 / UTM zone 33N",GEOGCS["WGS 84"],AUTHORITY["EPSG","32633"]]""")
        val (gotLon, gotLat) = prj.projection.toGeographic(coord.easting, coord.northing)
        assertEquals(13.4, gotLon, 1e-6)
        assertEquals(52.5, gotLat, 1e-6)
    }
}
