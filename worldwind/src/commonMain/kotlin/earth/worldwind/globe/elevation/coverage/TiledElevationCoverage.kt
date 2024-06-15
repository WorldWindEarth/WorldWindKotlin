package earth.worldwind.globe.elevation.coverage

import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationSourceFactory

expect open class TiledElevationCoverage(
    tileMatrixSet: TileMatrixSet, elevationSourceFactory: ElevationSourceFactory
): AbstractTiledElevationCoverage {
    open fun clone(): TiledElevationCoverage
}