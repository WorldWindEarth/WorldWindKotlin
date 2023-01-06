package earth.worldwind.ogc

import earth.worldwind.geom.TileMatrix
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.globe.elevation.ElevationTileFactory
import earth.worldwind.ogc.gpkg.GpkgContent

open class GpkgElevationTileFactory(protected val tiles: GpkgContent, protected val isFloat: Boolean) : ElevationTileFactory {
    override fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int): ElevationSource {
        // Convert the WorldWind tile row to the equivalent GeoPackage tile row.
        val gpkgRow = tileMatrix.matrixHeight - row - 1
        return ElevationSource.fromElevationFactory(GpkgElevationFactory(tiles, tileMatrix.ordinal, column, gpkgRow, isFloat))
    }
}