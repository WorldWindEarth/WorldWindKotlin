package earth.worldwind.globe.elevation

import earth.worldwind.geom.TileMatrix
import earth.worldwind.layer.source.TileSource

/**
 * Adapter that lets an [ElevationSource] carry a [TileSource] reference + decode format.
 *
 * Lives on every platform — each [earth.worldwind.globe.elevation.coverage.TiledElevationCoverage]
 * actual unwraps the ref via `elevationSource.asUnrecognized() is TileSourceElevationRef`,
 * fetches bytes through [source]'s `fetchTile(z, x, y)`, then dispatches the bytes to the
 * platform's existing elevation decoder using [outputFormat] (e.g. `application/bil16`,
 * `application/bil32`, `image/tiff`).
 *
 * Decouples "where do the bytes come from" (a [TileSource], optionally cached by
 * [earth.worldwind.layer.cache.CachedTileSource]) from "how are the bytes turned into a
 * [ShortArray]" (per-platform decoder, dispatched by [outputFormat]).
 */
data class TileSourceElevationRef(
    val source: TileSource,
    val z: Int,
    val x: Int,
    val y: Int,
    val outputFormat: String,
)

/**
 * [ElevationSourceFactory] that adapts a [TileSource] for the elevation pipeline. Each
 * `createElevationSource(matrix, row, col)` returns an [ElevationSource] whose raw `source`
 * is a [TileSourceElevationRef]; the platform's elevation retrieval path detects this and
 * fetches bytes through [source].
 *
 * Works on every platform — JVM/Android, iOS, JS. Wrap [source] in a
 * [earth.worldwind.layer.cache.CachedTileSource] for cache-first reads with write-through.
 */
class TileSourceElevationSourceFactory(
    val source: TileSource,
    val outputFormat: String,
    override val contentType: String = "TileSource",
) : ElevationSourceFactory {

    override fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int): ElevationSource =
        ElevationSource.fromUnrecognized(
            TileSourceElevationRef(source, tileMatrix.ordinal, column, row, outputFormat)
        )
}
