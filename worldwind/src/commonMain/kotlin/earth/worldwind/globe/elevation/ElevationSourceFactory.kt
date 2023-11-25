package earth.worldwind.globe.elevation

import earth.worldwind.geom.TileMatrix

interface ElevationSourceFactory {
    fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int): ElevationSource
}