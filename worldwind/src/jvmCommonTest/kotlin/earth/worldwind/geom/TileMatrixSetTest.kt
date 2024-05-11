package earth.worldwind.geom

import kotlin.test.Test
import kotlin.test.assertEquals

class TileMatrixSetTest {
    @Test
    fun testTilesInSector() {
        val tileMatrix = TileMatrix(Sector().setFullSphere(), 0, 4, 2, 256, 256)
        val sector = Sector.fromDegrees(-45.0, -45.0, 90.0, 90.0)
        val count = tileMatrix.tilesInSector(sector)
        assertEquals(4, count, "Tile count is invalid")
    }
}