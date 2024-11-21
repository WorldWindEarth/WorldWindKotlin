package earth.worldwind.render.image

import earth.worldwind.geom.Sector
import earth.worldwind.util.Level
import earth.worldwind.util.ResourcePostprocessor
import earth.worldwind.util.Tile
import org.w3c.dom.Image
import org.w3c.dom.events.Event

actual open class ImageTile actual constructor(
    sector: Sector, level: Level, row: Int, column: Int
): Tile(sector, level, row, column), ResourcePostprocessor {
    actual var imageSource: ImageSource? = null
    actual var cacheSource: ImageSource? = null

    /**
     * Repeat image.onLoad event defined in RenderResourceCache to continue retrieval of original unprocessed image
     */
    actual override suspend fun <Resource> process(resource: Resource): Resource {
        if (resource is Image) {
            resource.onload?.invoke(Event("load"))
        }
        return resource
    }
}