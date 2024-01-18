package earth.worldwind.globe.elevation

import earth.worldwind.geom.TileMatrix

interface ElevationSourceFactory {
    /**
     * Unique elevation source factory content type name
     */
    val contentType: String

    fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int): ElevationSource
}