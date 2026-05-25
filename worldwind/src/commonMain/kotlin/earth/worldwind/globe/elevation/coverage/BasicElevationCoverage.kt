package earth.worldwind.globe.elevation.coverage

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.ogc.WmsElevationCoverage

/**
 * NASA's global elevation coverage at 30 m (1 arc-second) resolution, served from
 * [the WorldWind WMS elevation endpoint](https://wms.worldwind.earth/elev).
 *
 * Two construction modes:
 *   * `BasicElevationCoverage()` — network-only. Add to an `ElevationModel` immediately;
 *     bind a persistent cache afterwards via [earth.worldwind.layer.cache.attachCache].
 *   * `BasicElevationCoverage(elevationSourceFactory)` — supply a cache-decorated factory
 *     (typically [earth.worldwind.globe.elevation.TileSourceElevationSourceFactory] over a
 *     [earth.worldwind.layer.cache.CachedTileSource]).
 */
class BasicElevationCoverage : WmsElevationCoverage {
    constructor() : super(SERVICE_ADDRESS, COVERAGE_NAME, OUTPUT_FORMAT, fullSphereSector(), RESOLUTION)
    constructor(elevationSourceFactory: ElevationSourceFactory) : super(
        SERVICE_ADDRESS, COVERAGE_NAME, OUTPUT_FORMAT, buildTileMatrixSet(), elevationSourceFactory
    )

    companion object {
        const val SERVICE_ADDRESS = "https://wms.worldwind.earth/elev"
        const val COVERAGE_NAME = "NASADEM"
        const val OUTPUT_FORMAT = "application/bil16"
        val RESOLUTION: Angle = Angle.fromSeconds(1.0)

        /** Tile pyramid matching the constructor defaults — exposed so cache callers can
         *  open a [earth.worldwind.layer.cache.TileStore] sized to the same matrix. */
        fun buildTileMatrixSet(): TileMatrixSet =
            TileMatrixSet.fromTilePyramid(fullSphereSector(), 4, 2, 256, 256, RESOLUTION)

        private fun fullSphereSector(): Sector = Sector().setFullSphere()
    }
}
