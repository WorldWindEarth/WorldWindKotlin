package earth.worldwind.globe.elevation

import earth.worldwind.geom.TileMatrix

interface ElevationTileFactory {
    fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int): ElevationSource
}