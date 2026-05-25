package earth.worldwind.globe.elevation.coverage

import earth.worldwind.geom.TileMatrixSet

/**
 * Marker for elevation coverages backed by a tile pyramid. Cache integration is composed
 * externally — wrap the elevation source with a cache-aware decorator (mirrors the
 * [earth.worldwind.layer.cache.CachedTileSource] pattern used by image layers).
 */
interface CacheableElevationCoverage : ElevationCoverage {
    /** Tile matrix which defines elevation coverage area. */
    val tileMatrixSet: TileMatrixSet

    /** Bounding sector of the elevation coverage. */
    val boundingSector get() = sector
}
