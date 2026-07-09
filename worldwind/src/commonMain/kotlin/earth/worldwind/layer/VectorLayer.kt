package earth.worldwind.layer

import earth.worldwind.geom.Sector

/**
 * Common interface for layers that render vector data (lines, polygons, points, labels)
 * rather than rasterized image tiles. Implemented by:
 *   * [earth.worldwind.layer.mvt.MvtVectorLayer] — Mapbox Vector Tiles (byte-tile vector).
 *   * [earth.worldwind.layer.BulkFeatureLayer] — WFS / Shapefile / any bulk feature source.
 *   * [earth.worldwind.layer.buildings.OsmBuildingsLayer] — Overpass-driven tiled 3D buildings.
 *
 * Symmetric with [TiledImageLayer] (the marker for raster tile layers). Use it to filter:
 *
 * ```
 * engine.layers.filterIsInstance<VectorLayer>()
 * contentManager.openLayers<VectorLayer>()                  // all cached vector layers
 * contentManager.openLayer<VectorLayer>("MyKey")            // narrow by category
 * ```
 *
 * Deliberately thin — the underlying source interfaces ([earth.worldwind.layer.source.TileSource]
 * for MVT bytes, [earth.worldwind.layer.source.BulkFeatureSource] for WFS/Shapefile,
 * [earth.worldwind.layer.source.TiledFeatureSource] for tile-addressed features) are
 * structurally too different to share a useful method set without losing type safety.
 */
interface VectorLayer : Layer {
    /**
     * Vector content bounding sector — real data availability can be smaller than the tile grid.
     * Full sphere by default; tiled layers skip tiles outside it. Cache reopen narrows it from
     * the persisted content bbox; bulk download widens and persists it. Mirrors
     * [earth.worldwind.globe.elevation.coverage.ElevationCoverage.sector].
     */
    val sector: Sector

    /** Makes a fresh copy carrying only this layer's configuration; caches and fetch scope are not shared. */
    fun clone(): VectorLayer
}
