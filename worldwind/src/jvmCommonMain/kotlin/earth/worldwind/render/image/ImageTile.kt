package earth.worldwind.render.image

import earth.worldwind.geom.Sector
import earth.worldwind.util.Level
import earth.worldwind.util.ResourcePostprocessor
import earth.worldwind.util.Tile

actual open class ImageTile actual constructor(
    sector: Sector, level: Level, row: Int, column: Int
): Tile(sector, level, row, column), ResourcePostprocessor {
    actual var imageSource: ImageSource? = null
    actual var cacheSource: ImageSource? = null

    actual override suspend fun <Resource> process(resource: Resource): Resource {
        val source = cacheSource?.asUnrecognized()
        return if (source is ResourcePostprocessor) source.process(resource) else resource
    }
}