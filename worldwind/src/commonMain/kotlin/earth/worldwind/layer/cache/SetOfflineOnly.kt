@file:OptIn(LowLevelCacheApi::class)

package earth.worldwind.layer.cache

import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.layer.VectorLayer
import earth.worldwind.layer.buildings.OsmBuildingsLayer
import earth.worldwind.layer.mvt.MvtVectorLayer
import earth.worldwind.layer.BulkFeatureLayer

/**
 * Toggle the cache-only flag on a cache-attached layer or coverage.
 *
 * When `true`, the cache decorator's `fetchTile` / `fetchAll` returns `null` on every
 * cache miss without consulting the underlying network source. When `false`, cache
 * misses fall back to the network source. The flag is a runtime property of the cache
 * decorator (see [OfflineToggleable.isCacheOnly]) — flip it at any time, in either
 * direction, after [attachCache] or [earth.worldwind.util.openLayer] /
 * [earth.worldwind.util.openElevationCoverage].
 *
 * No-op when the target isn't currently cache-attached (its source isn't an
 * [OfflineToggleable]). Idempotent.
 */
fun TiledImageLayer.setOfflineOnly(offlineOnly: Boolean = true) {
    val source = (tiledSurfaceImage?.tileFactory as? TileSourceFactoryAdapter)?.source
    (source as? OfflineToggleable)?.isCacheOnly = offlineOnly
}

/** See [setOfflineOnly] on [TiledImageLayer]. Dispatches by [VectorLayer] subtype to
 *  reach the right source field. No-op for unknown subtypes. */
fun VectorLayer.setOfflineOnly(offlineOnly: Boolean = true) {
    val source: Any? = when (this) {
        is MvtVectorLayer -> source
        is BulkFeatureLayer -> source
        is OsmBuildingsLayer -> source
        else -> null
    }
    (source as? OfflineToggleable)?.isCacheOnly = offlineOnly
}

/** See [setOfflineOnly] on [TiledImageLayer]. The `OfflineToggleable` lives on the
 *  coverage's `elevationSourceFactory` (the platform-specific
 *  `CachedElevationSourceFactory` / `GpkgCachedElevationSourceFactory` both implement
 *  it). No-op when the coverage is wired to a network-only factory. */
fun TiledElevationCoverage.setOfflineOnly(offlineOnly: Boolean = true) {
    (elevationSourceFactory as? OfflineToggleable)?.isCacheOnly = offlineOnly
}
