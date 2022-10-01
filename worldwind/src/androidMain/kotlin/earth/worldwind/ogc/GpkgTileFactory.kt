package earth.worldwind.ogc

import android.graphics.Bitmap
import earth.worldwind.geom.Sector
import earth.worldwind.ogc.gpkg.GpkgContent
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.image.ImageTile
import earth.worldwind.util.Level
import earth.worldwind.util.TileFactory

actual open class GpkgTileFactory actual constructor(protected val tiles: GpkgContent): TileFactory {
    protected val levelOffset = tiles.container.getTileMatrix(tiles.tableName)?.keys?.sorted()?.get(0) ?: 0
    var format = Bitmap.CompressFormat.PNG
    var quality = 100

    override fun createTile(sector: Sector, level: Level, row: Int, column: Int) =
        ImageTile(sector, level, row, column).apply { imageSource = getImageSource(level, row, column) }

    protected open fun getImageSource(level: Level, row: Int, column: Int): ImageSource? {
        val tileMatrixByZoomLevel = tiles.container.getTileMatrix(tiles.tableName) ?: return null

        // Attempt to find the GeoPackage tile matrix associated with the WorldWind level.
        val zoomLevel = level.levelNumber + levelOffset
        tileMatrixByZoomLevel[zoomLevel]?.let { tileMatrix ->
            // Convert the WorldWind tile row to the equivalent GeoPackage tile row.
            val gpkgRow = level.levelHeight / level.tileHeight - row - 1
            if (column < tileMatrix.matrixWidth && gpkgRow < tileMatrix.matrixHeight) {
                // Configure the tile with a bitmap factory that reads directly from the GeoPackage.
                return ImageSource.fromBitmapFactory(GpkgBitmapFactory(tiles, zoomLevel, column, gpkgRow, format, quality))
            }
        }
        return null
    }
}