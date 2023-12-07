package earth.worldwind.ogc

import earth.worldwind.geom.TileMatrix
import earth.worldwind.globe.elevation.CacheSourceFactory
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.ogc.gpkg.GpkgContent

open class GpkgElevationSourceFactory(protected val tiles: GpkgContent, protected val isFloat: Boolean) : CacheSourceFactory {
    override var isWritable: Boolean
        get() = !tiles.isReadOnly
        set(value) { tiles.isReadOnly = !value }

    @Throws(IllegalStateException::class)
    override suspend fun clearContent(deleteMetadata: Boolean) = if (deleteMetadata) {
        tiles.container.deleteContent(tiles.tableName)
    } else {
        tiles.container.clearContent(tiles.tableName)
    }

    override fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int): ElevationSource {
        // Convert the WorldWind tile row to the equivalent GeoPackage tile row.
        val gpkgRow = tileMatrix.matrixHeight - row - 1
        return ElevationSource.fromElevationDataFactory(GpkgElevationDataFactory(tiles, tileMatrix.ordinal, column, gpkgRow, isFloat))
    }
}