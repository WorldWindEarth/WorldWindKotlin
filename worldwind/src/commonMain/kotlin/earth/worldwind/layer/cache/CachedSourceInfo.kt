package earth.worldwind.layer.cache

/**
 * Cache identity for a tile / feature source — the key under which its bytes live in
 * the cache plus the path of the underlying [earth.worldwind.util.ContentManager]. Layer
 * extensions ([TiledImageLayer.cachedSourceInfo], [TiledElevationCoverage.cachedSourceInfo],
 * etc.) walk the layer's source chain and return this when the layer is wired through a
 * cache. `null` means "no cache wired" — the layer is network-only.
 */
data class CachedSourceInfo(
    val contentKey: String,
    val contentPath: String,
)

/**
 * Mixin for cache-aware sources, factories, and stores that can report their underlying
 * [CachedSourceInfo]. Implementations:
 *   - The per-platform [TileStore] implementations (gpkg, IDB, filesystem).
 *   - [CachedTileSource] delegates to its inner store.
 *   - The elevation source factories (`GpkgCachedElevationSourceFactory` on JVM,
 *     `CachedElevationSourceFactory` on JS / iOS) report from their content / backend.
 *
 * Apps that want a layer's contentKey for bulk-download progress, "save offline" UI, or
 * cache inspection use the layer-side extensions; this interface is the underlying
 * mechanism, not an app-facing surface.
 */
interface CachedSourceInfoProvider {
    val cacheInfo: CachedSourceInfo?
}
