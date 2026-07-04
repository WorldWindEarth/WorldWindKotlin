package earth.worldwind.layer

/**
 * Marker interface for layers that render vector data (lines, polygons, points, labels)
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
 * Intentionally empty — the underlying source interfaces ([earth.worldwind.layer.source.TileSource]
 * for MVT bytes, [earth.worldwind.layer.source.BulkFeatureSource] for WFS/Shapefile,
 * [earth.worldwind.layer.source.TiledFeatureSource] for tile-addressed features) are
 * structurally too different to share a useful method set without losing type safety.
 */
interface VectorLayer : Layer {
    /** Makes a fresh copy carrying only this layer's configuration; caches and fetch scope are not shared. */
    fun clone(): VectorLayer
}
