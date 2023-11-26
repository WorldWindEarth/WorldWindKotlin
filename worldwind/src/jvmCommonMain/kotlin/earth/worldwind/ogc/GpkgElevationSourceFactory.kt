package earth.worldwind.ogc

import earth.worldwind.geom.TileMatrix
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.ogc.gpkg.GpkgContent

open class GpkgElevationSourceFactory(protected val tiles: GpkgContent, protected val isFloat: Boolean) : ElevationSourceFactory {
    override fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int): ElevationSource {
        // Convert the WorldWind tile row to the equivalent GeoPackage tile row.
        val gpkgRow = tileMatrix.matrixHeight - row - 1
        return ElevationSource.fromElevationDataFactory(GpkgElevationDataFactory(tiles, tileMatrix.ordinal, column, gpkgRow, isFloat))
    }
}