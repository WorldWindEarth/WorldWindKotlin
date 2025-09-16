package earth.worldwind.ogc

import earth.worldwind.geom.Sector
import earth.worldwind.layer.mercator.MercatorImageTile
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.ogc.gpkg.GpkgContent
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.image.ImageTile
import earth.worldwind.util.CacheTileFactory
import earth.worldwind.util.Level
import kotlinx.datetime.Instant

open class GpkgTileFactory(
    protected val geoPackage: GeoPackage,
    protected val content: GpkgContent,
    protected val imageFormat: String? = null
): CacheTileFactory {
    override val contentType = "GPKG"
    override val contentKey get() = content.tableName
    override val contentPath get() = geoPackage.pathName

    override suspend fun lastModifiedDate() = Instant.fromEpochMilliseconds(content.lastChange.time)

    override suspend fun contentSize() = geoPackage.readTilesDataSize(content.tableName)

    @Throws(IllegalStateException::class)
    override suspend fun clearContent(deleteMetadata: Boolean) = if (deleteMetadata) {
        geoPackage.deleteContent(content.tableName)
    } else {
        geoPackage.clearContent(content.tableName)
    }

    override fun createTile(sector: Sector, level: Level, row: Int, column: Int) =
        buildTile(sector, level, row, column).apply {
        	// GeoPackage and WorldWind has different tile origin corners, thus tiles bigger than level will be incorrectly aligned.
            // TODO Find solution how to correctly align or transform tiles bigger than level size and remove this restriction.
            if (level.levelHeight >= level.tileHeight) {
                imageSource = getImageSource(level, row, column)?.also { it.postprocessor = this }
            }
        }

    protected open fun buildTile(sector: Sector, level: Level, row: Int, column: Int) = if (sector is MercatorSector) {
        MercatorImageTile(sector, level, row, column)
    } else {
        ImageTile(sector, level, row, column)
    }

    protected open fun getImageSource(level: Level, row: Int, column: Int) =
        // Attempt to find the GeoPackage tile matrix associated with the WorldWind level.
        geoPackage.getTileMatrix(content, level.levelNumber)?.let { tileMatrix ->
            // Convert the WorldWind tile row to the equivalent GeoPackage tile row.
            val gpkgRow = level.levelHeight / level.tileHeight - row - 1
            if (column < tileMatrix.matrixWidth && gpkgRow < tileMatrix.matrixHeight) {
                buildImageSource(geoPackage, content, level.levelNumber, column, gpkgRow, imageFormat)
            } else null
        }
}

expect fun buildImageSource(
    geoPackage: GeoPackage, content: GpkgContent, zoomLevel: Int, column: Int, gpkgRow: Int, imageFormat: String?
): ImageSource