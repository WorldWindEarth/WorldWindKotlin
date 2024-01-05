package earth.worldwind.ogc

import earth.worldwind.geom.TileMatrix
import earth.worldwind.globe.elevation.CacheSourceFactory
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.ogc.gpkg.GpkgContent

open class GpkgElevationSourceFactory(protected val tiles: GpkgContent, protected val isFloat: Boolean) : CacheSourceFactory {
    override val contentKey get() = tiles.tableName
    override val lastUpdateDate get() = tiles.lastChange
    override val boundingSector get() = tiles.container.getBoundingSector(tiles)

    override suspend fun contentSize() = tiles.container.readTilesDataSize(tiles.tableName)

    @Throws(IllegalStateException::class)
    override suspend fun clearContent(deleteMetadata: Boolean) = if (deleteMetadata) {
        tiles.container.deleteContent(tiles.tableName)
    } else {
        tiles.container.clearContent(tiles.tableName)
    }

    override fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int) =
        ElevationSource.fromElevationDataFactory(GpkgElevationDataFactory(tiles, tileMatrix.ordinal, column, row, isFloat))
}