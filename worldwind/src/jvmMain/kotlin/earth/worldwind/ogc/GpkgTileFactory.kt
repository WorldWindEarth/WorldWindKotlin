package earth.worldwind.ogc

import earth.worldwind.geom.Sector
import earth.worldwind.ogc.gpkg.GpkgContent
import earth.worldwind.util.Level
import earth.worldwind.util.Tile
import earth.worldwind.util.TileFactory

actual class GpkgTileFactory actual constructor(tiles: GpkgContent) : TileFactory {
    override fun createTile(sector: Sector, level: Level, row: Int, column: Int): Tile {
        TODO("Not yet implemented")
    }
}