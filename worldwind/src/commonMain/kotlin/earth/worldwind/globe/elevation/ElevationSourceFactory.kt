package earth.worldwind.globe.elevation

import earth.worldwind.geom.TileMatrix

interface ElevationSourceFactory {
    /**
     * Unique elevation source factory content type name
     */
    val contentType: String

    fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int): ElevationSource
}

/**
 * Optional capability for cache-backed [ElevationSourceFactory]s: read one tile from the
 * local cache only, never touching the network. Lets a coverage serve cached tiles without
 * spending a slot in the (small) network-retrieval budget, so a slow network DEM fetch for
 * one uncached tile can't stall cached neighbours behind it. Returns the rendered
 * `ShortArray` on a cache hit, or `null` on a cache miss / decode failure.
 */
interface CacheReadableElevationSourceFactory {
    suspend fun readCachedTileArray(tileMatrix: TileMatrix, row: Int, column: Int): ShortArray?
}