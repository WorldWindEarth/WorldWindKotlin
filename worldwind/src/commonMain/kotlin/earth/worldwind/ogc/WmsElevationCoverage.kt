package earth.worldwind.ogc

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrix
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.globe.elevation.ElevationTileFactory
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage

/**
 * Generates elevations from OGC Web Map Service (WMS) version 1.3.0.
 *
 * @param serviceAddress OGC Web Map Service (WMS) server address
 * @param coverage comma-separated coverage names
 * @param imageFormat required image format
 * @param sector bounding sector
 * @param resolution the target resolution in angular value of latitude per texel
 */
open class WmsElevationCoverage(
    serviceAddress: String, coverage: String, imageFormat: String, sector: Sector, resolution: Angle
): TiledElevationCoverage(
    buildTileMatrixSet(sector, resolution), buildTileFactory(serviceAddress, coverage, imageFormat)
) {
    companion object {
        /**
         * 4x2 top level matrix equivalent to 90 degree top level tiles
         *
         * @param sector bounding sector
         * @param resolution the target resolution in angular value of latitude per texel
         */
        private fun buildTileMatrixSet(sector: Sector, resolution: Angle) = TileMatrixSet.fromTilePyramid(
            sector, 4, 2, 256, 256, resolution
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