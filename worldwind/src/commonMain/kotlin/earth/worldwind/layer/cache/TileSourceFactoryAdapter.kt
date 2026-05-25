package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileSource

import earth.worldwind.geom.Sector
import earth.worldwind.layer.mercator.MercatorImageTile
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.image.ImageTile
import earth.worldwind.util.Level
import earth.worldwind.util.Tile
import earth.worldwind.util.TileFactory

/**
 * [TileFactory] adapter that produces [ImageTile]s whose `imageSource` reads bytes from a
 * [TileSource]. Bridges the rendering pipeline's TileFactory contract (used by
 * [earth.worldwind.shape.TiledSurfaceImage] for level-set iteration + mip-fallback) onto
 * the cache redesign's source contract.
 *
 * Cache wiring is external — wrap [source] in a [CachedTileSource] before constructing
 * this adapter when you want bytes persisted through a [TileStore].
 *
 * GeoPackage tile-row convention: WorldWind iterates tiles row-bottom-up, but GeoPackage
 * tile pyramids index row-top-down. The adapter converts coords at [createTile] time, so
 * source implementations always see slippy-map (y-from-top) coordinates.
 */
class TileSourceFactoryAdapter(
    val source: TileSource,
    val imageFormat: String = "image/png",
) : TileFactory {
    override val contentType = "TileSource"

    override fun createTile(sector: Sector, level: Level, row: Int, column: Int): Tile {
        val tile = if (sector is MercatorSector) {
            MercatorImageTile(sector, level, row, column)
        } else {
            ImageTile(sector, level, row, column)
        }
        // Convert WorldWind's bottom-up tile row to slippy-map y (top-down). Column / row
        // ranges come from the [Level]'s actual grid dimensions — WMS pyramids derive their
        // counts from the advertised bounding box + firstLevelDelta, so the grid is NOT
        // necessarily 2^z × 2^(z-1) like a Web-Mercator slippy map. Using level dimensions
        // here keeps `imageSource` populated for every legitimate tile.
        val cols = level.levelWidth / level.tileWidth
        val rows = level.levelHeight / level.tileHeight
        val slippyY = rows - row - 1
        if (column in 0 until cols && slippyY in 0 until rows) {
            // The tile is its own postprocessor — MercatorImageTile.process() reprojects
            // Web-Mercator bytes to equirectangular before the image hits GL. Without
            // postprocessor = tile, cached Mercator tiles render with the equator squished.
            tile.imageSource =
                buildTileSourceImageSource(source, level.levelNumber, column, slippyY, imageFormat)
                    .also { it.postprocessor = tile }
        }
        return tile
    }
}

/**
 * Construct a platform-specific [ImageSource] backed by [source] for tile `(z, x, y)`.
 * The platform `actual` returns an [ImageSource.ImageFactory] that fetches via
 * [TileSource.fetchTile] and decodes the resulting bytes (PNG/JPEG/WebP/…) into the
 * platform's native bitmap type.
 */
expect fun buildTileSourceImageSource(
    source: TileSource, z: Int, x: Int, y: Int, imageFormat: String,
): ImageSource
