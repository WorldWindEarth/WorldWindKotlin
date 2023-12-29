package earth.worldwind.ogc

import earth.worldwind.geom.Sector
import earth.worldwind.layer.mercator.MercatorImageTile
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.ogc.gpkg.GpkgContent
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.image.ImageTile
import earth.worldwind.util.CacheTileFactory
import earth.worldwind.util.Level

open class GpkgTileFactory(protected val tiles: GpkgContent, protected val imageFormat: String? = null): CacheTileFactory {
    @Throws(IllegalStateException::class)
    override suspend fun clearContent(deleteMetadata: Boolean) = if (deleteMetadata) {
        tiles.container.deleteContent(tiles.tableName)
    } else {
        tiles.container.clearContent(tiles.tableName)
    }

    override fun createTile(sector: Sector, level: Level, row: Int, column: Int) =
        buildTile(sector, level, row, column).apply {
            imageSource = getImageSource(level, row, column)?.also { it.postprocessor = this }
        }

    protected open fun buildTile(sector: Sector, level: Level, row: Int, column: Int) = if (sector is MercatorSector) {
        MercatorImageTile(sector, level, row, column)
    } else {
        ImageTile(sector, level, row, column)
    }

    protected open fun getImageSource(level: Level, row: Int, column: Int): ImageSource? {
        val tileMatrixByZoomLevel = tiles.container.tileMatrix[tiles.tableName] ?: return null

        // Attempt to find the GeoPackage tile matrix associated with the WorldWind level.
        tileMatrixByZoomLevel[level.levelNumber]?.let { tileMatrix ->
            // Convert the WorldWind tile row to the equivalent GeoPackage tile row.
            val gpkgRow = level.levelHeight / level.tileHeight - row - 1
            if (column < tileMatrix.matrixWidth && gpkgRow < tileMatrix.matrixHeight) {
                return buildImageSource(tiles, level.levelNumber, column, gpkgRow, imageFormat)
            }
        }
        return null
    }
}

expect fun buildImageSource(tiles: GpkgContent, zoomLevel: Int, column: Int, gpkgRow: Int, imageFormat: String?): ImageSource