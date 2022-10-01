package earth.worldwind.render.image

import earth.worldwind.geom.Sector
import earth.worldwind.util.DownloadPostprocessor
import earth.worldwind.util.Level
import earth.worldwind.util.Tile
import java.awt.image.BufferedImage

actual open class ImageTile actual constructor(
    sector: Sector, level: Level, row: Int, column: Int
): Tile(sector, level, row, column), DownloadPostprocessor<BufferedImage> {
    actual var imageSource: ImageSource? = null
    actual var cacheSource: ImageSource? = null

    override fun process(resource: BufferedImage) = resource
}