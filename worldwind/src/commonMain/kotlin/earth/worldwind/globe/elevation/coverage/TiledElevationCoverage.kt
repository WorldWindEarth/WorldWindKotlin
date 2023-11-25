package earth.worldwind.globe.elevation.coverage

import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationSourceFactory
import kotlinx.coroutines.CoroutineScope

expect open class TiledElevationCoverage(
    tileMatrixSet: TileMatrixSet, elevationSourceFactory: ElevationSourceFactory
): AbstractTiledElevationCoverage {
    constructor()
    protected val mainScope: CoroutineScope
}