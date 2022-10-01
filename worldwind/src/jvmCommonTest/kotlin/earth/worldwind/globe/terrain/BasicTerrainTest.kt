package earth.worldwind.globe.terrain

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Ellipsoid
import earth.worldwind.geom.Location.Companion.fromDegrees
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.globe.projection.Wgs84Projection
import earth.worldwind.util.LevelSet
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BasicTerrainTest {
    companion object {
        private const val OFFICIAL_WGS84_SEMI_MAJOR_AXIS = 6378137.0
        private const val OFFICIAL_WGS84_EC2 = 6.69437999014E-3
        private const val TOLERANCE = 0.0015 // Cartesian XYZ components must be within 1.5 millimeters

        private fun officialWgs84Ecef(latitude: Angle, longitude: Angle, altitude: Double): Vec3 {
            val cosLat = cos(latitude.radians)
            val sinLat = sin(latitude.radians)
            val cosLon = cos(longitude.radians)
            val sinLon = sin(longitude.radians)
            val normal = OFFICIAL_WGS84_SEMI_MAJOR_AXIS / sqrt(1.0 - OFFICIAL_WGS84_EC2 * sinLat * sinLat)
            val x = (normal + altitude) * cosLat * cosLon
            val y = (normal + altitude) * cosLat * sinLon
            val z = (normal * (1.0 - OFFICIAL_WGS84_EC2) + altitude) * sinLat
            return Vec3(x, y, z)
        }

        private fun officialWgs84Ecef(latitudeDegrees: Double, longitudeDegrees: Double, altitude: Double) =
            officialWgs84Ecef(fromDegrees(latitudeDegrees), fromDegrees(longitudeDegrees), altitude)

        private fun worldWindEcef(officialEcef: Vec3): Vec3 {
            val x = officialEcef.y
            val y = officialEcef.z
            val z = officialEcef.x
            return Vec3(x, y, z)
        }

        private fun bilinearCentroid(sw: Vec3, se: Vec3, nw: Vec3, ne: Vec3): Vec3 {
            val px = sw.x * 0.25 + se.x * 0.25 + nw.x * 0.25 + ne.x * 0.25
            val py = sw.y * 0.25 + se.y * 0.25 + nw.y * 0.25 + ne.y * 0.25
            val pz = sw.z * 0.25 + se.z * 0.25 + nw.z * 0.25 + ne.z * 0.25
            return Vec3(px, py, pz)
        }
    }

    private lateinit var terrain: Terrain

    @BeforeTest
    fun setUp() {
        // Create the globe object used by the test
        val globe = Globe(Ellipsoid.WGS84, Wgs84Projection())

        // Create the terrain object used by the test
        terrain = BasicTerrain()

        // Add a terrain tile used to the mocked terrain
        val levelSet = LevelSet(Sector().setFullSphere(), fromDegrees(-90.0, -180.0), fromDegrees(1.0, 1.0), 1, 5, 5) // tiles with 5x5 vertices
        val tile = TerrainTile(fromDegrees(0.0, 0.0, 1.0, 1.0), levelSet.firstLevel!!, 90, 180)
        (terrain as BasicTerrain).addTile(tile)

        // Populate the terrain tile's geometry
        val tileWidth = tile.level.tileWidth
        val tileHeight = tile.level.tileHeight
        val rowStride = (tileWidth + 2) * 3
        val points = FloatArray((tileWidth + 2) * (tileHeight + 2) * 3)
        val tileOrigin = globe.geographicToCartesian(fromDegrees(0.5), fromDegrees(0.5), 0.0, Vec3())
        globe.geographicToCartesianGrid(tile.sector, tileWidth, tileHeight, null, 1.0f, tileOrigin, points, rowStride + 3, rowStride)
        globe.geographicToCartesianBorder(tile.sector, tileWidth + 2, tileHeight + 2, 0.0f, tileOrigin, points)
        tile.origin = tileOrigin
        tile.points = points
    }

    @Test
    fun testGetSector() {
        val expected = fromDegrees(0.0, 0.0, 1.0, 1.0)
        val actual = terrain.sector
        assertEquals(expected, actual, "sector")
    }

    @Test
    fun testSurfacePoint_SouthwestCorner() {
        val lat = fromDegrees(0.0)
        val lon = fromDegrees(0.0)
        val alt = 0.0
        val expected = worldWindEcef(officialWgs84Ecef(lat, lon, alt))
        val expectedReturn = true
        val actual = Vec3()
        val actualReturn = terrain.surfacePoint(lat, lon, actual)
        assertEquals(expected.x, actual.x, TOLERANCE, "surfacePoint Southwest corner x")
        assertEquals(expected.y, actual.y, TOLERANCE, "surfacePoint Southwest corner y")
        assertEquals(expected.z, actual.z, TOLERANCE, "surfacePoint Southwest corner z")
        assertEquals(expectedReturn, actualReturn, "surfacePoint Southwest corner return")
    }

    @Test
    fun testSurfacePoint_SoutheastCorner() {
        val lat = fromDegrees(0.0)
        val lon = fromDegrees(1.0)
        val alt = 0.0
        val expected = worldWindEcef(officialWgs84Ecef(lat, lon, alt))
        val expectedReturn = true
        val actual = Vec3()
        val actualReturn = terrain.surfacePoint(lat, lon, actual)
        assertEquals(expected.x, actual.x, TOLERANCE, "surfacePoint Southeast corner x")
        assertEquals(expected.y, actual.y, TOLERANCE, "surfacePoint Southeast corner y")
        assertEquals(expected.z, actual.z, TOLERANCE, "surfacePoint Southeast corner z")
        assertEquals(expectedReturn, actualReturn, "surfacePoint Southeast corner return")
    }

    @Test
    fun testSurfacePoint_NorthwestCorner() {
        val lat = fromDegrees(1.0)
        val lon = fromDegrees(0.0)
        val alt = 0.0
        val expected = worldWindEcef(officialWgs84Ecef(lat, lon, alt))
        val expectedReturn = true
        val actual = Vec3()
        val actualReturn = terrain.surfacePoint(lat, lon, actual)
        assertEquals(expected.x, actual.x, TOLERANCE, "surfacePoint Northwest corner x")
        assertEquals(expected.y, actual.y, TOLERANCE, "surfacePoint Northwest corner y")
        assertEquals(expected.z, actual.z, TOLERANCE, "surfacePoint Northwest corner z")
        assertEquals(expectedReturn, actualReturn, "surfacePoint Northwest corner return")
    }

    @Test
    fun testSurfacePoint_NortheastCorner() {
        val lat = fromDegrees(1.0)
        val lon = fromDegrees(1.0)
        val alt = 0.0
        val expected = worldWindEcef(officialWgs84Ecef(lat, lon, alt))
        val expectedReturn = true
        val actual = Vec3()
        val actualReturn = terrain.surfacePoint(lat, lon, actual)
        assertEquals(expected.x, actual.x, TOLERANCE, "surfacePoint Northeast corner x")
        assertEquals(expected.y, actual.y, TOLERANCE, "surfacePoint Northeast corner y")
        assertEquals(expected.z, actual.z, TOLERANCE, "surfacePoint Northeast corner z")
        assertEquals(expectedReturn, actualReturn, "surfacePoint Northeast corner return")
    }

    @Test
    fun testSurfacePoint_SouthEdge() {
        val lat = fromDegrees(0.0)
        val lon = fromDegrees(0.5)
        val alt = 0.0
        val expected = worldWindEcef(officialWgs84Ecef(lat, lon, alt))
        val expectedReturn = true
        val actual = Vec3()
        val actualReturn = terrain.surfacePoint(lat, lon, actual)
        assertEquals(expected.x, actual.x, TOLERANCE, "surfacePoint South edge x")
        assertEquals(expected.y, actual.y, TOLERANCE, "surfacePoint South edge y")
        assertEquals(expected.z, actual.z, TOLERANCE, "surfacePoint South edge z")
        assertEquals(expectedReturn, actualReturn, "surfacePoint South edge return")
    }

    @Test
    fun testSurfacePoint_NorthEdge() {
        val lat = fromDegrees(1.0)
        val lon = fromDegrees(0.5)
        val alt = 0.0
        val expected = worldWindEcef(officialWgs84Ecef(lat, lon, alt))
        val expectedReturn = true
        val actual = Vec3()
        val actualReturn = terrain.surfacePoint(lat, lon, actual)
        assertEquals(expected.x, actual.x, TOLERANCE, "surfacePoint North edge x")
        assertEquals(expected.y, actual.y, TOLERANCE, "surfacePoint North edge y")
        assertEquals(expected.z, actual.z, TOLERANCE, "surfacePoint North edge z")
        assertEquals(expectedReturn, actualReturn, "surfacePoint North edge return")
    }

    @Test
    fun testSurfacePoint_WestEdge() {
        val lat = fromDegrees(0.5)
        val lon = fromDegrees(0.0)
        val alt = 0.0
        val expected = worldWindEcef(officialWgs84Ecef(lat, lon, alt))
        val expectedReturn = true
        val actual = Vec3()
        val actualReturn = terrain.surfacePoint(lat, lon, actual)
        assertEquals(expected.x, actual.x, TOLERANCE, "surfacePoint West edge x")
        assertEquals(expected.y, actual.y, TOLERANCE, "surfacePoint West edge y")
        assertEquals(expected.z, actual.z, TOLERANCE, "surfacePoint West edge z")
        assertEquals(expectedReturn, actualReturn, "surfacePoint West edge return")
    }

    @Test
    fun testSurfacePoint_EastEdge() {
        val lat = fromDegrees(0.5)
        val lon = fromDegrees(1.0)
        val alt = 0.0
        val expected = worldWindEcef(officialWgs84Ecef(lat, lon, alt))
        val expectedReturn = true
        val actual = Vec3()
        val actualReturn = terrain.surfacePoint(lat, lon, actual)
        assertEquals(expected.x, actual.x, TOLERANCE, "surfacePoint East edge x")
        assertEquals(expected.y, actual.y, TOLERANCE, "surfacePoint East edge y")
        assertEquals(expected.z, actual.z, TOLERANCE, "surfacePoint East edge z")
        assertEquals(expectedReturn, actualReturn, "surfacePoint East edge return")
    }

    @Test
    fun testSurfacePoint_SouthwestCell() {
        val sw = officialWgs84Ecef(0.0, 0.0, 0.0)
        val se = officialWgs84Ecef(0.0, 0.25, 0.0)
        val nw = officialWgs84Ecef(0.25, 0.0, 0.0)
        val ne = officialWgs84Ecef(0.25, 0.25, 0.0)
        val expected = worldWindEcef(bilinearCentroid(sw, se, nw, ne))
        val expectedReturn = true
        val actual = Vec3()
        val actualReturn = terrain.surfacePoint(fromDegrees(0.125), fromDegrees(0.125), actual)
        assertEquals(expected.x, actual.x, TOLERANCE, "surfacePoint Southwest cell x")
        assertEquals(expected.y, actual.y, TOLERANCE, "surfacePoint Southwest cell y")
        assertEquals(expected.z, actual.z, TOLERANCE, "surfacePoint Southwest cell z")
        assertEquals(expectedReturn, actualReturn, "surfacePoint Southwest cell return")
    }

    @Test
    fun testSurfacePoint_SoutheastCell() {
        val sw = officialWgs84Ecef(0.0, 0.75, 0.0)
        val se = officialWgs84Ecef(0.0, 1.0, 0.0)
        val nw = officialWgs84Ecef(0.25, 0.75, 0.0)
        val ne = officialWgs84Ecef(0.25, 1.0, 0.0)
        val expected = worldWindEcef(bilinearCentroid(sw, se, nw, ne))
        val expectedReturn = true
        val actual = Vec3()
        val actualReturn = terrain.surfacePoint(fromDegrees(0.125), fromDegrees(0.875), actual)
        assertEquals(expected.x, actual.x, TOLERANCE, "surfacePoint Southeast cell x")
        assertEquals(expected.y, actual.y, TOLERANCE, "surfacePoint Southeast cell y")
        assertEquals(expected.z, actual.z, TOLERANCE, "surfacePoint Southeast cell z")
        assertEquals(expectedReturn, actualReturn, "surfacePoint Southeast cell return")
    }

    @Test
    fun testSurfacePoint_NorthwestCell() {
        val sw = officialWgs84Ecef(0.75, 0.0, 0.0)
        val se = officialWgs84Ecef(0.75, 0.25, 0.0)
        val nw = officialWgs84Ecef(1.0, 0.0, 0.0)
        val ne = officialWgs84Ecef(1.0, 0.25, 0.0)
        val expected = worldWindEcef(bilinearCentroid(sw, se, nw, ne))
        val expectedReturn = true
        val actual = Vec3()
        val actualReturn = terrain.surfacePoint(fromDegrees(0.875), fromDegrees(0.125), actual)
        assertEquals(expected.x, actual.x, TOLERANCE, "surfacePoint Northwest cell x")
        assertEquals(expected.y, actual.y, TOLERANCE, "surfacePoint Northwest cell y")
        assertEquals(expected.z, actual.z, TOLERANCE, "surfacePoint Northwest cell z")
        assertEquals(expectedReturn, actualReturn, "surfacePoint Northwest cell return")
    }

    @Test
    fun testSurfacePoint_NortheastCell() {
        val sw = officialWgs84Ecef(0.75, 0.75, 0.0)
        val se = officialWgs84Ecef(0.75, 1.0, 0.0)
        val nw = officialWgs84Ecef(1.0, 0.75, 0.0)
        val ne = officialWgs84Ecef(1.0, 1.0, 0.0)
        val expected = worldWindEcef(bilinearCentroid(sw, se, nw, ne))
        val expectedReturn = true
        val actual = Vec3()
        val actualReturn = terrain.surfacePoint(fromDegrees(0.875), fromDegrees(0.875), actual)
        assertEquals(expected.x, actual.x, TOLERANCE, "surfacePoint Northeast cell x")
        assertEquals(expected.y, actual.y, TOLERANCE, "surfacePoint Northeast cell y")
        assertEquals(expected.z, actual.z, TOLERANCE, "surfacePoint Northeast cell z")
        assertEquals(expectedReturn, actualReturn, "surfacePoint Northeast cell return")
    }

    @Test
    fun testSurfacePoint_Centroid() {
        val lat = fromDegrees(0.5)
        val lon = fromDegrees(0.5)
        val alt = 0.0
        val expected = worldWindEcef(officialWgs84Ecef(lat, lon, alt))
        val expectedReturn = true
        val actual = Vec3()
        val actualReturn = terrain.surfacePoint(lat, lon, actual)
        assertEquals(expected.x, actual.x, TOLERANCE, "surfacePoint centroid x")
        assertEquals(expected.y, actual.y, TOLERANCE, "surfacePoint centroid y")
        assertEquals(expected.z, actual.z, TOLERANCE, "surfacePoint centroid z")
        assertEquals(expectedReturn, actualReturn, "surfacePoint centroid return")
    }
}