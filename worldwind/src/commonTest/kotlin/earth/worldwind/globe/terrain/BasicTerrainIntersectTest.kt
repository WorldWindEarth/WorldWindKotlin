package earth.worldwind.globe.terrain

import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Line
import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.util.LevelSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [BasicTerrain.intersect] must return the nearest hit even when the ray does not start at the camera. */
class BasicTerrainIntersectTest {

    private val levelSet = LevelSet(
        sector = Sector().setFullSphere(),
        tileOrigin = Sector().setFullSphere(),
        firstLevelDelta = Location(fromDegrees(45.0), fromDegrees(45.0)),
        numLevels = 1,
        tileWidth = 1,
        tileHeight = 1,
    )

    /** One triangle square to the ray at [x], wide enough that a ray down the X axis lands inside it. */
    private fun tileAt(x: Float, row: Int): TerrainTile {
        val tile = TerrainTile(Sector().setFullSphere(), levelSet.firstLevel, row, 0)
        val points = tile.points
        points[0] = x; points[1] = -50f; points[2] = -50f
        points[3] = x; points[4] = 50f; points[5] = -50f
        points[6] = x; points[7] = 0f; points[8] = 50f
        tile.localBounds[0] = x; tile.localBounds[1] = x
        tile.localBounds[2] = -50f; tile.localBounds[3] = 50f
        tile.localBounds[4] = -50f; tile.localBounds[5] = 50f
        return tile
    }

    /** Elements for a single triangle. */
    private val singleTriangle = shortArrayOf(0, 1, 2)

    private fun terrain(vararg tiles: TerrainTile) =
        BasicTerrain(tiles.toList(), Sector().setFullSphere(), singleTriangle)

    private fun rayAlongX() = Line(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))

    @Test
    fun takes_the_nearer_tile_when_it_comes_first_in_the_list() {
        val result = Vec3()
        assertTrue(terrain(tileAt(100f, 0), tileAt(500f, 1)).intersect(rayAlongX(), result))
        assertEquals(100.0, result.x, 1e-6)
    }

    @Test
    fun takes_the_nearer_tile_when_it_comes_last_in_the_list() {
        // Tiles are listed in reverse ray order; stopping at the first hit would return 500
        val result = Vec3()
        assertTrue(terrain(tileAt(500f, 0), tileAt(100f, 1)).intersect(rayAlongX(), result))
        assertEquals(100.0, result.x, 1e-6)
    }

    @Test
    fun ignores_tiles_behind_the_ray_origin() {
        val result = Vec3()
        assertTrue(terrain(tileAt(-100f, 0), tileAt(300f, 1)).intersect(rayAlongX(), result))
        assertEquals(300.0, result.x, 1e-6)
    }

    @Test
    fun answers_false_when_the_ray_meets_nothing() {
        assertTrue(!terrain(tileAt(-100f, 0)).intersect(rayAlongX(), Vec3()))
    }

    @Test
    fun is_unaffected_by_a_direction_that_is_not_a_unit_vector() {
        val result = Vec3()
        val ray = Line(Vec3(0.0, 0.0, 0.0), Vec3(7.0, 0.0, 0.0))
        assertTrue(terrain(tileAt(500f, 0), tileAt(100f, 1)).intersect(ray, result))
        assertEquals(100.0, result.x, 1e-6)
    }
}
