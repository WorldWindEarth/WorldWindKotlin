package earth.worldwind.ogc

import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrix
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.globe.elevation.ElevationTileFactory
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage

/**
 * Generates elevations from OGC Web Map Service (WMS) version 1.3.0.
 */
open class WmsElevationCoverage(
    serviceAddress: String, coverage: String, imageFormat: String, sector: Sector = Sector().setFullSphere(), numLevels: Int = 13
): TiledElevationCoverage(
    buildTileMatrixSet(sector, numLevels),
    buildTileFactory(serviceAddress, coverage, imageFormat)
) {
    companion object {
        /**
         * 4x2 top level matrix equivalent to 90 degree top level tiles
         */
        private fun buildTileMatrixSet(sector: Sector, numLevels: Int) = TileMatrixSet.fromTilePyramid(
            sector, 4, 2, 256, 256, numLevels
        )

        private fun buildTileFactory(serviceAddress: String, coverage: String, imageFormat: String): ElevationTileFactory {
            val layerConfig = WmsLayerConfig(serviceAddress, coverage).apply { this.imageFormat = imageFormat }
            val wmsTileFactory = WmsTileFactory(layerConfig)
            return object : ElevationTileFactory {
                override fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int): ElevationSource {
                    val tileSector = tileMatrix.tileSector(row, column)
                    val urlString = wmsTileFactory.urlForTile(tileSector, tileMatrix.tileWidth, tileMatrix.tileHeight)
                    return ElevationSource.fromUrlString(urlString)
                }
            }
        }
    }
}