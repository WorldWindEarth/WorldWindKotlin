package earth.worldwind.globe

import earth.worldwind.geom.Ellipsoid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests WorldWind's WGS84 definition to ensure it conforms to these ellipsoid parameters obtained from the official
 * WGS84 specifications: http://earth-info.nga.mil/GandG/publications/NGA_STND_0036_1_0_0_WGS84/NGA.STND.0036_1.0.0_WGS84.pdf
 * <br></br>
 * Semi Major Axis: 6378137
 * <br></br>
 * Flattening: 298.257223563
 */
class Wgs84Test {
    companion object {
        private const val OFFICIAL_WGS84_SEMI_MAJOR_AXIS = 6378137.0
        private const val OFFICIAL_WGS84_SEMI_MINOR_AXIS = 6356752.3142
        private const val OFFICIAL_WGS84_INVERSE_FLATTENING = 298.257223563
        private const val OFFICIAL_WGS84_EC2 = 6.694379990141e-3
    }

    @Test
    fun testWgs84SemiMajorAxis() {
        assertEquals(OFFICIAL_WGS84_SEMI_MAJOR_AXIS, Ellipsoid.WGS84.semiMajorAxis, 0.0, "WGS84 semi-major axis")
    }

    @Test
    fun testWgs84InverseFlattening() {
        assertEquals(OFFICIAL_WGS84_INVERSE_FLATTENING, Ellipsoid.WGS84.inverseFlattening, 0.0, "WGS84 inverse flattening")
    }

    @Test
    fun testWgs84Ellipsoid() {
        val ellipsoid = Ellipsoid.WGS84
        assertNotNull(ellipsoid, "WGS84 ellipsoid not null")
        assertEquals(OFFICIAL_WGS84_SEMI_MAJOR_AXIS, ellipsoid.semiMajorAxis, 0.0, "WGS84 ellipsoid semi-major axis")
        assertEquals(OFFICIAL_WGS84_INVERSE_FLATTENING, ellipsoid.inverseFlattening, 0.0, "WGS84 ellipsoid inverse flattening")

        // WGS84 official value:  6356752.3142
        // Actual computed value: 6356752.314245179
        assertEquals(OFFICIAL_WGS84_SEMI_MINOR_AXIS, ellipsoid.semiMinorAxis, 1.0e-4, "WGS84 ellipsoid semi-minor axis")

        // Official value:        6.694379990141e-3
        // Actual computed value: 6.6943799901413165e-3
        assertEquals(OFFICIAL_WGS84_EC2, ellipsoid.eccentricitySquared, 1.0e-15, "WGS84 ellipsoid eccentricity squared ")
    }
}