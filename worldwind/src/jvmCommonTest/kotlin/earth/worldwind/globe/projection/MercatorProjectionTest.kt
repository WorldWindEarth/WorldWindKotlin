package earth.worldwind.globe.projection

import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Ellipsoid
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MercatorProjectionTest {
    private lateinit var projection: GeographicProjection

    @BeforeTest
    fun setUp() {
        projection = MercatorProjection()
    }

    /**
     * Simply tests that the reciprocal method will regenerate the original value.
     */
    @Test
    fun testGeographicToCartesian_Reciprocal() {
        val lat = 34.2.degrees
        val lon = (-119.2).degrees
        val alt = 1000.0
        val vec = projection.geographicToCartesian(Ellipsoid.WGS84, lat, lon, alt, 0.0,  Vec3())
        val pos = projection.cartesianToGeographic(Ellipsoid.WGS84, vec.x, vec.y, vec.z, 0.0, Position())
        assertEquals(lat.inDegrees, pos.latitude.inDegrees, 1e-6, "lat")
        assertEquals(lon.inDegrees, pos.longitude.inDegrees, 1e-6, "lon")
        assertEquals(alt, pos.altitude, 1e-6, "alt")
    }

    /**
     * Simply tests that the reciprocal method will regenerate the original value.
     */
    @Test
    fun testCartesianToGeographic_Reciprocal() {
        val x = -13269283.302558212
        val y = 4031672.2655886197
        val z = 1000.0
        val pos = projection.cartesianToGeographic(Ellipsoid.WGS84, x, y, z, 0.0, Position())
        val vec = projection.geographicToCartesian(Ellipsoid.WGS84, pos.latitude, pos.longitude, pos.altitude, 0.0, Vec3())
        assertEquals(x, vec.x, 1e-1, "x")
        assertEquals(y, vec.y, 1e-1, "y")
        assertEquals(z, vec.z, 1e-1, "z")
    }
}