package earth.worldwind.util

import earth.worldwind.geom.Sector
import kotlin.test.Test
import kotlin.test.assertEquals

class LevelSetTest {
    @Test
    fun testTilesInSector() {
        val config = LevelSetConfig()
        val levelSet = LevelSet(config)
        val level = levelSet.firstLevel
        val sector = Sector.fromDegrees(-45.0, -45.0, 90.0, 90.0)
        val count = level.tilesInSector(sector)
        assertEquals(4, count, "Tile count is invalid")
    }
}