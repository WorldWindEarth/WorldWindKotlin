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
}
