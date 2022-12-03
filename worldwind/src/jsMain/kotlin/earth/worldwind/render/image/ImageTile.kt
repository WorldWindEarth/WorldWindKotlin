package earth.worldwind.render.image

import earth.worldwind.geom.Sector
import earth.worldwind.util.DownloadPostprocessor
import earth.worldwind.util.Level
import earth.worldwind.util.Tile
import org.w3c.dom.Image
import org.w3c.dom.events.Event

actual open class ImageTile actual constructor(
    sector: Sector, level: Level, row: Int, column: Int
): Tile(sector, level, row, column), DownloadPostprocessor<Image> {
    actual var imageSource: ImageSource? = null
    actual var cacheSource: ImageSource? = null

    /**
     * Repeat image.onLoad event defined in RenderResourceCache to continue retrieval of original unprocessed image
     */
    override suspend fun process(resource: Image) = resource.also { resource.onload?.invoke(Event("load")) as Unit }
}