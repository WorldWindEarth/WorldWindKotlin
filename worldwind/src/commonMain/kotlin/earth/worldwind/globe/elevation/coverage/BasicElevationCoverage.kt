package earth.worldwind.globe.elevation.coverage

import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrix
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.globe.elevation.ElevationTileFactory
import earth.worldwind.ogc.WmsLayerConfig
import earth.worldwind.ogc.WmsTileFactory

/**
 * Displays NASA's global elevation coverage at 30m resolution and 900m resolution on the ocean floor,
 * all from an OGC Web Map Service (WMS). By default, BasicElevationCoverage is configured to
 * retrieve elevation coverage from the WMS at [&amp;https://wms.worldwind.earth/elev](https://wms.worldwind.earth/elev?SERVICE=WMS&amp;REQUEST=GetCapabilities).
 */
open class BasicElevationCoverage: TiledElevationCoverage(tileMatrixSet, tileFactory) {
    companion object {
        /**
         * 4x2 top level matrix equivalent to 90 degree top level tiles
         */
        private val tileMatrixSet get() = TileMatrixSet.fromTilePyramid(
            Sector().setFullSphere(), 4, 2, 256, 256, 13
        )

        private val tileFactory: ElevationTileFactory get() {
            val layerConfig = WmsLayerConfig(
                "https://wms.worldwind.earth/elev", "SRTM-CGIAR,GEBCO"
            ).apply { imageFormat = "application/bil16" }
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