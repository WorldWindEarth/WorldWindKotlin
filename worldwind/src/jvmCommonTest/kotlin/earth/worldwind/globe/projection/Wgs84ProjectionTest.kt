package earth.worldwind.globe.projection

import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.toDegrees
import earth.worldwind.geom.Angle.Companion.toRadians
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.globe.Globe
import io.mockk.every
import io.mockk.mockk
import kotlin.math.*
import kotlin.test.*

class Wgs84ProjectionTest {
    private lateinit var globe: Globe

    @BeforeTest
    fun setUp() {
        // Create a globe with a WGS84 definition.
        globe = Globe(Ellipsoid.WGS84, Wgs84Projection())
    }

    @Test
    fun testConstructor() {
        val wgs84 = Wgs84Projection()
        assertNotNull(wgs84)
    }

    @Test
    fun testGetDisplayName() {
        val wgs84 = Wgs84Projection()
        val string = wgs84.displayName
        assertEquals("WGS84", string, "WGS84 name string")
    }

    /**
     * Tests the cartesian coordinates against values defined in the NIMA WGS specifications:
     * http://earth-info.nga.mil/GandG/publications/tr8350.2/Addendum%20NIMA%20TR8350.2.pdf
     */
    @Test
    fun testGeographicToCartesian() {
        val wgs84 = Wgs84Projection()
        val stations = stations
        for ((key, value) in stations) {
            val p = value[0] as Position
            val v = value[1] as Vec3
            val result = Vec3()
            wgs84.geographicToCartesian(globe, p.latitude, p.longitude, p.altitude, result)

            // Note: we must rotate the axis to match the WW coord system to the WGS coord system
            // WW: Y is polar axis, X and Z line on the equatorial plane with X +/-90 and Z +/-180
            // WGS: Z is polar axis
            assertEquals(v.x, result.x, 1e-3, key)
            assertEquals(v.y, result.y, 1e-3, key)
            assertEquals(v.z, result.z, 1e-3, key)
        }
    }

    /**
     * Simply tests that the reciprocal method will regenerate the original value.
     */
    @Test
    fun testGeographicToCartesian_Reciprocal() {
        val wgs84 = Wgs84Projection()
        val lat = 34.2.degrees
        val lon = (-119.2).degrees
        val alt = 1000.0
        val vec = Vec3()
        val pos = Position()
        wgs84.geographicToCartesian(globe, lat, lon, alt, vec)
        wgs84.cartesianToGeographic(globe, vec.x, vec.y, vec.z, pos)
        assertEquals(lat.degrees, pos.latitude.degrees, 1e-6, "lat")
        assertEquals(lon.degrees, pos.longitude.degrees, 1e-6, "lon")
        assertEquals(alt, pos.altitude, 1e-6, "alt")
    }

    /**
     * Simple test ensures the computed normal aligns to an expected result.
     */
    @Test
    fun testGeographicToCartesianNormal() {
        val wgs84 = Wgs84Projection()
        val lat = 34.2.degrees
        val lon = (-119.2).degrees
        val result = wgs84.geographicToCartesianNormal(globe, lat, lon, Vec3())
        val theta = toDegrees(asin(result.y))
        val lambda = toDegrees(atan2(result.x, result.z))
        assertEquals(theta, lat.degrees, 1e-6, "latitude: ")
        assertEquals(lambda, lon.degrees, 1e-6, "longitude: ")
    }

    /**
     * Ensures that transform matrix agrees with manual cartesian transform of a position.
     */
    @Test
    fun testGeographicToCartesianTransform() {
        // The expectation of geographicToCartesianTransform is that the
        // coordinate system is perpendicular to the geodetic tangent plane
        // at the position. So the Z axes points along the normal to the
        // ellipsoid -- the vector Rn in diagrams.
        val wgs84 = Wgs84Projection()
        val lat = 34.2.degrees
        val lon = (-119.2).degrees
        val alt = 0.0
        // Compute the radius of the prime vertical, Rn, and the vertical
        // delta between the equator and the intersection of Rn and Y axis.
        val a = globe.equatorialRadius
        val e2 = globe.eccentricitySquared
        val sinLat = sin(lat.radians)
        val rn = a / sqrt(1 - e2 * sinLat * sinLat)
        val delta = e2 * rn * sinLat
        // First rotate longitude about the Y axis; second, translate the
        // origin to the intersection of Rn and the Y axis; third, rotate
        // latitude about the X axis (opposite rotation used for latitudes);
        // and finally, translate along Rn (the Z axis) to the surface of
        // the ellipsoid.
        val expected = Matrix4()
        expected.multiplyByRotation(0.0, 1.0, 0.0, lon)
        expected.multiplyByTranslation(0.0, -delta, 0.0)
        expected.multiplyByRotation(1.0, 0.0, 0.0, -lat)
        expected.multiplyByTranslation(0.0, 0.0, rn)
        val result = wgs84.geographicToCartesianTransform(globe, lat, lon, alt, Matrix4())
        //assertContentEquals(expected.m, result.m)
        for (i in result.m.indices) {
            assertEquals(expected.m[i], result.m[i], 1e-6)
        }
    }

    @Ignore // Not implemented yet.
    @Test
    fun testGeographicToCartesianGrid() {
        val wgs84 = Wgs84Projection()
        val stride = 5
        val numLat = 17
        val numLon = 33
        val count = numLat * numLon * stride
        val elevations = FloatArray(count)
        val verticalExaggeration = 1.0f
        val sector = Sector()
        val referencePoint = Vec3()
        val result = FloatArray(count)
        wgs84.geographicToCartesianGrid(globe, sector, numLat, numLon, elevations, verticalExaggeration, referencePoint, result, stride, 0)
        fail("The test case is a stub.")
    }

    /**
     * Tests the geodetic coordinates against values defined in the NIMA WGS specifications:
     * http://earth-info.nga.mil/GandG/publications/tr8350.2/Addendum%20NIMA%20TR8350.2.pdf
     */
    @Test
    fun testCartesianToGeographic() {
        val wgs84 = Wgs84Projection()
        val stations = stations
        for ((key, value) in stations) {
            val p = value[0] as Position
            val v = value[1] as Vec3
            val result = Position()

            // Note: we must rotate the axis to match the WW coord system to the WGS ECEF coord system
            // WW: Y is polar axis, X and Z line on the equatorial plane with X coincident with +/-90 and Z +/-180
            wgs84.cartesianToGeographic(globe, v.x, v.y, v.z, result)
            assertEquals(p.latitude.normalizeLatitude().degrees, result.latitude.degrees, 1e-6, key)
            assertEquals(p.longitude.normalizeLongitude().degrees, result.longitude.degrees, 1e-6, key)
            assertEquals(p.altitude, result.altitude, 1e-3, key)
        }
    }

    /**
     * Simply tests that the reciprocal method will regenerate the original value.
     */
    @Test
    fun testCartesianToGeographic_Reciprocal() {
        val wgs84 = Wgs84Projection()
        val x = -4610466.9131683465 // KOXR airport
        val y = 3565379.0227454384
        val z = -2576702.8642047923
        val vec = Vec3()
        val pos = Position()
        wgs84.cartesianToGeographic(globe, x, y, z, pos)
        wgs84.geographicToCartesian(globe, pos.latitude, pos.longitude, pos.altitude, vec)
        assertEquals(x, vec.x, 1e-6, "x")
        assertEquals(y, vec.y, 1e-6, "y")
        assertEquals(z, vec.z, 1e-6, "z")
    }

    /**
     * This test case was provided by the COE EMP team. Visually, it is obvious the Line in this examples has a
     * direction and origin that will not intersect the ellipsoid.
     */
    @Test
    fun testEmpBackwardInstance() {
        val wgs84 = Wgs84Projection()
        val ray = Line(Vec3(990474.8037403631, 3007310.9566306924, 5583923.602748461), Vec3(-0.1741204769506282, 0.9711294099374702, -0.16306357245254538))
        val intersection = wgs84.intersect(globe, ray, Vec3())
        assertFalse(intersection, "EMP backward intersection")
    }

    /**
     * An instance which is easily visualized for understanding the backwards intersection instance.
     */
    @Test
    fun testSimpleBackwardsIntersection() {
        val wgs84 = Wgs84Projection()
        val mockedGlobe = mockk<Globe>(relaxed = true)
        every { mockedGlobe.equatorialRadius } returns 1.0
        every { mockedGlobe.polarRadius } returns 1.0
        val ray = Line(Vec3(0.8, 0.8, 0.0), Vec3(0.0, 1.0, 0.0))
        val intersection = wgs84.intersect(mockedGlobe, ray, Vec3())
        assertFalse(intersection, "simple backwards intersection")
    }

    /**
     * An instance which is easily visualized for understanding the forwards intersection instance.
     */
    @Test
    fun testSimpleIntersection() {
        val wgs84 = Wgs84Projection()
        val mockedGlobe = mockk<Globe>(relaxed = true)
        every { mockedGlobe.equatorialRadius } returns 1.0
        every { mockedGlobe.polarRadius } returns 1.0
        val ray = Line(Vec3(0.8, 0.8, 0.0), Vec3(0.0, -1.0, 0.0))
        val intersection = wgs84.intersect(mockedGlobe, ray, Vec3())
        assertTrue(intersection, "simple intersection")
    }

    /**
     * An instance which demonstrates two intersections, but the closest, or first surface intersection position is
     * desired.
     */
    @Test
    fun testSimpleTwoIntersection() {
        val wgs84 = Wgs84Projection()
        val mockedGlobe = mockk<Globe>(relaxed = true)
        every { mockedGlobe.equatorialRadius } returns 1.0
        every { mockedGlobe.polarRadius } returns 1.0
        val ray = Line(Vec3(-1.0, 2.0, 0.0), Vec3(1.0, -1.0, 0.0).normalize())
        val result = Vec3()
        val errorThreshold = 1e-9
        val intersection = wgs84.intersect(mockedGlobe, ray, result)
        assertTrue(intersection, "simple intersection")
        assertEquals(0.0, result.x, errorThreshold, "nearest calculated intersection x")
        assertEquals(1.0, result.y, errorThreshold, "nearest calculated intersection y")
    }

    /**
     * An instance which demonstrates two intersections with a ray originating within the ellipsoid.
     */
    @Test
    fun testSimpleTwoIntersectionInternal() {
        val wgs84 = Wgs84Projection()
        val mockedGlobe = mockk<Globe>(relaxed = true)
        every { mockedGlobe.equatorialRadius } returns 1.0
        every { mockedGlobe.polarRadius } returns 1.0
        val ray = Line(Vec3(-0.8, 0.0, 0.0), Vec3(1.0, 0.0, 0.0).normalize())
        val result = Vec3()
        val errorThreshold = 1e-9
        val intersection = wgs84.intersect(mockedGlobe, ray, result)
        assertTrue(intersection, "simple internal intersection")
        assertEquals(1.0, result.x, errorThreshold, "forward calculated intersection x")
        assertEquals(0.0, result.y, errorThreshold, "forward calculated intersection y")
    }

    //////////////////////////////////////////
    //           Helper Methods
    //////////////////////////////////////////
    fun radiusOfPrimeVeritcal(geographicLat: Double): Double {
        val a = globe.equatorialRadius
        val e2 = globe.eccentricitySquared
        val sinSquared = sin(toRadians(geographicLat)).pow(2.0)
        return a / sqrt(1 - e2 * sinSquared)
    }

    companion object {
        /**
         * Creates a Vec3 in the WorldWind coordinate system from WGS84 ECEF coordinates.
         *
         * @param xEcef X coordinate
         * @param yEcef Y coordinate
         * @param zEcef Z coordinate
         *
         * @return a Vec3 compatible with the WorldWind graphics coordinate system.
         */
        private fun fromEcef(xEcef: Double, yEcef: Double, zEcef: Double) = Vec3(yEcef, zEcef, xEcef)

        /**
         * Returns a Map of station names with Position and Vec3 pairs.
         * <pre>
         * Geodetic Coordinates 2001 epoch:
         * Air Force Station    Station  Lat             Lon             Ellipsoid Height
         * -------------------------------------------------------------------------------
         * Colorado Springs      85128   38.80305456     255.47540844    1911.755
         * Ascension             85129   -7.95132970     345.58786950    106.558
         * Diego Garcia          85130   -7.26984347     72.37092177     -64.063
         * Kwajalein             85131   8.72250074      167.73052625    39.927
         * Hawaii                85132   21.56149086     201.76066922    426.077
         * Cape Canaveral        85143   28.48373800     279.42769549    -24.005
         *
         * Cartesian Coordinates 2001 epoch (ECEF coordinates positive Z points up)
         * Air Force Station    Station  X(km)           Y(km)           Z(km)
         * -------------------------------------------------------------------------------
         * Colorado Springs      85128   -1248.597295    -4819.433239    3976.500175
         * Ascension             85129   6118.524122     -1572.350853    -876.463990
         * Diego Garcia          85130   1916.197142     6029.999007     -801.737366
         * Kwajalein             85131   -6160.884370    1339.851965     960.843071
         * Hawaii                85132   -5511.980484    -2200.247093    2329.480952
         * Cape Canaveral        85143   918.988120      -5534.552966    3023.721377
        </pre> *
         * http://earth-info.nga.mil/GandG/publications/tr8350.2/Addendum%20NIMA%20TR8350.2.pdf
         *
         * @return a Map collection containing station names with reference positions and ECEF coordinates.
         */
        val stations = mapOf(
            "Colorado Springs" to arrayOf(
                fromDegrees(38.80305456, 255.47540844, 1911.755),
                fromEcef(-1248.597295e3, -4819.433239e3, 3976.500175e3)
            ),
            "Ascension" to arrayOf(
                fromDegrees(-7.95132970, 345.58786950, 106.558),
                fromEcef(6118.524122e3, -1572.350853e3, -876.463990e3)
            ),
            "Diego Garcia" to arrayOf(
                fromDegrees(-7.26984347, 72.37092177, -64.063),
                fromEcef(1916.197142e3, 6029.999007e3, -801.737366e3)
            ),
            "Kwajalein" to arrayOf(
                fromDegrees(8.72250074, 167.73052625, 39.927),
                fromEcef(-6160.884370e3, 1339.851965e3, 960.843071e3)
            ),
            "Hawaii" to arrayOf(
                fromDegrees(21.56149086, 201.76066922, 426.077),
                fromEcef(-5511.980484e3, -2200.247093e3, 2329.480952e3)
            ),
            "Cape Canaveral" to arrayOf(
                fromDegrees(28.48373800, 279.42769549, -24.005),
                fromEcef(918.988120e3, -5534.552966e3, 3023.721377e3)
            )
        )
    }
}