package earth.worldwind.globe

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.NEG90
import earth.worldwind.geom.Angle.Companion.POS90
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Ellipsoid
import earth.worldwind.globe.projection.Wgs84Projection
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for Globe. Tests ensure the proper calculation of the globe's radius using WGS84 specifications, and
 * simple parameter passing to the underlying projection.
 */
class GlobeTest {
    companion object {
        private const val OFFICIAL_WGS84_SEMI_MAJOR_AXIS = 6378137.0
        private const val OFFICIAL_WGS84_SEMI_MINOR_AXIS = 6356752.3142
        private const val OFFICIAL_WGS84_EC2 = 6.694379990141e-3

        ////////////////////
        // Helper Methods
        ////////////////////
        /**
         * Returns the radius of ellipsoid at the specified geographic latitude.
         *
         * @param geographicLat a geographic (geodetic) latitude.
         *
         * @return The radius in meters.
         */
        private fun computeRadiusOfEllipsoid(geographicLat: Angle): Double {
            // From Radius of the Earth - Radii Used in Geodesy
            // J. Clynch, Naval Post Graduate School, 2002
            val sinLatSquared = sin(geographicLat.radians).pow(2.0)
            val cosLatSquared = cos(geographicLat.radians).pow(2.0)
            val eSquared = OFFICIAL_WGS84_EC2
            var radius = OFFICIAL_WGS84_SEMI_MAJOR_AXIS * sqrt((1 - eSquared).pow(2.0) * sinLatSquared + cosLatSquared)
            radius /= sqrt(1 - eSquared * sinLatSquared)
            return radius
        }
    }

    /**
     * The globe used in the tests, created in setUp(), released in tearDown().
     */
    private lateinit var globe: Globe

    @BeforeTest
    fun setUp() {
        // Create the globe object used by the test
        globe = Globe(Ellipsoid.WGS84, Wgs84Projection())
    }

    @Test
    fun testConstructor() {
        assertNotNull(globe)
    }

    /**
     * Ensures the equatorial radius matches the semi-major axis used to define the globe.
     */
    @Test
    fun testGetEquatorialRadius() {
        val equatorialRadius = globe.equatorialRadius
        assertEquals(OFFICIAL_WGS84_SEMI_MAJOR_AXIS, equatorialRadius, 0.0, "equatorial radius")
    }

    /**
     * Ensures the polar radius matches the value derived from the globe definition.
     */
    @Test
    fun testGetPolarRadius() {
        val polarRadius = globe.polarRadius

        // WGS84 official value:  6356752.3142
        // Actual computed value: 6356752.314245179
        assertEquals(OFFICIAL_WGS84_SEMI_MINOR_AXIS, polarRadius, 1.0e-4, "polar radius")
    }

    /**
     * Ensures the correct calculation of the ellipsoidal radius at a geographic latitude.
     */
    @Test
    fun testGetRadiusAt() {
        // Test all whole number latitudes
        var lat = NEG90
        while (lat <= POS90) {
            val radiusExpected = computeRadiusOfEllipsoid(lat)
            val radiusActual = globe.getRadiusAt(lat, ZERO)
            assertEquals(radiusExpected, radiusActual, 1.0e-8, lat.toString())
            lat = lat.plusDegrees(1.0)
        }
    }

    /**
     * Ensures the eccentricity squared matches the value derived from the globe definition.
     */
    @Test
    fun testGetEccentricitySquared() {
        val eccentricitySquared = globe.ellipsoid.eccentricitySquared

        // Official value:        6.694379990141e-3
        // Actual computed value: 6.6943799901413165e-3
        assertEquals(OFFICIAL_WGS84_EC2, eccentricitySquared, 1.0e-15, "eccentricity squared")
    }
}