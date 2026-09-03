package earth.worldwind.layer.geotiff

import earth.worldwind.formats.geotiff.GeoTiffDataset
import earth.worldwind.formats.geotiff.TiffDataSource
import earth.worldwind.geom.Angle.Companion.toRadians
import earth.worldwind.geom.Sector
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.render.image.ArgbImage
import earth.worldwind.render.image.ImageTile
import earth.worldwind.render.image.argbImageSource
import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.Level
import earth.worldwind.util.LevelSet
import earth.worldwind.util.LevelSetConfig
import earth.worldwind.util.Tile
import earth.worldwind.util.TileFactory

/**
 * Displays a tiled GeoTIFF — an orthophoto, a scanned chart, a colour-mapped raster — as an
 * ordinary image layer. Tiles are resampled straight out of the file's own tile grid at the
 * overview level closest to each tile's resolution, so the layer costs the same per frame
 * whether the source is 50 MB or 50 GB.
 *
 * The layer holds the [dataset] open for as long as it is displayed; [close] releases the
 * underlying file. A [clone] shares the dataset with its original, so close only once.
 */
open class GeoTiffImageLayer protected constructor(
    /** The open GeoTIFF backing this layer. */
    val dataset: GeoTiffDataset, displayName: String, levelSet: LevelSet,
) : TiledImageLayer(displayName, TiledSurfaceImage(GeoTiffTileFactory(dataset), levelSet)) {

    /** Geographic bounds of the imagery. */
    val sector: Sector get() = dataset.sector

    /** Release the underlying file. The layer renders nothing afterwards. */
    fun close() = dataset.close()

    override fun clone() = GeoTiffImageLayer(dataset, displayName ?: DEFAULT_NAME, LevelSet(levelSet(dataset)))

    companion object {
        private const val DEFAULT_NAME = "GeoTIFF"
        /** Tile size in texels. 256 matches the rest of the engine's raster layers. */
        private const val TILE_SIZE = 256
        /** Ceiling on pyramid depth — level 24 already resolves well under a centimetre. */
        private const val MAX_LEVELS = 24

        /**
         * Build a layer over [source]. Returns `null` when the bytes aren't a GeoTIFF the
         * engine can read or georeference — the reason is logged. On success the layer owns
         * [source] and closes it in [close].
         */
        fun create(source: TiffDataSource, displayName: String = DEFAULT_NAME): GeoTiffImageLayer? {
            val dataset = GeoTiffDataset.open(source) ?: return null
            return create(dataset, displayName)
        }

        /** Build a layer over an already-open [dataset]. */
        fun create(dataset: GeoTiffDataset, displayName: String = DEFAULT_NAME) =
            GeoTiffImageLayer(dataset, displayName, LevelSet(levelSet(dataset)))

        /** Pyramid covering the raster's sector down to its native resolution. The tile grid
         *  keeps the engine's global origin, so only the levels' tiles that intersect the
         *  raster are ever created. */
        private fun levelSet(dataset: GeoTiffDataset) = LevelSetConfig().apply {
            sector.copy(dataset.sector)
            tileWidth = TILE_SIZE
            tileHeight = TILE_SIZE
            numLevels = numLevelsForResolution(toRadians(dataset.degreesPerPixel)).coerceIn(1, MAX_LEVELS)
        }
    }
}

/**
 * Produces one [ImageTile] per pyramid cell whose pixels are resampled from the GeoTIFF on
 * demand. The image source's cache key is the tile address, so a tile evicted from the
 * subdivision cache and rebuilt still matches its uploaded texture instead of re-decoding.
 */
internal class GeoTiffTileFactory(private val dataset: GeoTiffDataset) : TileFactory {
    override val contentType = "GeoTIFF"

    override fun createTile(sector: Sector, level: Level, row: Int, column: Int): Tile {
        val tile = ImageTile(sector, level, row, column)
        // The engine reuses the sector instance while assembling tiles; keep our own copy.
        val tileSector = Sector(sector)
        val width = level.tileWidth
        val height = level.tileHeight
        tile.imageSource = argbImageSource(TileKey(dataset, level.levelNumber, row, column)) {
            dataset.sampleArgb(tileSector, width, height)?.let { ArgbImage(width, height, it) }
        }
        return tile
    }

    /** Texture-cache identity of one tile: same dataset, same address, same texture. */
    private data class TileKey(val dataset: GeoTiffDataset, val level: Int, val row: Int, val column: Int)
}
