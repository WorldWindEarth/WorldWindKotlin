package earth.worldwind.ogc

import earth.worldwind.geom.TileMatrix
import earth.worldwind.globe.elevation.CacheSourceFactory
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.ogc.gpkg.GpkgContent
import kotlinx.datetime.Instant

open class GpkgElevationSourceFactory(
    protected val geoPackage: GeoPackage,
    protected val content: GpkgContent,
    override val isFloat: Boolean
) : CacheSourceFactory {
    override val contentType = "GPKG"
    override val contentKey get() = content.tableName
    override val contentPath get() = geoPackage.pathName
    override val lastUpdateDate get() = content.lastChange?.let { Instant.fromEpochMilliseconds(it.time) }

    override suspend fun contentSize() = geoPackage.readTilesDataSize(content.tableName)

    @Throws(IllegalStateException::class)
    override suspend fun clearContent(deleteMetadata: Boolean) = if (deleteMetadata) {
        geoPackage.deleteContent(content.tableName)
    } else {
        geoPackage.clearContent(content.tableName)
    }

    override fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int) =
        ElevationSource.fromElevationDataFactory(
            GpkgElevationDataFactory(geoPackage, content, tileMatrix.ordinal, column, row, isFloat)
        )
}