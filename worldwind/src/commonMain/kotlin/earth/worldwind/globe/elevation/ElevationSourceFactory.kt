package earth.worldwind.globe.elevation

import earth.worldwind.geom.TileMatrix
import earth.worldwind.globe.elevation.coverage.ElevationImage

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
 * one uncached tile can't stall cached neighbours behind it. Returns a fully built
 * [ElevationImage] on a cache hit, or `null` on a cache miss / decode failure.
 *
 * Implementations must do the decode AND the [ElevationImage] min/max/missing scan off the
 * render thread (inside their existing cache-read dispatch), so the caller resumes on the
 * main thread with a ready image and only pays a single coroutine switch per cached tile.
 */
interface CacheReadableElevationSourceFactory {
    suspend fun readCachedTileImage(tileMatrix: TileMatrix, row: Int, column: Int): ElevationImage?
}