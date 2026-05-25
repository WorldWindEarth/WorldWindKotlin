# Migration to WorldWindKotlin 3.0 (Cache Architecture v3)

The 3.0 release replaces the per-layer-type cache API with a uniform
source-decorator design. Pre-3.0 GeoPackage files remain wire-compatible —
the `gpkg_web_service` schema is unchanged, so existing offline atlases keep
working — but caller code that touched the old `setupXxxCache` /
`getXxxLayers` / `GpkgTileFactory` surface needs to migrate.

This guide maps every removed type to its v3 replacement and shows the two
canonical user-facing patterns.

## Two patterns to remember

### Pattern A — Online start, then bind cache

```kotlin
val layer = WmsLayerFactory.createLayer(
    serviceAddress = "https://neo.gsfc.nasa.gov/wms/wms",
    layerNames = listOf("MOD_LSTD_CLIM_M"),
)
layer.attachCache(contentManager, "WMS_NeoTemperature")
engine.layers.addLayer(layer)
```

For **WMS / WMTS / WCS 2.0.1**, the layer or coverage can't be constructed without
a metadata fetch (GetCapabilities for WMS/WMTS, DescribeCoverage for WCS 2.0.1) —
level-set, image format, axis labels, bounding box etc. all come from the response.
The metadata fetch happens at factory time, before `attachCache` could run. Pass
`contentManager` + `contentKey` to the factory and it does the cache-first metadata
read AND calls `attachCache` internally:

```kotlin
WmsLayerFactory.createLayer(
    serviceAddress = "https://neo.gsfc.nasa.gov/wms/wms",
    layerNames = listOf("MOD_LSTD_CLIM_M"),
    contentManager = contentManager,
    contentKey = "WMS_NeoTemperature",
)
// Cache already attached on return — first launch persists capabilities, every
// subsequent launch reads them back and never needs the server.
```

If you really want network-only construction (tests, app-side caching scheme), omit
the cm/key pair and the factory hits `GetCapabilities` every time.

Every cacheable layer / coverage type has the same `Layer.attachCache(cm, key)`
extension. It:

1. Opens (or re-opens) the cache store via the [ContentManager].
2. Wraps the layer's network source in the matching `CachedTileSource` /
   `CachedBulkFeatureSource` / `CachedTiledFeatureSource` decorator, swapping
   the live `tileFactory` / `elevationSourceFactory` / `source` in place.
3. Registers a `gpkg_web_service` row so the layer can be rediscovered via
   `ContentManager.listCachedSources()` on the next launch.

### Pattern B — Offline replay from cache

```kotlin
val handles = contentManager.listCachedSources()
val handle = handles.firstOrNull { it.contentKey == "WMS_NeoTemperature" }
val layer = handle?.let { contentManager.openCachedLayer(it) }
if (layer is Layer) engine.layers.addLayer(layer)
```

`ContentManager.openCachedLayer(handle, offlineOnly = false)` rebuilds a layer
or coverage from a persisted handle. Pass `offlineOnly = true` to wrap with a
cache decorator whose inner source is `null` — strict cache-only mode.

Service-type dispatch goes through `CachedLayerRegistry`. The built-in
reconstructors cover WMS / WMTS / XYZ / MVT / WCS 1.0 / WCS 2.0 / WFS /
Shapefile / OSM Buildings; register custom types via
`CachedLayerRegistry.register(dataType, serviceType) { cm, handle, offline -> … }`.

## Bulk attach for app startup

A real app typically wires 5–20 layers; the per-layer `mainScope.launch` sites
add up. Use `ContentManager.attachAll(scope) { … }`:

```kotlin
val cacheJob = contentManager.attachAll(mainScope) {
    bind(satellite, "GSat")
    bind(elevation)                       // BasicElevationCoverage uses COVERAGE_NAME default
    bind(wmsLayer, "WMS_NeoTemperature")
    bind(mvt, "MVT_Versatiles")
}
```

Sequential binds inside one launch; per-bind failures log WARN and don't abort
the remaining binds.

## Removed types → v3 replacement

| Pre-3.0 | v3 |
|---------|---|
| `GpkgContentManager.getImageLayers(keys)` | `cm.listCachedSources().filter { it.dataType == DataType.TILES }.mapNotNull { cm.openCachedLayer(it) }` |
| `GpkgContentManager.getElevationCoverages(keys)` | same, filter on `DataType.COVERAGE` |
| `GpkgContentManager.setupImageLayerCache(layer, key)` | `layer.attachCache(cm, key)` |
| `GpkgContentManager.setupFeatureLayerCache(layer, key)` | `layer.attachCache(cm, key)` |
| `GpkgContentManager.setupVectorTileLayerCache(layer, key)` | `layer.attachCache(cm, key)` |
| `GpkgContentManager.setupElevationCoverageCache(cov, key)` | `coverage.attachCache(cm, key)` |
| `GpkgTileFactory` | `TileSourceFactoryAdapter` over a `CachedTileSource` (constructed by `attachCache`) |
| `GpkgElevationSourceFactory` | `TileSourceElevationSourceFactory` over a `CachedTileSource` |
| `GpkgFeatureCacheFactory` | `GpkgFeatureStore` accessed via `cm.openFeatureStore(key)` |
| `CachedWfsFeatureLayer` | `WfsFeatureLayer(source = WfsBulkFeatureSource(...))` + `attachCache(cm, key)` |
| `CachedShapefileLayer` | `WfsFeatureLayer(source = ShapefileBulkFeatureSource(...))` + `attachCache(cm, key)` |
| `CachedOsmBuildingsLayer` | `OsmBuildingsLayer(source = OverpassBuildingsSource(...))` + `attachCache(cm, key)` |
| `CacheableImageLayer` / `CacheableFeatureLayer` / `CacheableVectorTileLayer` / `CacheableElevationCoverage` | Marker interfaces removed — any `Layer` / `ElevationCoverage` with a known source can be cached via `attachCache` |
| `WebFeatureCacheFactory` / `WebVectorTileCacheFactory` | Folded into `ContentManager.openFeatureStore` / `openVectorTileStore` |
| `WmsLayerFactory.createLayer(... contentManager, contentKey ...)` | `WmsLayerFactory.createLayer(...)` (network-only) + `layer.attachCache(cm, key)` |
| `WmtsLayerFactory.createLayer(... contentManager, contentKey ...)` | `WmtsLayerFactory.createLayer(...)` (network-only) + `layer.attachCache(cm, key)` |
| `ContentManager.attachMvtCache(...)` / `attachWfsCache` / `attachShapefileCache` / `attachOsmBuildingsCache` | Replaced by `MvtVectorLayer.attachCache(cm, key)` / `WfsFeatureLayer.attachCache(cm, key)` / `OsmBuildingsLayer.attachCache(cm, key)` (uniform Layer receiver) |

## Cache eviction

`CacheEvictionPolicy` (max bytes / max age / max entries) attaches to
individual stores via the `evictionPolicy` argument on `openImageTileStore` /
`openCoverageStore` / etc. All three backends (GPKG, IndexedDB, filesystem)
call `evict()` on store-open when the policy is bounded. Defaults to
`CacheEvictionPolicy.UNBOUNDED`.

To delete one content key entirely (tiles + features + metadata):

```kotlin
contentManager.deleteContent("Shapefile_Countries")
```

## Cross-platform contract changes

| Capability | Pre-3.0 | v3 |
|-----------|---------|---|
| `lastModifiedDate()` | JVM only | All platforms; tracks per-content writes on JS / iOS |
| `boundingSector` on `CachedSourceHandle` | JVM only (from `gpkg_contents`) | Still JVM-only; JS / iOS return `null` (geom encoding pending) |
| `data_type` mismatch validation on reopen | JVM only | All platforms — JS uses a sidecar object store, iOS uses `_data_type.txt` per key |
| Eviction-on-open | JVM only | All platforms |

## What's still on JVM only

`ContentManager.openCachedLayer` lives in `jvmCommonMain` because the WFS arm
needs `mil.nga.sf` for geometry decoding. JS and iOS callers can still inspect
`listCachedSources()` / `findCachedSource()` and hand-build a layer per
handle's service type. Promoting the dispatcher to commonMain (with an
expect/actual for the WFS arm only) is a future task — the registry pattern
in `CachedLayerRegistry` already makes the dispatch portable; only the
built-in feature reconstructors are JVM-specific.

## Wire compatibility

- `gpkg_web_service` schema is unchanged — same `(table_name, type, address,
  layer_name, output_format, metadata, is_transparent)` columns.
- Full GetCapabilities XML persisted for WMS / WMTS (matches pre-3.0).
- WCS 2.0 persists the per-coverage `DescribeCoverage` XML (matches pre-3.0).
- WCS 1.0 stores no metadata (matches pre-3.0) — pyramid is reconstructed
  from `gpkg_tile_matrix_set` on reopen.
- WMS image vs WMS elevation collision (both `type = "WMS"`) is resolved by
  dispatching on `(gpkg_contents.data_type, gpkg_web_service.type)` — same
  approach pre-3.0's `getImageLayers` / `getElevationCoverages` used.

## Common porting pitfalls

- **Old cache + new code** — clear `~/.cache/worldwind-tutorials/cache_content.gpkg`
  (JVM) or the equivalent on your platform if you see "Coordinate systems not
  compatible" or "Tile size mismatch" errors on reopen. Pre-3.0 wrote a
  *filtered* WMS capabilities XML which loses the root-layer CRS list — v3
  writes the full capabilities, but a cache populated by pre-3.0 still has
  the filtered version. Easiest fix: delete the cache once.
- **Surface line shapes dimming after a 3D layer** — fixed in
  `DrawableSurfaceShape: reset enableLighting uniform per draw` (commit
  `18f40d44`). If you see surface shapes render at ~35% intensity after an
  `OsmBuildingsLayer` / other lit layer renders, ensure that commit is in
  your branch.
- **`attachCache` returns before tiles render** — that's expected.
  `attachCache` is synchronous about the cache wiring (one store-open + one
  metadata write); tile fetches still go through the renderer's tile-request
  pipeline on first frame.
- **Tutorial coroutine handlers** — `try { … } catch (Throwable) { … }` will
  swallow `CancellationException` and break structured concurrency. v3's
  tutorial loaders use `try / catch (CancellationException) { throw e } /
  catch (Throwable) { log }` — adopt the same pattern in app code.
