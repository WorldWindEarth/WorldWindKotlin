package earth.worldwind.render

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Location.Companion.fromDegrees
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.globe.terrain.BasicTerrain
import earth.worldwind.globe.terrain.TerrainTile
import earth.worldwind.util.Level
import earth.worldwind.util.LevelSet
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CartesianCacheTest {
    private class TestTile(sector: Sector, level: Level, row: Int, column: Int) : TerrainTile(sector, level, row, column) {
        fun bumpPointBufferVersion() = updatePointBufferKey()
    }

    private lateinit var globe: Globe
    private lateinit var rc: RenderContext
    private lateinit var levelSet: LevelSet
    private lateinit var tile: TestTile

    private fun makeTile(sector: Sector, row: Int, column: Int): TestTile {
        val level = levelSet.firstLevel
        val result = TestTile(sector, level, row, column)
        val rowStride = (level.tileWidth + 2) * 3
        globe.geographicToCartesian(sector.centroidLatitude, sector.centroidLongitude, 0.0, result.origin)
        globe.geographicToCartesianGrid(sector, level.tileWidth, level.tileHeight, null, result.origin, result.points, rowStride + 3, rowStride)
        globe.geographicToCartesianBorder(sector, level.tileWidth + 2, level.tileHeight + 2, 0f, result.origin, result.points)
        return result
    }

    private fun shiftPoints(tile: TestTile, offset: Float) {
        for (i in tile.points.indices) tile.points[i] += offset
    }

    @BeforeTest
    fun setUp() {
        globe = Globe()
        levelSet = LevelSet(Sector().setFullSphere(), Sector().setFullSphere(), fromDegrees(1.0, 1.0), 1, 5, 5)
        tile = makeTile(fromDegrees(0.0, 0.0, 1.0, 1.0), 90, 180)
        rc = RenderContext()
        rc.globe = globe
        rc.globeState = globe.state
        rc.terrain = BasicTerrain(listOf(tile), tile.sector, null, 1L)
    }

    @Test
    fun testClampedPointCachedWhileCoveringTileIntact() {
        val cache = CartesianCache()
        val position = Position.fromDegrees(0.125, 0.125, 0.0)
        val first = rc.geographicToCartesian(position, AltitudeMode.CLAMP_TO_GROUND, Vec3(), cache)

        // Corrupt the geometry without bumping its version - a cache hit must still return the original point
        shiftPoints(tile, 1000f)
        val second = rc.geographicToCartesian(position, AltitudeMode.CLAMP_TO_GROUND, Vec3(), cache)
        assertEquals(first, second, "cached point on unchanged terrain")

        // Same tile rendered by a new terrain with a new version - the point survives the version change
        rc.terrain = BasicTerrain(listOf(tile), tile.sector, null, 2L)
        val third = rc.geographicToCartesian(position, AltitudeMode.CLAMP_TO_GROUND, Vec3(), cache)
        assertEquals(first, third, "cached point when covering tile is intact across versions")
    }

    @Test
    fun testClampedPointRecomputedWhenTileGeometryChanges() {
        val cache = CartesianCache()
        val position = Position.fromDegrees(0.125, 0.125, 0.0)
        val first = rc.geographicToCartesian(position, AltitudeMode.CLAMP_TO_GROUND, Vec3(), cache)

        // Elevation update or vertical exaggeration would re-project the grid and bump the point buffer version
        shiftPoints(tile, 1000f)
        tile.bumpPointBufferVersion()
        rc.terrain = BasicTerrain(listOf(tile), tile.sector, null, 2L)
        val second = rc.geographicToCartesian(position, AltitudeMode.CLAMP_TO_GROUND, Vec3(), cache)
        assertNotEquals(first, second, "recomputed point when tile geometry changed")
    }

    @Test
    fun testClampedPointRecomputedWhenCoveringTileChanges() {
        val cache = CartesianCache()
        val position = Position.fromDegrees(0.125, 0.125, 0.0)
        val first = rc.geographicToCartesian(position, AltitudeMode.CLAMP_TO_GROUND, Vec3(), cache)

        // A different tile covers the location now, e.g. terrain LoD refinement replaced the tile
        val finerTile = makeTile(fromDegrees(0.0, 0.0, 1.0, 1.0), 91, 180)
        shiftPoints(finerTile, 500f)
        rc.terrain = BasicTerrain(listOf(finerTile), finerTile.sector, null, 2L)
        val second = rc.geographicToCartesian(position, AltitudeMode.CLAMP_TO_GROUND, Vec3(), cache)
        assertNotEquals(first, second, "recomputed point when another tile covers the location")
    }

    @Test
    fun testFallbackRetriedWhenTerrainChanges() {
        val cache = CartesianCache()
        val position = Position.fromDegrees(2.125, 2.125, 0.0)

        // No tile covers the location - the elevation model fallback is cached
        val first = rc.geographicToCartesian(position, AltitudeMode.CLAMP_TO_GROUND, Vec3(), cache)
        val second = rc.geographicToCartesian(position, AltitudeMode.CLAMP_TO_GROUND, Vec3(), cache)
        assertEquals(first, second, "cached fallback point on unchanged terrain")

        // Terrain covering the location appears - the fallback must be replaced by the surface point
        val coveringTile = makeTile(fromDegrees(2.0, 2.0, 1.0, 1.0), 92, 182)
        shiftPoints(coveringTile, 500f)
        rc.terrain = BasicTerrain(listOf(tile, coveringTile), fromDegrees(0.0, 0.0, 3.0, 3.0), null, 2L)
        val third = rc.geographicToCartesian(position, AltitudeMode.CLAMP_TO_GROUND, Vec3(), cache)
        assertNotEquals(first, third, "recomputed point when terrain coverage appeared")
    }

    @Test
    fun testAbsolutePointInvalidatedByPositionAndExaggeration() {
        val cache = CartesianCache()
        val position = Position.fromDegrees(10.0, 20.0, 1000.0)
        val first = rc.geographicToCartesian(position, AltitudeMode.ABSOLUTE, Vec3(), cache)
        val second = rc.geographicToCartesian(position, AltitudeMode.ABSOLUTE, Vec3(), cache)
        assertEquals(first, second, "cached absolute point")

        val moved = rc.geographicToCartesian(Position.fromDegrees(11.0, 20.0, 1000.0), AltitudeMode.ABSOLUTE, Vec3(), cache)
        assertNotEquals(first, moved, "recomputed absolute point on position change")

        globe.verticalExaggeration = 2.0
        val exaggerated = rc.geographicToCartesian(position, AltitudeMode.ABSOLUTE, Vec3(), cache)
        assertNotEquals(moved, exaggerated, "recomputed absolute point on vertical exaggeration change")
    }

    @Test
    fun testSurfacePointTileReportsCoveringTile() {
        val result = Vec3()
        assertEquals(tile, rc.terrain.surfacePointTile(0.125.degrees, 0.125.degrees, result), "covering tile")
        assertEquals(null, rc.terrain.surfacePointTile(2.0.degrees, 2.0.degrees, result), "no covering tile")
        assertEquals(true, rc.terrain.containsTile(tile.tileKey, tile.pointBufferVersion), "tile version consistent")
        assertEquals(false, rc.terrain.containsTile(tile.tileKey, tile.pointBufferVersion + 1), "tile version changed")
        assertEquals(false, rc.terrain.containsTile("unknown", 0), "unknown tile key")
    }
}
