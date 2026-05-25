package earth.worldwind.layer.cache

import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.layer.buildings.OsmBuildingsLayer
import earth.worldwind.layer.mvt.MvtVectorLayer
import earth.worldwind.layer.BulkFeatureLayer

/**
 * Cache identity of [this] layer's tile source, or `null` when the layer isn't wired
 * through a cache (network-only). Walks the layer's tile factory chain, looking for a
 * [CachedSourceInfoProvider] — typically a [CachedTileSource] that wraps an IDB / gpkg /
 * filesystem [TileStore].
 *
 * Apps use this for cache-aware UI: showing "size on disk" for the right gpkg row,
 * threading the right contentKey through a bulk-download progress notification, etc.
 * Pre-2.0 layers exposed a `contentKey: String?` property directly; this is the v2.0
 * source-decorator equivalent.
 */
val TiledImageLayer.cachedSourceInfo: CachedSourceInfo?
    get() {
        val source = (tiledSurfaceImage?.tileFactory as? TileSourceFactoryAdapter)?.source
        return (source as? CachedSourceInfoProvider)?.cacheInfo
    }

/** See [TiledImageLayer.cachedSourceInfo] — same shape for elevation coverages. The
 *  cache provider here is the elevation source factory, not a tile-source decorator. */
val TiledElevationCoverage.cachedSourceInfo: CachedSourceInfo?
    get() = (elevationSourceFactory as? CachedSourceInfoProvider)?.cacheInfo

/** See [TiledImageLayer.cachedSourceInfo] — same shape for MVT vector-tile layers. */
val MvtVectorLayer.cachedSourceInfo: CachedSourceInfo?
    get() = (source as? CachedSourceInfoProvider)?.cacheInfo

/** See [TiledImageLayer.cachedSourceInfo] — same shape for WFS feature layers. */
val BulkFeatureLayer.cachedSourceInfo: CachedSourceInfo?
    get() = (source as? CachedSourceInfoProvider)?.cacheInfo

/** See [TiledImageLayer.cachedSourceInfo] — same shape for OSM buildings. */
val OsmBuildingsLayer.cachedSourceInfo: CachedSourceInfo?
    get() = (source as? CachedSourceInfoProvider)?.cacheInfo
