package earth.worldwind.layer

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Location
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.globe.Globe
import earth.worldwind.layer.cache.RevalidatingSource
import earth.worldwind.layer.mvt.MvtBatchedLineTile
import earth.worldwind.layer.mvt.MvtBatchedPolygonTile
import earth.worldwind.layer.source.CachedFeatureRow
import earth.worldwind.layer.source.DEFAULT_DENSITY
import earth.worldwind.layer.source.DEFAULT_LABEL_VISIBILITY_THRESHOLD
import earth.worldwind.layer.source.TiledFeatureSource
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Polygon
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.util.Level
import earth.worldwind.util.LevelSet
import earth.worldwind.util.Tile
import earth.worldwind.util.TileFactory
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.withPermit

/**
 * Tiled vector-feature layer over a [TiledFeatureSource]. Each viewport fetches only the tiles it
 * needs (per-tile BBOX `GetFeature`), caches them, and renders the features via [FeatureRenderer]
 * (Point → Label/Placemark, LineString → Path, Polygon → Polygon, Multi/GeometryCollection fan out).
 * The cache-through counterpart to [BulkFeatureLayer]: wrap the source in
 * [earth.worldwind.layer.cache.CachedTiledFeatureSource] so memory AND network stay bounded.
 *
 * All the tiling — screen-space-error quadtree selection, the no-overlap cut with ancestor/descendant
 * fallback, the async fetch/cache/backoff/revalidation lifecycle — lives in [TiledVectorLayer]; this
 * class supplies the geographic tile scheme and the WFS-specific load/render. With
 * [useBatchedRendering] (default), a tile's polygons are tessellated per-feature into ONE shared
 * VBO/EBO ([MvtBatchedPolygonTile] fill + [MvtBatchedLineTile] outline) — O(tiles) per frame, correct
 * per-feature colours, and no cross-feature odd-winding cancellation. Always [close] it.
 */
open class TiledFeatureLayer(
    var source: TiledFeatureSource,
    /** Quadtree levels coarser than this aren't fetched/rendered — a single coarse tile's BBOX would
     *  pull too many features. The layer's effective "zoom band" floor. Level `n` spans `180°/2^n`. */
    levelOffset: Int = 0,
    numLevels: Int = 20,
    maxLoadedTiles: Int = 256,
    maxConcurrentFetches: Int = 4,
    var shapeAttributes: ShapeAttributes? = null,
    var autoApplyStyle: Boolean = true,
    var defaultLineColor: Color? = null,
    var defaultFillColor: Color? = null,
    var density: Float = DEFAULT_DENSITY,
    var labelVisibilityThreshold: Double = DEFAULT_LABEL_VISIBILITY_THRESHOLD,
    var defaultAltitudeMode: AltitudeMode? = null,
    /** RDP+radial simplify tolerance, in tile texels (≈ screen px at the tile's level), applied to
     *  each tile's geometry before tessellation; `0.0` disables. WFS `GetFeature` returns
     *  full-resolution geometry at every zoom (unlike pre-simplified MVT), so the default ~1px drops
     *  the sub-pixel detail that would otherwise bloat tile memory and earcut cost without any visible
     *  change. Raise for smaller/faster tiles, lower toward 0 to keep every server vertex. */
    var simplifyTolerancePixels: Double = 1.0,
    /** Tessellate each tile's polygons into one shared VBO/EBO (fill + outline) instead of one
     *  surface shape per feature. O(tiles) per frame; preserves per-feature colours. Set false for
     *  the legacy per-feature path. */
    val useBatchedRendering: Boolean = true,
    var customLogicToApplyProperties: Renderable.(LinkedHashMap<String, Any?>) -> Unit = {},
    /** Default `false`: feature fills are typically TRANSLUCENT, so drawing a coarse ancestor UNDER
     *  the loaded finer tiles double-paints — and worse, the coarse tile (its sub-tile islands dropped
     *  at its zoom) shows solid through the finer tile's island holes, painting islands as water. Set
     *  `true` only for fully OPAQUE feature fills, where the finer tile hides the coarse one. */
    override val progressiveRefinement: Boolean = false,
    /** Default `true`: while a tile loads, fall back to its coarser ancestor so there's no gap. Set
     *  `false` for LINE/sparse feature layers (e.g. roads) where the coarse ancestor's lines show
     *  THROUGH the finer tiles — both resolutions at once. False keeps only the finest loaded tiles
     *  and leaves still-loading cells blank, so detail never doubles up. */
    override val coarseAncestorFallback: Boolean = true,
    displayName: String? = null,
) : TiledVectorLayer<List<Renderable>>(
    // Geographic (Plate-Carrée) pyramid: level 0 is two 180°×180° tiles; each level halves both.
    LevelSet(
        Sector.fromDegrees(-90.0, -180.0, 180.0, 360.0),
        Sector.fromDegrees(-90.0, -180.0, 180.0, 360.0),
        Location.fromDegrees(180.0, 180.0),
        numLevels, 256, 256, levelOffset,
    ), GeographicTileFactory, maxLoadedTiles, maxConcurrentFetches, displayName,
) {
    private var wiredSource: TiledFeatureSource? = null
    // Captured on the render thread, read off-thread to pre-assemble batched tile geometry.
    @Volatile private var capturedGlobe: Globe? = null
    @Volatile private var capturedGlobeState: Globe.State? = null

    override suspend fun loadTileContent(z: Int, x: Int, y: Int, sector: Sector): List<Renderable> {
        // ~1 tile-texel (≈ 1 screen px at the tile's selected level) of RDP+radial tolerance: full-res
        // WFS geometry is mostly sub-pixel at this zoom, so this shrinks each tile ~5-6× without a
        // visible change. Keyed off the tile's own degree span so the tolerance tracks the zoom.
        val simplifyToleranceDeg = if (simplifyTolerancePixels > 0.0)
            sector.deltaLongitude.inDegrees / TILE_TEXELS * simplifyTolerancePixels else 0.0
        val renderer = FeatureRenderer(
            shapeAttributes, autoApplyStyle, defaultLineColor, defaultFillColor,
            density, labelVisibilityThreshold, defaultAltitudeMode, customLogicToApplyProperties,
            simplifyToleranceDeg = simplifyToleranceDeg,
        )
        // Cache hits (tryReadCachedTile) skip the network semaphore; only the round-trip is gated.
        val rows = mutableListOf<CachedFeatureRow>()
        val cached = source.tryReadCachedTile(z, x, y, sector)
        if (cached != null) cached.collect { rows += it }
        else semaphore.withPermit {
            (source.fetchTile(z, x, y, sector) ?: return emptyList()).collect { rows += it }
        }
        val renderables = rows.flatMap { renderer.build(it) }
        return if (useBatchedRendering) batchTile(renderables, sector) else renderables
    }

    /** Pack a tile's polygons into one batched fill tile + one batched outline tile (each polygon
     *  tessellated independently → correct disjoint/hole fill, per-feature colour). Non-polygon
     *  renderables (Paths, Placemarks, Labels) pass through unbatched. */
    private fun batchTile(renderables: List<Renderable>, sector: Sector): List<Renderable> {
        val fills = ArrayList<MvtBatchedPolygonTile.BatchFeature>()
        val strokes = ArrayList<MvtBatchedLineTile.BatchLineFeature>()
        val others = ArrayList<Renderable>()
        for (r in renderables) {
            if (r is Polygon) {
                val attrs = r.attributes
                if (attrs.isDrawInterior) fills += MvtBatchedPolygonTile.BatchFeature(
                    r.getBoundary(0).toFlatDeg(), (1 until r.boundaryCount).map { r.getBoundary(it).toFlatDeg() }, attrs,
                )
                if (attrs.isDrawOutline) for (i in 0 until r.boundaryCount) {
                    strokes += MvtBatchedLineTile.BatchLineFeature(r.getBoundary(i).toFlatDeg(), attrs)
                }
            } else others += r
        }
        if (fills.isEmpty() && strokes.isEmpty()) return others
        val content = ArrayList<Renderable>(others.size + 2)
        // One tile texel ≈ one screen pixel at this tile's level; holes below ~1px² are sub-pixel and
        // dropped, but larger islands always stay holes (an absolute floor, not a fraction of the
        // outer — so a long river no longer fills its visible islands in as water).
        val pxDeg = sector.deltaLongitude.inDegrees / TILE_TEXELS
        if (fills.isNotEmpty()) content += MvtBatchedPolygonTile(fills, sector, minHoleAreaDeg2 = pxDeg * pxDeg).also { tile ->
            capturedGlobe?.let { tile.assemble(it, capturedGlobeState) } // else lazy-assembles on first render
        }
        if (strokes.isNotEmpty()) content += MvtBatchedLineTile(strokes, sector).also { it.assemble(capturedGlobeState) }
        content += others
        return content
    }

    override fun renderTileContent(rc: RenderContext, content: List<Renderable>) {
        for (i in content.indices) content[i].render(rc)
    }

    override fun onContentEvicted(rc: RenderContext, content: List<Renderable>) {
        for (r in content) when (r) {
            is MvtBatchedPolygonTile -> r.releaseRenderResources(rc)
            is MvtBatchedLineTile -> r.releaseRenderResources(rc)
        }
    }

    override fun beginFrame(rc: RenderContext) {
        capturedGlobe = rc.globe
        capturedGlobeState = rc.globeState
        // The source can be swapped (e.g. attachCache); re-wire stale-while-revalidate when it changes.
        val src = source
        if (src === wiredSource) return
        wiredSource = src
        (src as? RevalidatingSource)?.onTileRevalidated = { z, x, y -> invalidateTile(z, x, y) }
    }

    override fun closeSource() { source.close() }

    private class FeatureTile(sector: Sector, level: Level, row: Int, column: Int) : Tile(sector, level, row, column)

    private object GeographicTileFactory : TileFactory {
        override val contentType = "TiledFeatureLayer"
        override fun createTile(sector: Sector, level: Level, row: Int, column: Int): Tile =
            FeatureTile(sector, level, row, column)
    }

    private companion object {
        /** Tile texel dimension — matches the [LevelSet] tile width below; one texel ≈ one screen
         *  pixel at the SSE-selected level, so a per-texel tolerance is a per-pixel tolerance. */
        const val TILE_TEXELS = 256.0
    }
}

/** [Position] boundary → flat `[lon°, lat°, …]` ring for [MvtBatchedPolygonTile.BatchFeature]. */
private fun List<Position>.toFlatDeg(): DoubleArray {
    val out = DoubleArray(size * 2)
    for (i in indices) {
        out[i * 2] = this[i].longitude.inDegrees
        out[i * 2 + 1] = this[i].latitude.inDegrees
    }
    return out
}
