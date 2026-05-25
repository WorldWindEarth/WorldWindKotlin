package earth.worldwind.layer.cache

import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.layer.VectorLayer

/**
 * Typed key for bulk-opening cached content by category. The phantom [T] carries the
 * return type all the way from the call site through [earth.worldwind.util.open], so
 * `cm.open(CacheCategory.Tiles)` returns `List<TiledImageLayer>` with no `as?` cast and
 * no reified type argument at the call site.
 *
 * Mapping to the on-disk [CacheEntry.DataType]:
 *
 * | Category      | Data type      | Returned layer/coverage type    | Concrete subtypes          |
 * | ------------- | -------------- | ------------------------------- | -------------------------- |
 * | [Tiles]       | `TILES`        | [TiledImageLayer]               | WMS / WMTS / WebMercator   |
 * | [VectorTiles] | `VECTOR_TILES` | [VectorLayer]                   | MVT                        |
 * | [Features]    | `FEATURES`     | [VectorLayer]                   | WFS / Shapefile / OSM      |
 * | [Coverage]    | `COVERAGE`     | [TiledElevationCoverage]        | BasicElevation / WMS / WCS |
 *
 * For narrower filters than a whole category (e.g. only WMS image layers), keep the
 * returned list and add a `.filterIsInstance<WmsImageLayer>()` — there's no per-subtype
 * key, because `CacheCategory` is intentionally coarse.
 */
sealed class CacheCategory<out T : Any>(internal val dataType: CacheEntry.DataType) {
    /** Image tile pyramids: WMS / WMTS / WebMercator. Returns [TiledImageLayer]. */
    object Tiles : CacheCategory<TiledImageLayer>(CacheEntry.DataType.TILES)

    /** Mapbox Vector Tiles. Returns [VectorLayer]. */
    object VectorTiles : CacheCategory<VectorLayer>(CacheEntry.DataType.VECTOR_TILES)

    /** Vector features — WFS, Shapefile, OSM Buildings. Returns [VectorLayer]. */
    object Features : CacheCategory<VectorLayer>(CacheEntry.DataType.FEATURES)

    /** Gridded elevation coverages. Returns [TiledElevationCoverage]. */
    object Coverage : CacheCategory<TiledElevationCoverage>(CacheEntry.DataType.COVERAGE)
}
