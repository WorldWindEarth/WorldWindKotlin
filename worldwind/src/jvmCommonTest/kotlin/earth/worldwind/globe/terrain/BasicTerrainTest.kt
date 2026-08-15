package earth.worldwind.globe.terrain

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Line
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Location.Companion.fromDegrees
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
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
            val cosLat = cos(latitude.inRadians)
            val sinLat = sin(latitude.inRadians)
            val cosLon = cos(longitude.inRadians)
            val sinLon = sin(longitude.inRadians)
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

        // Mirrors BasicTessellator.assembleTriStripElements for the full (width + 2) x (height + 2) tile grid
        private fun assembleTriStripElements(numLat: Int, numLon: Int): ShortArray {
            val count = ((numLat - 1) * numLon + (numLat - 2)) * 2
            val result = ShortArray(count)
            var pos = 0
            var vertex = 0
            for (latIndex in 0 until numLat - 1) {
                for (lonIndex in 0 until numLon) {
                    vertex = lonIndex + latIndex * numLon
                    result[pos++] = (vertex + numLon).toShort()
                    result[pos++] = vertex.toShort()
                }
                if (latIndex < numLat - 2) {
                    result[pos++] = vertex.toShort()
                    result[pos++] = ((latIndex + 2) * numLon).toShort()
                }
            }
            return result
        }
    }

    // Exposes computeLocalBounds, which production code runs in prepare()
    private class TestTerrainTile(
        sector: Sector, level: earth.worldwind.util.Level, row: Int, column: Int
    ) : TerrainTile(sector, level, row, column) {
        fun updateLocalBounds() = computeLocalBounds()
    }

    private lateinit var terrain: Terrain

    @BeforeTest
    fun setUp() {
        // Create the globe object used by the test
        val globe = Globe()

        // Add a terrain tile used to the mocked terrain
        val levelSet = LevelSet(Sector().setFullSphere(), Sector().setFullSphere(), fromDegrees(1.0, 1.0), 1, 5, 5) // tiles with 5x5 vertices
        val tile = TestTerrainTile(fromDegrees(0.0, 0.0, 1.0, 1.0), levelSet.firstLevel, 90, 180)
        val triStripElements = assembleTriStripElements(tile.level.tileHeight + 2, tile.level.tileWidth + 2)
        terrain = BasicTerrain(listOf(tile), tile.sector, triStripElements)

        // Populate the terrain tile's geometry
        val tileWidth = tile.level.tileWidth
        val tileHeight = tile.level.tileHeight
        val rowStride = (tileWidth + 2) * 3
        val points = tile.points
        val tileOrigin = globe.geographicToCartesian(0.5.degrees, 0.5.degrees, 0.0, tile.origin)
        globe.geographicToCartesianGrid(tile.sector, tileWidth, tileHeight, null, tileOrigin, points, rowStride + 3, rowStride)
        globe.geographicToCartesianBorder(tile.sector, tileWidth + 2, tileHeight + 2, 0.0f, tileOrigin, points)
        tile.updateLocalBounds()
    }

    @Test
    fun testGetSector() {
        val expected = fromDegrees(0.0, 0.0, 1.0, 1.0)
        val actual = terrain.sector
        assertEquals(expected, actual, "sector")
    }

    @Test
    fun testSurfacePoint_SouthwestCorner() {
        val lat = 0.0.degrees
        val lon = 0.0.degrees
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
        val lat = 0.0.degrees
        val lon = 1.0.degrees
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
        val lat = 1.0.degrees
        val lon = 0.0.degrees
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
        val lat = 1.0.degrees
        val lon = 1.0.degrees
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
        val lat = 0.0.degrees
        val lon = 0.5.degrees
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
        val lat = 1.0.degrees
        val lon = 0.5.degrees
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
        val lat = 0.5.degrees
        val lon = 0.0.degrees
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
        val lat = 0.5.degrees
        val lon = 1.0.degrees
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
        val actualReturn = terrain.surfacePoint(0.125.degrees, 0.125.degrees, actual)
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
        val actualReturn = terrain.surfacePoint(0.125.degrees, 0.875.degrees, actual)
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
        val actualReturn = terrain.surfacePoint(0.875.degrees, 0.125.degrees, actual)
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
        val actualReturn = terrain.surfacePoint(0.875.degrees, 0.875.degrees, actual)
        assertEquals(expected.x, actual.x, TOLERANCE, "surfacePoint Northeast cell x")
        assertEquals(expected.y, actual.y, TOLERANCE, "surfacePoint Northeast cell y")
        assertEquals(expected.z, actual.z, TOLERANCE, "surfacePoint Northeast cell z")
        assertEquals(expectedReturn, actualReturn, "surfacePoint Northeast cell return")
    }

    @Test
    fun testSurfacePoint_Centroid() {
        val lat = 0.5.degrees
        val lon = 0.5.degrees
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

    @Test
    fun testIntersect_HitsGridVertex() {
        // Ray along the ellipsoid normal through the tile's central grid vertex - the local
        // bounds test must pass the tile through to the strip walk, which hits the vertex.
        val target = worldWindEcef(officialWgs84Ecef(0.5, 0.5, 0.0))
        val origin = worldWindEcef(officialWgs84Ecef(0.5, 0.5, 1.0e4))
        val direction = Vec3(target.x - origin.x, target.y - origin.y, target.z - origin.z).normalize()
        val actual = Vec3()
        val actualReturn = terrain.intersect(Line(origin, direction), actual)
        assertEquals(true, actualReturn, "intersect return")
        assertEquals(target.x, actual.x, TOLERANCE, "intersect x")
        assertEquals(target.y, actual.y, TOLERANCE, "intersect y")
        assertEquals(target.z, actual.z, TOLERANCE, "intersect z")
    }

    @Test
    fun testIntersect_MissesOutsideTile() {
        // A ray far outside the tile's sector must not intersect
        val target = worldWindEcef(officialWgs84Ecef(5.0, 5.0, 0.0))
        val origin = worldWindEcef(officialWgs84Ecef(5.0, 5.0, 1.0e4))
        val direction = Vec3(target.x - origin.x, target.y - origin.y, target.z - origin.z).normalize()
        val actualReturn = terrain.intersect(Line(origin, direction), Vec3())
        assertEquals(false, actualReturn, "intersect return")
    }

    @Test
    fun testIntersect_IgnoresIntersectionBehindOrigin() {
        // Same line as the hit case pointing away from the globe - the surface lies behind
        // the ray's origin and must be ignored.
        val target = worldWindEcef(officialWgs84Ecef(0.5, 0.5, 0.0))
        val origin = worldWindEcef(officialWgs84Ecef(0.5, 0.5, 1.0e4))
        val direction = Vec3(origin.x - target.x, origin.y - target.y, origin.z - target.z).normalize()
        val actualReturn = terrain.intersect(Line(origin, direction), Vec3())
        assertEquals(false, actualReturn, "intersect return")
    }
}