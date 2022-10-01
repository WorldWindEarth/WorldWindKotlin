package earth.worldwind.globe.elevation.coverage

import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationTileFactory
import kotlinx.coroutines.CoroutineScope

expect open class TiledElevationCoverage(
    tileMatrixSet: TileMatrixSet, tileFactory: ElevationTileFactory
): AbstractTiledElevationCoverage {
    constructor()
    protected val mainScope: CoroutineScope
}