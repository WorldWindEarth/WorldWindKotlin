package earth.worldwind.render.image

import earth.worldwind.geom.Sector
import earth.worldwind.util.Level
import earth.worldwind.util.Tile

expect open class ImageTile(sector: Sector, level: Level, row: Int, column: Int): Tile {
    var imageSource: ImageSource?
    var cacheSource: ImageSource?
}