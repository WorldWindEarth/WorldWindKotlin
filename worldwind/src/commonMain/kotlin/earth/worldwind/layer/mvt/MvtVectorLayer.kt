package earth.worldwind.layer.mvt

import earth.worldwind.WorldWind
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.globe.Globe
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Offset
import earth.worldwind.geom.OffsetMode
import earth.worldwind.geom.Vec3
import earth.worldwind.util.Tile
import earth.worldwind.layer.cache.RevalidatingSource
import earth.worldwind.layer.buildings.OsmBuilding
import earth.worldwind.layer.buildings.OsmBuildingsTile
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.layer.mercator.MercatorTiledVectorLayer
import earth.worldwind.layer.shadow.ShadowMode
import earth.worldwind.layer.source.TileSource
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Renderable
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.Label
import earth.worldwind.shape.Path
import earth.worldwind.shape.PathType
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkAttributes
import earth.worldwind.shape.Polygon
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import kotlinx.coroutines.CancellationException
import earth.worldwind.util.FloatList
import earth.worldwind.util.IntList
import earth.worldwind.util.LongList
import earth.worldwind.util.PrioritySemaphore
import earth.worldwind.util.withPermit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sinh
import kotlin.concurrent.Volatile
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Renders Mapbox Vector Tile (MVT) data as flat surface-draped polygons and polylines.
 *
 * Pipeline: HTTP fetch → protobuf decode → command-stream geometry → Web-Mercator
 * unprojection → batched surface drawables (one VBO+EBO per tile per [Globe.State]).
 *
 * Tile management — Web-Mercator screen-space-error quadtree selection, the no-overlap cut with
 * ancestor/descendant fallback, LRU + backoff + revalidation — is owned by [MercatorTiledVectorLayer]
 * / [earth.worldwind.layer.TiledVectorLayer]. This class supplies only the MVT specifics: tile
 * decode + [toRenderables], zoom-interpolated line widths, and the cross-tile label collision pass.
 *
 * ```
 * val mvt = MvtVectorLayer(
 *     source = UrlTemplateMvtTileSource("https://tiles.example.com/v3/{z}/{x}/{y}.pbf"),
 *     style = OpenTopoMapRules,
 * )
 * worldWindow.engine.layers.addLayer(mvt)
 * // ...later:
 * mvt.close()
 * ```
 */
open class MvtVectorLayer(
    var source: TileSource,
    /**
     * Minimum slippy-map zoom the layer will request. Below this the layer stops fetching —
     * features at lower zooms are unreadable at globe-spanning distances and the imagery basemap
     * covers the same role. Becomes the quadtree's fetch floor (`levelOffset`).
     */
    val minZoom: Int = 0,
    /**
     * Maximum slippy-map zoom the layer will request. Most OpenMapTiles-schema servers stop
     * publishing past z14. Becomes the quadtree's deepest level.
     */
    val maxZoom: Int = 14,
    // Resident tile-geometry cap (~0.7 MB vertex/element arrays each); 256 holds the working set +
    // ancestors without OOMing the heap (1024 OOM'd; 128 thrashed in-view tiles → GC storm).
    maxLoadedTiles: Int = 256,
    // Network round-trips in flight; 4 fills a pan without hammering the server.
    maxConcurrentFetches: Int = 4,
    // CPU concurrency for the decode + tessellation stage, bounded so prep can't starve render/main.
    maxConcurrentAssembly: Int = 4,
    /** When true (default), ring/line geometry is RDP+radial simplified at decode; false passes raw verts through. */
    val simplifyGeometry: Boolean = true,
    /**
     * Geometry is simplified only at tile zoom <= this (globe/regional scale, where fine detail is
     * sub-pixel and full-detail continental geometry would OOM); at higher zoom (city/street scale)
     * features render full-detail. Applies to both polygon fills and lines.
     */
    val simplifyMaxZoom: Int = 12,
    /**
     * When true (default), POLYGON features in a tile are packed into one [MvtBatchedPolygonTile]
     * and LINESTRING features into one [MvtBatchedLineTile] — one VBO + one EBO per tile per
     * [Globe.State], sub-draws bucketed by (color, width). Set false to fall back to per-feature
     * [Polygon]/[Path] instances — required when overriding [toRenderables] to attach per-feature
     * pick metadata or unique attribute mutations the batched path doesn't carry.
     */
    val useBatchedRendering: Boolean = true,
    /** Layer-name → [ShapeAttributes] resolver. Swap to drive a custom appearance. */
    var style: MvtStyle = DefaultMvtStyle,
    /**
     * Optional sprite atlas used to resolve `icon-image` paint to a per-icon image source. When
     * null, rules with icon paint are silently skipped. Load via [MvtSpriteAtlasLoader.load].
     */
    var spriteAtlas: MvtSpriteAtlas? = null,
    displayName: String? = "Vector Tiles",
) : MercatorTiledVectorLayer<List<Renderable>>(
    levelOffset = minZoom,
    numLevels = maxZoom + 1, // quadtree needs levels 0..maxZoom
    maxLoadedTiles = maxLoadedTiles,
    maxConcurrentFetches = maxConcurrentFetches,
    displayName = displayName,
) {
    // Vector-tile fills/lines are opaque, so keep loaded finer tiles and draw coarser ones under
    // them while children stream in — no whole-view collapse to a single coarse tile on pan/tilt/zoom.
    override val progressiveRefinement get() = true

    // The source the revalidation callback is wired to; re-wired in beginFrame if [source] swaps.
    private var wiredSource: TileSource? = null

    // Coarse-first fetch gate: low-zoom fallback tiles outrank fine ones in the wait line, so a pan
    // paints a (blurry) coarse cover immediately and refines after — instead of the render cut
    // collapsing to blank until fine tiles land. Replaces the base FIFO semaphore for MVT's network
    // round-trip; same priority-permit util as the 3D-Tiles TileFetchQueue.
    private val fetchPermits = PrioritySemaphore(maxConcurrentFetches.coerceAtLeast(1))

    // CPU gate for the decode + tessellation stage, separate from the network fetch gate above —
    // bounded so assembly can't occupy every core and starve the render + main threads.
    private val assemblyPermits = Semaphore(maxConcurrentAssembly.coerceAtLeast(1))

    /**
     * Most recent [Globe] / [Globe.State] snapshot, written each [beginFrame] and read by the fetch
     * coroutine on `Dispatchers.Default` so tile geometry can be pre-assembled off the render thread.
     */
    @Volatile
    private var capturedGlobe: Globe? = null
    @Volatile
    private var capturedGlobeState: Globe.State? = null

    // ---- Base hooks ----------------------------------------------------------------------------

    override suspend fun loadTileContent(z: Int, x: Int, y: Int, sector: Sector): List<Renderable>? {
        // The Mercator quadtree's row is 0=south; MVT URLs/decode use slippy y (0=north).
        val key = TileKey(z, x, (1 shl z) - 1 - y)
        return try {
            // Cache hits skip the permit pool; only the round-trip is gated. Empty/missing blob → an
            // empty tile (negative-cached) rather than a failure. Priority = -z so coarse fallback
            // tiles jump ahead of fine ones in the wait line (no blank-then-pop on pan).
            val blob = source.tryReadCachedTile(key.z, key.x, key.y)
                ?: fetchPermits.withPermit(-z.toDouble()) { source.fetchTile(key.z, key.x, key.y) }
            // Gate the CPU-heavy decode + tessellation separately from the network fetch above, so the
            // off-render pool can't run one decode+earcut per core and starve render/main (+ GC churn).
            withContext(mvtAssemblyDispatcher) {
                assemblyPermits.withPermit {
                    val tile = if (blob == null || blob.isEmpty) MvtTile(emptyList())
                    else MvtDecoder.decode(blob.bytes)
                    // Re-run schema detection while UNKNOWN — an empty first tile would otherwise lock it.
                    if (tile.layers.isNotEmpty() && detectedSchema.let { it == null || it == MvtSchemaDetector.Schema.UNKNOWN }) {
                        detectedSchema = MvtSchemaDetector.detect(tile)
                    }
                    toRenderables(key, tile)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logMessage(WARN, "MvtVectorLayer", "loadTileContent",
                "Failed to fetch tile $key: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    override fun renderTileContent(rc: RenderContext, content: List<Renderable>) {
        val scale = cameraZoomScale()
        for (r in content) {
            // Push the per-frame camera-zoom interpolation factor into batched line tiles so their
            // lineWidth scales smoothly between baked tile zooms. Defer label groups to [endFrame]'s
            // cross-tile collision pass; everything else renders now.
            if (r is MvtBatchedLineTile) r.widthScale = scale
            // Defer both label kinds to [endFrame]'s cross-tile collision pass; everything else now.
            when (r) {
                is MvtLabelGroup -> activeLabelGroups += r
                is MvtCurvedLineLabel -> activeLineLabels += r
                else -> r.render(rc)
            }
        }
    }

    override fun onContentEvicted(rc: RenderContext, content: List<Renderable>) {
        // Batched tiles own their GL buffers directly; release proactively so the KGSL shared-memory
        // pool doesn't fill with stale buffers. (Per-feature pick state is cleared on close/invalidate.)
        for (r in content) try {
            when (r) {
                is MvtBatchedPolygonTile -> r.releaseRenderResources(rc)
                is MvtBatchedLineTile -> r.releaseRenderResources(rc)
                is OsmBuildingsTile -> r.releaseRenderResources(rc) // extruded buildings own GL buffers too
            }
        } catch (e: Exception) {
            logMessage(WARN, "MvtVectorLayer", "onContentEvicted", "GPU buffer release failed", e)
        }
    }

    override fun beginFrame(rc: RenderContext) {
        capturedGlobe = rc.globe
        capturedGlobeState = rc.globeState
        // Re-wire stale-while-revalidate to the current source (idempotent per source instance). The
        // callback fires off the render thread; invalidateTile marshals the cache drop back onto it.
        // The callback's y is slippy (0=north); convert to the quadtree's Mercator row.
        val src = source
        if (src !== wiredSource) {
            wiredSource = src
            (src as? RevalidatingSource)?.onTileRevalidated = { z, x, y -> invalidateTile(z, x, (1 shl z) - 1 - y) }
        }
        hysteresisTileZoom(rc) // updates activeTileZoom for cameraZoomScale (no longer drives selection)
        lastCameraZoomReal = selectTileZoomReal(rc)
        activeLabelGroups.clear()
        activeLineLabels.clear()
    }

    override fun endFrame(rc: RenderContext) {
        // One global cross-tile collision pass (project → dedup → greedy-by-importance → budget)
        // sets each group's enabledMask; then render the survivors after all polygons + lines so
        // labels stay on top. Frame-to-frame stickiness lives in [labelCollider].
        if (activeLabelGroups.isNotEmpty() || activeLineLabels.isNotEmpty()) {
            labelDedup.clear()
            labelLineDedup.clear()
            val totalLabels = activeLabelGroups.sumOf { it.labels.size }
            // One pass collides point labels then curved line labels (line-vs-point + line-vs-line).
            labelCollider.run(rc, activeLabelGroups, totalLabels, labelDedup, activeLineLabels, labelLineDedup)
            // Curved line labels first, then point groups on top (city names over street names).
            for (l in activeLineLabels) try {
                l.render(rc)
            } catch (e: Exception) {
                logMessage(ERROR, "MvtVectorLayer", "endFrame", "line label render failed", e)
            }
            for (g in activeLabelGroups) try {
                g.render(rc)
            } catch (e: Exception) {
                logMessage(ERROR, "MvtVectorLayer", "endFrame", "label group render failed", e)
            }
        }
        labelCollider.endFrame()
    }

    override fun closeSource() {
        // Release the source's per-instance resources (e.g. UrlTemplateMvtTileSource's shared
        // HttpClient + OkHttp pool). Sources that need no cleanup default to a no-op.
        try { source.close() } catch (_: Exception) {}
    }

    override fun close() {
        featureStates.clear()
        super.close()
    }

    // ---- Zoom selection (drives line-width interpolation; the quadtree drives tile selection) ----

    /**
     * Pick a slippy-map zoom level for the current camera — now used only to derive the line-width
     * interpolation factor (the quadtree selects tiles by screen-space error). Default formula:
     * the zoom that makes one tile span ~256 screen pixels at the camera's distance to the target.
     */
    protected open fun selectTileZoom(rc: RenderContext): Int = selectTileZoomReal(rc).toInt()

    /** Real-valued (continuous) tile zoom from the camera; [cameraZoomScale] reads it for a smooth
     *  line-width multiplier between tile-zoom boundaries. */
    protected fun selectTileZoomReal(rc: RenderContext): Double {
        val target = rc.lookAtPosition
        val distance = if (target != null) {
            val cameraAlt = rc.camera.position.altitude
            abs(cameraAlt - target.altitude).coerceAtLeast(1.0)
        } else {
            rc.camera.position.altitude.coerceAtLeast(1.0)
        }
        val metersPerPixel = rc.pixelSizeAtDistance(distance)
        if (metersPerPixel <= 0.0) return minZoom.toDouble()
        val tileMeters = TILE_PIXEL_SIZE * metersPerPixel
        val equatorialCircumference = 2.0 * PI * rc.globe.equatorialRadius
        return log2(equatorialCircumference / tileMeters)
    }

    /** How hard to coarsen the foreshortened far band (Cesium `dynamicScreenSpaceError`): a horizon
     *  tile gets up to `(1 + farBandDetailFactor)×` the detail factor, so it carries fewer features.
     *  0 disables. */
    var farBandDetailFactor = 4.0
    private val farBandScratch = Vec3()

    /** Inflate the detail factor by the squared fraction of the camera-to-horizon distance to this
     *  tile — near tiles keep [detailControl], the horizon band coarsens hard (fewer place features
     *  in the tile, instead of relying on the label collision pass to hide them). */
    override fun effectiveDetailControl(rc: RenderContext, tile: Tile): Double {
        if (farBandDetailFactor <= 0.0 || rc.horizonDistance <= 0.0) return detailControl
        val s = tile.sector
        rc.geographicToCartesian(s.centroidLatitude, s.centroidLongitude, 0.0, AltitudeMode.ABSOLUTE, farBandScratch)
        val f = (rc.cameraPoint.distanceTo(farBandScratch) / rc.horizonDistance).coerceIn(0.0, 1.0)
        return detailControl * (1.0 + farBandDetailFactor * f * f)
    }

    /**
     * Hysteresis filter over [selectTileZoom]: only commits a new integer zoom after it's been
     * observed for [zoomStableFrames] consecutive frames. Keeps the line-width scale from thrashing
     * when the camera teeters around a zoom boundary. Returns the committed zoom (now read only via
     * the [activeTileZoom] side effect).
     */
    protected open fun hysteresisTileZoom(rc: RenderContext): Int {
        val candidate = selectTileZoom(rc)
        val active = activeTileZoom
        if (active == -1) {
            activeTileZoom = candidate
            pendingTileZoom = candidate
            pendingFrameCount = 0
            return candidate
        }
        if (candidate == active) {
            pendingTileZoom = candidate
            pendingFrameCount = 0
            return active
        }
        if (candidate != pendingTileZoom) {
            pendingTileZoom = candidate
            pendingFrameCount = 1
        } else {
            pendingFrameCount++
        }
        return if (pendingFrameCount >= zoomStableFrames) {
            activeTileZoom = candidate
            pendingFrameCount = 0
            candidate
        } else active
    }

    /**
     * Multiplier applied to surface-shape line widths each frame to compensate for the difference
     * between the active tile zoom and the camera's current real zoom, so lines smoothly fatten as
     * the user zooms into the same tile rather than jumping when a new-zoom tile arrives. Clamped to
     * `2^±1`.
     */
    internal fun cameraZoomScale(): Float {
        val active = activeTileZoom
        if (active == -1) return 1f
        val delta = (lastCameraZoomReal - active).coerceIn(-1.0, 1.0)
        return exp(delta * LN2).toFloat()
    }

    private val LN2 = ln(2.0)

    /** How many consecutive frames a different zoom must be observed before switching. */
    var zoomStableFrames: Int = 8

    // Hysteresis state — last-committed tile zoom, currently-pending zoom under observation, the
    // consecutive-frame count of that pending zoom, and the most recent real-valued zoom.
    private var activeTileZoom: Int = -1
    private var pendingTileZoom: Int = -1
    private var pendingFrameCount: Int = 0
    private var lastCameraZoomReal: Double = 0.0

    // Per-frame collection of [MvtLabelGroup]s from all visible tiles — populated by
    // [renderTileContent], drained by [endFrame]'s global collision pass. A field to avoid per-frame
    // allocation. [labelCollider] owns the cross-frame stickiness state + scratch buffers.
    private val activeLabelGroups = ArrayList<MvtLabelGroup>()
    // Per-frame collection of curved line labels (one collision candidate each), drained by the same
    // [endFrame] collision pass as the point label groups.
    private val activeLineLabels = ArrayList<MvtCurvedLineLabel>()
    private val labelCollider = MvtLabelCollider()
    // Per-frame cross-tile POINT-label dedup: id-keyed when present, else a zoom-independent geographic
    // metres fallback (per-tile-quantized parent + child anchors can project far apart at high zoom).
    private val labelDedup = LabelGeoDedup()
    // Separate dedup for curved line labels' text+proximity fallback (id-keyed dups don't use it),
    // kept apart so a street name never dedups against a same-named point label.
    private val labelLineDedup = LabelDedup()

    /**
     * Schema reported by [MvtSchemaDetector] for the first non-empty decoded tile. `null` until
     * detection succeeds; re-runs while UNKNOWN to handle empty first-tile responses.
     */
    @Volatile
    var detectedSchema: MvtSchemaDetector.Schema? = null
        private set

    // Per-feature dynamic state read by ["feature-state", key] expressions. Keyed by the
    // MvtPickedFeature handed out at pick time; values are arbitrary (hover/select, severity, etc.).
    private val featureStates = HashMap<MvtPickedFeature, MutableMap<String, Any?>>()

    /**
     * Set dynamic feature state read by `["feature-state", key]` expressions. After mutating state
     * for already-styled features, call [invalidate] to force re-styling.
     */
    fun setFeatureState(feature: MvtPickedFeature, key: String, value: Any?) {
        val map = featureStates.getOrPut(feature) { HashMap() }
        if (value == null) map.remove(key) else map[key] = value
        if (map.isEmpty()) featureStates.remove(feature)
    }

    /** Read all state set for [feature]. Returns null when no state has been set. */
    fun featureState(feature: MvtPickedFeature): Map<String, Any?>? = featureStates[feature]

    /** Drop all cached tiles so [toRenderables] re-runs on the next render. Use after bulk
     *  [setFeatureState] mutations. */
    fun invalidate() {
        clearContent()
        WorldWind.requestRedraw()
    }

    /** Full-tile background fill (the Mapbox `background` layer) covering [sector]'s corners exactly,
     *  so adjacent tiles abut with no seam. Emitted at [MvtStyle.Z_BACKGROUND] under every feature. */
    private fun backgroundFeature(sector: Sector, bg: Color) = MvtBatchedPolygonTile.BatchFeature(
        outer = doubleArrayOf(
            sector.minLongitude.inDegrees, sector.minLatitude.inDegrees,
            sector.maxLongitude.inDegrees, sector.minLatitude.inDegrees,
            sector.maxLongitude.inDegrees, sector.maxLatitude.inDegrees,
            sector.minLongitude.inDegrees, sector.maxLatitude.inDegrees,
        ),
        holes = emptyList(),
        attributes = ShapeAttributes().apply {
            interiorColor = bg
            isDrawInterior = true
            isDrawOutline = false
            shadowMode = ShadowMode.DISABLED
        },
        zOrder = MvtStyle.Z_BACKGROUND,
    )

    /**
     * Convert one decoded tile into the list of [Renderable]s the render loop iterates over.
     *
     * With [useBatchedRendering] = true (default), POLYGON features pack into a single
     * [MvtBatchedPolygonTile] and LINESTRING features into a single [MvtBatchedLineTile]
     * (each: one VBO + one EBO per tile per [Globe.State]). With false, both polygon and
     * line features become per-feature [Polygon] / [Path] shapes.
     *
     * POINT features become [Label]s when a rule-based style ([MvtRuleBasedStyle]) matches
     * with a `text {}` paint block. Hand-coded [MvtStyle] implementations don't carry label
     * data — POINTs they produce are dropped. UNKNOWN-geometry features are always dropped.
     *
     * Override to customise (cull tiny polygons by area, swap shape types, attach pick
     * metadata). Subclasses that override and need per-feature attribute control should set
     * [useBatchedRendering] = false; the batched path shares one [ShapeAttributes] instance
     * per color bucket.
     */
    protected open fun toRenderables(key: TileKey, tile: MvtTile): List<Renderable> {
        val out = ArrayList<Renderable>()
        val polygonBatch = if (useBatchedRendering) ArrayList<MvtBatchedPolygonTile.BatchFeature>() else null
        // Mapbox `background` layer: a full-tile fill under every feature so gaps show the style base.
        // Routed through the batched fill (not DrawableSurfaceColor) so it composites in the
        // shadow-receiving DrawableSurfaceShape pass. Its Z_BACKGROUND becomes the tile's min-z.
        if (polygonBatch != null) style.backgroundColor?.let { polygonBatch += backgroundFeature(key.sector, it) }
        val lineBatch = if (useBatchedRendering) ArrayList<MvtBatchedLineTile.BatchLineFeature>() else null
        // Per-color extruded-building buckets — each becomes one OsmBuildingsTile. Keyed by
        // the actual Color value (which has structural equality) so distinct colors with
        // colliding hashes don't accidentally merge.
        val extrusionBuckets = HashMap<Color, ExtrusionBucket>()

        // Per-feature Path collection, only populated in non-batched mode; kept ordered so
        // we can stable-sort by z-order before adding to [out].
        val lineRenderables = if (useBatchedRendering) null else ArrayList<Pair<Int, Path>>()

        // Label collection — accumulated per tile then wrapped in one [MvtLabelGroup] at the
        // end so Stage 4b's collision pass runs across all labels in this tile.
        val tileLabels = ArrayList<Label>()
        // Primitive-backed (no per-label Int/Float boxing — an addresses tile carries thousands of labels).
        val tileLabelPriorities = IntList()
        val tileLabelSizes = IntList()
        val tileLabelWidths = FloatList() // measured pixel width per label (exact collision box)
        val tileLabelMinZooms = IntList()
        val tileLabelIds = LongList() // per-label feature id (0 when absent) for cross-tile id dedup
        // Dedup curved line-labels by name within a coarse grid cell, so a dual carriageway's two
        // parallel same-name ways (or a name split into a few co-located segments) yield ONE label.
        val streetSeen = HashSet<String>()

        // Dedup address (housenumber) labels by number within a building-scale radius. OSM tags a
        // complex's number on many nodes (the data has e.g. "176" ×17 within ~230 m), so without this one
        // building shows the same number 5+ times. Distinct buildings sharing a number sit ~1.5 km apart
        // (every street has a "5"), far outside the radius, so they keep their labels.
        val addrSeen = HashMap<String, MutableList<Position>>()

        // Rule-based styles want the tile zoom passed through so zoom-interpolated paint
        // properties (line widths, palette shifts) resolve correctly. Hand-coded MvtStyle
        // implementations don't read zoom — they go through the single-arg overload.
        val ruleStyle = style as? MvtRuleBasedStyle

        // Per-tile paint cache (Rank-1 boxing fix). For a rule whose paint is NOT feature-
        // dependent (no get/has/feature-state/geometry-type/line-progress — i.e. zoom-only or
        // literal), the resolved shape attributes / casing / extrusion are IDENTICAL for every
        // matching feature in this tile (zoom is constant within a tile). Resolve once on first
        // match, reuse thereafter — avoiding one EvalContext + zoom-ramp boxing storm per feature.
        // Identity-keyed by the rule instance; LOCAL to this call so nothing leaks across tiles.
        // The cached ShapeAttributes are shared read-only by all consumers (BatchFeature /
        // BatchLineFeature / Polygon / Path read them; gradient + extrusion defensively copy).
        val paintCache = if (ruleStyle != null) HashMap<MvtStyleRule, ResolvedPaint>() else null

        // Simplify only at globe/regional zoom — at city scale keep full detail (lines especially
        // distort under 1px simplification), and full-detail continental geometry at low zoom OOMs.
        val simplifyTile = simplifyGeometry && key.z <= simplifyMaxZoom

        val scratch = MvtGeometry.Scratch()
        // One reused props map for the whole tile — refilled (cleared) per feature instead of a fresh
        // LinkedHashMap each iteration. Safe: every reader (firstMatching/resolve/buildText/buildExtrusion/
        // placeMinZoom/…) reads it transiently; none retain the reference. Pick uses raw feature.tags.
        val propsScratch = LinkedHashMap<String, Any?>()
        // Reused shape+text rule holder — refilled per feature by firstShapeAndText (no per-feature alloc).
        val shapeTextScratch = MvtRuleBasedStyle.ShapeTextRules()
        val layers = tile.layers
        for (li in layers.indices) {
            val layer = layers[li]
            val features = layer.features
            for (fi in features.indices) {
                val feature = features[fi]
                inflateMvtPropertiesInto(feature.tags, layer.keys, layer.values, propsScratch)
                val props: Map<String, Any?> = propsScratch

                // Shape and text paint resolve via independent rules per feature (Mapbox-GL style).
                // Per-feature pick payload, built only when pick-enabled; also the [featureStates] key
                // so `["feature-state", ...]` expressions see this feature's dynamic state. Built only
                // AFTER the no-match continue below, so unmatched features (the common case) don't
                // allocate one just to discard it.
                val pickPayload: MvtPickedFeature?
                val featureState: Map<String, Any?>?
                val shapeRule: MvtStyleRule?
                val textRule: MvtStyleRule?
                val zOrder: Int
                val shapeAttrs: ShapeAttributes?
                if (ruleStyle != null) {
                    // Single indexed walk resolves shape + text rules at once (no double match / iterator).
                    ruleStyle.firstShapeAndText(layer.name, feature.type, key.z, props, shapeTextScratch)
                    shapeRule = shapeTextScratch.shape
                    textRule = shapeTextScratch.text
                    if (shapeRule == null && textRule == null) continue
                    pickPayload = if (isPickEnabled)
                        MvtPickedFeature(layer.name, feature.type, key, feature.tags, layer.keys, layer.values)
                    else null
                    featureState = pickPayload?.let { featureStates[it] }
                    val ruleForZ = shapeRule ?: textRule!!
                    zOrder = ruleForZ.zOrder
                    shapeAttrs = shapeRule?.let { resolveShape(it, paintCache!!, key.z, props, featureState, feature.type) }
                } else {
                    shapeRule = null
                    textRule = null
                    val attrs = style.styleFor(layer.name, feature.type, props) ?: continue
                    pickPayload = if (isPickEnabled)
                        MvtPickedFeature(layer.name, feature.type, key, feature.tags, layer.keys, layer.values)
                    else null
                    featureState = pickPayload?.let { featureStates[it] }
                    zOrder = style.zOrderFor(layer.name, feature.type, props)
                    shapeAttrs = attrs
                }
                // Used below: matchedRule for POINT label resolution stays the textRule when
                // present (where the text payload lives).
                val matchedRule = textRule ?: shapeRule
                when (feature.type) {
                    MvtGeometryType.POLYGON -> {
                        val attrs = shapeAttrs ?: continue
                        val rings = MvtGeometry.decodePolygons(feature, key.z, key.x, key.y, layer.extent, simplify = simplifyTile, scratch)
                        // 3D extrusion path — collect buildings into per-attribute buckets that
                        // emit as OsmBuildingsTile renderables below.
                        val extrusion = shapeRule?.let { resolveExtrusion(it, paintCache!!, key.z, props, featureState, feature.type) }
                        if (extrusion != null) {
                            val (height, base) = extrusion
                            val bucket = extrusionBuckets.getOrPut(attrs.interiorColor) {
                                ExtrusionBucket(ShapeAttributes(attrs))
                            }
                            for (ri in rings.indices) {
                                val poly = rings[ri]
                                if (poly.outer.size < 6) continue // flat lon/lat → 3 verts = 6 doubles
                                // Extrusion builds 3D OsmBuildings, which key off Position rings — rebuild
                                // them from the flat decoder output (only for extruded layers, e.g. buildings).
                                bucket.buildings += OsmBuilding(
                                    id = "mvt-${key.z}-${key.x}-${key.y}-${bucket.buildings.size}",
                                    outerRing = flatRingToPositions(poly.outer),
                                    innerRings = poly.holes.map { flatRingToPositions(it) },
                                    height = height,
                                    minHeight = base,
                                )
                            }
                            continue
                        }
                        for (ri in rings.indices) {
                            val poly = rings[ri]
                            if (poly.outer.size < 6) continue // flat lon/lat → 3 verts = 6 doubles
                            if (polygonBatch != null) {
                                polygonBatch += MvtBatchedPolygonTile.BatchFeature(
                                    outer = poly.outer, holes = poly.holes,
                                    attributes = attrs, zOrder = zOrder,
                                    pickPayload = pickPayload,
                                )
                            } else {
                                // Legacy unbatched path needs Position-list boundaries — rebuild them
                                // from the flat rings (rare; only when useBatchedRendering = false).
                                val p = Polygon(flatRingToPositions(poly.outer), attrs).apply {
                                    altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                                    isFollowTerrain = true
                                    pathType = PathType.LINEAR
                                    this.zOrder = zOrder.toDouble()
                                }
                                val holes = poly.holes
                                for (hi in holes.indices) { val hole = holes[hi]; if (hole.size >= 6) p.addBoundary(flatRingToPositions(hole)) }
                                out += p
                            }
                        }
                    }
                    MvtGeometryType.LINESTRING -> {
                        val lines = MvtGeometry.decodeLines(feature, key.z, key.x, key.y, layer.extent, simplify = simplifyTile, scratch)
                        // Stroke geometry (when the rule has fill/line paint).
                        if (shapeAttrs != null) {
                            // Resolve casing once per feature; emit a wider stroke at zOrder-1
                            // so it paints under the main line.
                            val casingAttrs = shapeRule?.let { resolveCasing(it, paintCache!!, key.z, props, featureState, feature.type) }
                            // Loop-invariant: gradient-rule detection happens once per feature,
                            // not per polyline-within-feature.
                            val gradientRule = shapeRule?.takeIf { it.paint.hasLineGradient }
                            for (lni in lines.indices) {
                                val line = lines[lni]
                                if (line.size < 4) continue // flat lon/lat → <2 verts
                                // Casing always emits to the batched path (or as its own Path
                                // when batching is off).
                                if (casingAttrs != null) {
                                    if (lineBatch != null) {
                                        lineBatch += MvtBatchedLineTile.BatchLineFeature(
                                            coords = line, attributes = casingAttrs,
                                            zOrder = zOrder - 1, pickPayload = pickPayload,
                                        )
                                    } else {
                                        val casingPath = Path(flatRingToPositions(line), casingAttrs).apply {
                                            altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                                            isFollowTerrain = true
                                            pathType = PathType.LINEAR
                                            this.zOrder = (zOrder - 1).toDouble()
                                        }
                                        lineRenderables?.let { it += (zOrder - 1) to casingPath }
                                            ?: run { out += casingPath }
                                    }
                                }
                                // Line-gradient path: subdivide into GRADIENT_SUBDIVISIONS arc-length
                                // segments, each emitted as a batched feature with its midpoint-sampled
                                // color. Falls through to the solid-color path when there's no gradient.
                                if (gradientRule != null) {
                                    emitGradientSegments(
                                        line = line,
                                        baseAttrs = shapeAttrs,
                                        rule = gradientRule,
                                        zoom = key.z,
                                        zOrder = zOrder,
                                        properties = props,
                                        featureState = featureState,
                                        geometryType = feature.type,
                                        pickPayload = pickPayload,
                                        lineBatch = lineBatch,
                                        lineRenderables = lineRenderables,
                                        out = out,
                                    )
                                    continue
                                }
                                // Dashed features render through per-feature [Path]: the batched tile sets
                                // texCoord1d = 0 on every corner (stipple would sample one texel → solid),
                                // while Path computes proper per-vertex texCoord for the repeating stipple.
                                val forceUnbatched = shapeAttrs.outlineImageSource != null
                                if (lineBatch != null && !forceUnbatched) {
                                    lineBatch += MvtBatchedLineTile.BatchLineFeature(
                                        coords = line, attributes = shapeAttrs, zOrder = zOrder,
                                        pickPayload = pickPayload,
                                    )
                                } else {
                                    val path = Path(flatRingToPositions(line), shapeAttrs).apply {
                                        altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                                        isFollowTerrain = true
                                        pathType = PathType.LINEAR
                                        this.zOrder = zOrder.toDouble()
                                    }
                                    if (lineRenderables != null) {
                                        lineRenderables += zOrder to path
                                    } else {
                                        out += path
                                    }
                                }
                            }
                        }
                        // Line-placement label (when the rule has text paint with LINE
                        // placement). Mapbox-equivalent `symbol-placement: line-center` —
                        // one label per line at the midpoint, rotated along the local
                        // tangent so it follows road direction.
                        if (matchedRule != null
                            && matchedRule.paint.hasText
                            && matchedRule.paint.textPlacement == MvtStyleRule.LabelPlacement.LINE) {
                            val labelSpec = matchedRule.paint.buildText(key.z, props, featureState, feature.type)
                            if (labelSpec != null) {
                                // Use the source font's per-character advance widths so each
                                // glyph is positioned by its actual on-screen extent (kerning
                                // differs per platform — measureText/measureChars handles it).
                                val font = labelSpec.attributes.font
                                val charWidths = font.measureChars(labelSpec.text)
                                val textWidth = font.measureText(labelSpec.text)
                                // Collision priority by road/water class (motorway > … > service, rivers mid).
                                val linePriority = lineLabelImportance(props, zOrder)
                                for (lni in lines.indices) {
                                    val line = lines[lni]
                                    if (line.size < 4) continue // flat lon/lat → <2 verts
                                    // One curved label per street name per tile — collapses a boulevard's two
                                    // named lanes (separate features, mismatched midpoints) to a single label.
                                    if (!streetSeen.add(labelSpec.text)) continue
                                    out += MvtCurvedLineLabel(
                                        coords = line,
                                        text = labelSpec.text,
                                        attributes = labelSpec.attributes,
                                        charWidths = charWidths,
                                        textWidth = textWidth,
                                        pixelSize = labelSpec.pixelSize,
                                        priority = linePriority,
                                        featureId = feature.id, // id-keyed cross-tile dedup when non-zero
                                    )
                                }
                            }
                        }
                    }
                    MvtGeometryType.POINT -> {
                        if (matchedRule == null) continue
                        val labelSpec = if (matchedRule.paint.hasText)
                            matchedRule.paint.buildText(key.z, props, featureState, feature.type) else null
                        val iconSpec = if (matchedRule.paint.hasIcon)
                            matchedRule.paint.buildIcon(key.z, props, featureState, feature.type) else null
                        if (labelSpec == null && iconSpec == null) continue
                        val points = MvtGeometry.decodePoints(feature, key.z, key.x, key.y, layer.extent, scratch)
                        // Shield path: when a rule has BOTH icon and text, render them as
                        // ONE composite Placemark (Mapbox's symbol-with-text-on-icon =
                        // highway shield). Otherwise emit a Label and/or Placemark separately.
                        val isShield = iconSpec != null && labelSpec != null
                        for (pi in points.indices) {
                            val pt = points[pi]
                            if (labelSpec != null && !isShield &&
                                !(layer.name == "addresses" && addressClustered(addrSeen, labelSpec.text, pt))) {
                                val label = Label(pt, labelSpec.text, labelSpec.attributes).apply {
                                    altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                                    this.zOrder = zOrder.toDouble()
                                }
                                tileLabels += label
                                // Collision priority by place importance, not paint-order zOrder.
                                tileLabelPriorities.add(placeImportance(props))
                                tileLabelSizes.add(labelSpec.pixelSize)
                                tileLabelWidths.add(labelSpec.width)
                                tileLabelMinZooms.add(placeMinZoom(props))
                                tileLabelIds.add(feature.id) // id-keyed cross-tile dedup when non-zero
                            }
                            if (iconSpec != null) {
                                val atlas = spriteAtlas ?: continue
                                val factory = atlas.iconFactory(iconSpec.name) ?: continue
                                val attrs = PlacemarkAttributes.createWithImage(
                                    ImageSource.fromImageFactory(factory)
                                ).apply {
                                    // Icon native pixel size is set in the atlas manifest; the
                                    // factory crops to (entry.width × entry.height). Scale by
                                    // iconSpec.size and divide by the entry's pixelRatio so
                                    // @2x atlas variants render at the same on-screen size.
                                    val pr = factory.entry.pixelRatio
                                    imageScale = iconSpec.size.toDouble() / pr
                                    // isShield is defined as `iconSpec != null && labelSpec != null`,
                                    // so the compiler smart-casts labelSpec to non-null in this branch.
                                    if (isShield) {
                                        // Use the rule's text attributes for the on-shield label.
                                        labelAttributes.copy(labelSpec.attributes)
                                        // icon-text-fit: width — widen the shield (via imageScaleX only,
                                        // height unchanged) when the label exceeds the icon's 70% content area.
                                        val textPx = labelSpec.attributes.font.measureText(labelSpec.text)
                                        val iconContentPx = factory.entry.width * 0.7f
                                        if (textPx > iconContentPx && iconContentPx > 0f) {
                                            imageScaleX = imageScaleY * (textPx / iconContentPx).toDouble()
                                        }
                                    }
                                    imageOffset = anchorToOffset(iconSpec.anchor)
                                }
                                val placemark = Placemark(pt, attrs).apply {
                                    altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                                    if (isShield) label = labelSpec.text
                                }
                                placemark.zOrder = zOrder.toDouble()
                                out += placemark
                            }
                        }
                    }
                    MvtGeometryType.UNKNOWN -> Unit
                }
            }
        }

        // Captured globe snapshot for off-thread pre-assembly. Null until the first frame; pre-
        // assembly is skipped in that case and the render thread assembles lazily on first paint.
        val preAssembleGlobe = capturedGlobe
        val preAssembleState = capturedGlobeState

        // Prepend the batched-polygon tile so fills paint under lines. Surface compositor
        // honours enqueue order within a sector for opaque drawables.
        if (polygonBatch != null && polygonBatch.isNotEmpty()) {
            val batched = MvtBatchedPolygonTile(
                features = polygonBatch,
                boundingSector = key.sector,
                displayName = "mvt-batched-poly-${key.z}-${key.x}-${key.y}",
            ).apply {
                // Single tile-level z. Per-feature z-ordering inside the tile is preserved by
                // EBO bucket order; this is the z used when compositing this tile against
                // lines and other surface drawables. Picking the lowest polygon z lets lines
                // (typically Z_ROAD_* ≥ 70) consistently composite over the polygon mass.
                zOrder = (polygonBatch.minOfOrNull { it.zOrder } ?: 0).toDouble()
            }
            // Off-thread tessellation (earcut into a reused per-tile arena, serialised by the tile's
            // assembleLock) — moves the expensive work off the render thread so zoom storms don't stall.
            if (preAssembleGlobe != null) batched.assemble(preAssembleGlobe, preAssembleState)
            out.add(0, batched)
        }

        // Append the batched-line tile after polygons so its compositor z is above. Lines
        // route by attribute width/color INSIDE the tile via per-range drawElements calls.
        if (lineBatch != null && lineBatch.isNotEmpty()) {
            val batchedLines = MvtBatchedLineTile(
                features = lineBatch,
                boundingSector = key.sector,
                displayName = "mvt-batched-line-${key.z}-${key.x}-${key.y}",
            ).apply {
                zOrder = (lineBatch.maxOfOrNull { it.zOrder } ?: 0).toDouble()
            }
            // Line assembly is pure degree arithmetic — safe to pre-assemble regardless of
            // whether we have a globe yet, but we gate on globeState for cache consistency.
            batchedLines.assemble(preAssembleState)
            out += batchedLines
        }

        // Non-batched fallback path: stable-sort per-feature Paths by z ascending so reflow /
        // draw-list iteration matches paint order even outside the surface compositor's sort.
        if (lineRenderables != null) {
            lineRenderables.sortBy { it.first }
            for ((_, path) in lineRenderables) out += path
        }

        // 3D extruded buildings — one OsmBuildingsTile per (color) bucket, inserted BEFORE
        // labels so labels paint on top.
        for (bucket in extrusionBuckets.values) {
            if (bucket.buildings.isEmpty()) continue
            out += OsmBuildingsTile(
                buildings = bucket.buildings,
                attributes = bucket.attributes,
                useOsmColors = false,
                displayName = "mvt-buildings-${key.z}-${key.x}-${key.y}",
            )
        }

        // Labels go last in the [out] list so they paint on top of every shape in the tile,
        // and they go through [MvtLabelGroup] so the collision pass runs over the full set.
        if (tileLabels.isNotEmpty()) {
            out += MvtLabelGroup(
                labels = tileLabels,
                priorities = tileLabelPriorities.toIntArray(),
                pixelSizes = tileLabelSizes.toIntArray(),
                widths = tileLabelWidths.toFloatArray(),
                minZooms = tileLabelMinZooms.toIntArray(),
                featureIds = tileLabelIds.toLongArray(),
                displayName = "mvt-labels-${key.z}-${key.x}-${key.y}",
            )
        }
        return out
    }

    /**
     * Subdivide [line] into [MvtStyleRule.PaintSpec.GRADIENT_SUBDIVISIONS] arc-length
     * segments, sample [rule]'s gradient at each segment's midpoint, and emit each segment
     * as its own batched feature with the sampled color.
     */
    private fun emitGradientSegments(
        line: DoubleArray,  // flat [lon°, lat°, …]
        baseAttrs: ShapeAttributes,
        rule: MvtStyleRule,
        zoom: Int,
        zOrder: Int,
        properties: Map<String, Any?>,
        featureState: Map<String, Any?>?,
        geometryType: MvtGeometryType,
        pickPayload: MvtPickedFeature?,
        lineBatch: ArrayList<MvtBatchedLineTile.BatchLineFeature>?,
        lineRenderables: ArrayList<Pair<Int, Path>>?,
        out: ArrayList<Renderable>,
    ) {
        val k = MvtStyleRule.PaintSpec.GRADIENT_SUBDIVISIONS.coerceAtLeast(2)
        // Cumulative arc length using planar lat/lon distance — matches PathType.LINEAR
        // semantics. For surface-clamped lines at MVT zoom (≤14) the difference from
        // great-circle is sub-pixel.
        val n = line.size / 2
        val cum = DoubleArray(n)
        for (i in 1 until n) {
            val dx = line[i * 2] - line[(i - 1) * 2]
            val dy = line[i * 2 + 1] - line[(i - 1) * 2 + 1]
            cum[i] = cum[i - 1] + sqrt(dx * dx + dy * dy)
        }
        val total = cum.last()
        if (total <= 0.0) return
        // For each segment 0..k-1, gather the sub-polyline between progress fractions
        // i/k and (i+1)/k by interpolating endpoints at exact arc-length boundaries and
        // including any waypoints that fall within. Reused flat lon/lat scratch (worst case:
        // all waypoints + both endpoints in one segment).
        val sub = DoubleArray((n + 2) * 2)
        var startIdx = 0
        var prevLon = line[0]
        var prevLat = line[1]
        for (i in 0 until k) {
            val startT = i.toDouble() / k
            val endT = (i + 1).toDouble() / k
            val endArc = total * endT
            // Build the sub-polyline as flat lon/lat doubles.
            var sc = 0
            sub[sc++] = prevLon; sub[sc++] = prevLat
            // Walk forward through waypoints that fall before endArc.
            var j = startIdx + 1
            while (j < n && cum[j] < endArc) { sub[sc++] = line[j * 2]; sub[sc++] = line[j * 2 + 1]; j++ }
            // Interpolate the segment's end position at exact endArc (if there's a next
            // waypoint to interpolate against; otherwise use the last waypoint).
            val endLon: Double
            val endLat: Double
            if (j < n) {
                val seg = (j - 1).coerceIn(0, n - 2)
                val segStart = cum[seg]
                val segLen = cum[seg + 1] - segStart
                val u = if (segLen <= 0.0) 0.0 else ((endArc - segStart) / segLen).coerceIn(0.0, 1.0)
                endLon = line[seg * 2] + (line[(seg + 1) * 2] - line[seg * 2]) * u
                endLat = line[seg * 2 + 1] + (line[(seg + 1) * 2 + 1] - line[seg * 2 + 1]) * u
            } else {
                endLon = line[(n - 1) * 2]; endLat = line[(n - 1) * 2 + 1]
            }
            sub[sc++] = endLon; sub[sc++] = endLat
            if (sc >= 4) {
                val midProgress = (startT + endT) * 0.5
                val color = rule.paint.buildGradientColor(
                    zoom, midProgress, properties, featureState, geometryType,
                )
                if (color != null) {
                    val segAttrs = ShapeAttributes(baseAttrs).apply {
                        outlineColor = color
                    }
                    val flat = sub.copyOf(sc)
                    if (lineBatch != null) {
                        lineBatch += MvtBatchedLineTile.BatchLineFeature(
                            coords = flat, attributes = segAttrs, zOrder = zOrder,
                            pickPayload = pickPayload,
                        )
                    } else {
                        val path = Path(flatRingToPositions(flat), segAttrs).apply {
                            altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                            isFollowTerrain = true
                            pathType = PathType.LINEAR
                            this.zOrder = zOrder.toDouble()
                        }
                        if (lineRenderables != null) lineRenderables += zOrder to path
                        else out += path
                    }
                }
            }
            // Carry the end position over so adjacent segments share their join vertex
            // exactly — no visible seam between segments. When the boundary aligned with
            // an existing waypoint (cum[j] == endArc), advance startIdx past it so the
            // next iteration's walk doesn't re-include that waypoint as a duplicate.
            prevLon = endLon; prevLat = endLat
            startIdx = if (j < n && cum[j] == endArc) j else j - 1
        }
    }

    private class ExtrusionBucket(val attributes: ShapeAttributes) {
        val buildings = ArrayList<OsmBuilding>()
    }

    /**
     * Per-tile cached resolution of one [MvtStyleRule]'s constant (zoom-only/literal) paint
     * outputs. Only populated for rules whose [MvtStyleRule.PaintSpec.isFeatureDependent] is
     * false — every matching feature in the tile then shares these instances. Each `*Resolved`
     * flag distinguishes "computed and null" from "not computed yet" so a genuinely-null result
     * (e.g. a rule with no casing) is cached and not recomputed per feature.
     *
     * The cached [ShapeAttributes] are treated as read-only by all consumers (batched/per-feature
     * shapes read them; extrusion + gradient defensively copy), so sharing one instance across
     * many renderables is safe.
     */
    private class ResolvedPaint {
        var shape: ShapeAttributes? = null
        var shapeResolved = false
        var casing: ShapeAttributes? = null
        var casingResolved = false
        var extrusion: Pair<Double, Double>? = null
        var extrusionResolved = false
    }

    /** Resolve a rule's shape attributes, caching per (rule, tile) when the paint is constant. */
    private fun resolveShape(
        rule: MvtStyleRule,
        cache: HashMap<MvtStyleRule, ResolvedPaint>,
        zoom: Int,
        props: Map<String, Any?>,
        featureState: Map<String, Any?>?,
        geometryType: MvtGeometryType,
    ): ShapeAttributes {
        // Feature-dependent paint must resolve per feature — its result varies within the tile.
        if (rule.paint.isFeatureDependent) return rule.resolve(zoom, props, featureState, geometryType)
        val entry = cache.getOrPut(rule) { ResolvedPaint() }
        if (!entry.shapeResolved) {
            // Constant paint: props/featureState don't affect the result, so the first feature's
            // values yield the shared attributes for every matching feature in this tile.
            entry.shape = rule.resolve(zoom, props, featureState, geometryType)
            entry.shapeResolved = true
        }
        return entry.shape!!
    }

    /** Resolve a rule's casing attributes, caching per (rule, tile) when the paint is constant. */
    private fun resolveCasing(
        rule: MvtStyleRule,
        cache: HashMap<MvtStyleRule, ResolvedPaint>,
        zoom: Int,
        props: Map<String, Any?>,
        featureState: Map<String, Any?>?,
        geometryType: MvtGeometryType,
    ): ShapeAttributes? {
        if (rule.paint.isFeatureDependent) return rule.paint.buildCasing(zoom, props, featureState, geometryType)
        val entry = cache.getOrPut(rule) { ResolvedPaint() }
        if (!entry.casingResolved) {
            entry.casing = rule.paint.buildCasing(zoom, props, featureState, geometryType)
            entry.casingResolved = true
        }
        return entry.casing
    }

    /** Resolve a rule's extrusion (height, base), caching per (rule, tile) when the paint is constant. */
    private fun resolveExtrusion(
        rule: MvtStyleRule,
        cache: HashMap<MvtStyleRule, ResolvedPaint>,
        zoom: Int,
        props: Map<String, Any?>,
        featureState: Map<String, Any?>?,
        geometryType: MvtGeometryType,
    ): Pair<Double, Double>? {
        if (rule.paint.isFeatureDependent) return rule.paint.buildExtrusion(zoom, props, featureState, geometryType)
        val entry = cache.getOrPut(rule) { ResolvedPaint() }
        if (!entry.extrusionResolved) {
            entry.extrusion = rule.paint.buildExtrusion(zoom, props, featureState, geometryType)
            entry.extrusionResolved = true
        }
        return entry.extrusion
    }

    /** Slippy-map tile coordinate triple (y is slippy, 0=north). */
    data class TileKey(val z: Int, val x: Int, val y: Int) {
        val sector: Sector get() = tileToSector(z, x, y)
    }

    companion object {
        /**
         * Service-type tag persisted in `gpkg_web_service.service_type` when caching an
         * MVT layer with a service association. Read by `GpkgContentManager` to decide how
         * to rebuild the source at content-getter time.
         */
        const val SERVICE_TYPE = "MVT"

        /** Standard slippy-tile pixel width — the input to camera-altitude → zoom matching. */
        const val TILE_PIXEL_SIZE: Int = 256

        fun tileToSector(zoom: Int, x: Int, y: Int): Sector {
            val n = 1 shl zoom
            val west = x.toDouble() / n * 360.0 - 180.0
            val east = (x + 1).toDouble() / n * 360.0 - 180.0
            val north = atan(sinh(PI * (1 - 2 * y.toDouble() / n))) * 180.0 / PI
            val south = atan(sinh(PI * (1 - 2 * (y + 1).toDouble() / n))) * 180.0 / PI
            return Sector.fromDegrees(south, west, north - south, east - west)
        }

        internal fun anchorToOffset(anchor: String): Offset = when (anchor) {
            "top" -> Offset.topCenter()
            "bottom" -> Offset.bottomCenter()
            "left" -> Offset(OffsetMode.FRACTION, 0.0, OffsetMode.FRACTION, 0.5)
            "right" -> Offset(OffsetMode.FRACTION, 1.0, OffsetMode.FRACTION, 0.5)
            "top-left" -> Offset.topLeft()
            "top-right" -> Offset.topRight()
            "bottom-left" -> Offset.bottomLeft()
            "bottom-right" -> Offset.bottomRight()
            else -> Offset.center()
        }
    }
}

/** Flat `[lon°, lat°, …]` ring/line → [Position] list, for the legacy unbatched [Polygon]/[Path] paths only. */
private fun flatRingToPositions(flat: DoubleArray): List<Position> {
    val out = ArrayList<Position>(flat.size / 2)
    var i = 0
    while (i < flat.size) { out.add(Position.fromDegrees(flat[i + 1], flat[i], 0.0)); i += 2 }
    return out
}

/** Radius (metres) within which two same-housenumber address labels are one building/complex and deduped.
 *  Building-scale: tight enough to keep genuinely distinct same-number buildings (≈1.5 km apart on
 *  different streets), wide enough to collapse a complex's repeated nodes (seen ×17 within ~230 m). */
internal var ADDR_DEDUP_M = 65.0

/** True if a same-housenumber address sits within [ADDR_DEDUP_M] of one already kept in this tile; else
 *  records it. Local-equirectangular metres (cheap, no globe) — fine at building scale. */
private fun addressClustered(seen: HashMap<String, MutableList<Position>>, hn: String, pt: Position): Boolean {
    val kept = seen.getOrPut(hn) { ArrayList(4) }
    val lat = pt.latitude.inDegrees
    val lon = pt.longitude.inDegrees
    val mPerDeg = 111_320.0
    val lonScale = mPerDeg * cos(pt.latitude.inRadians)
    val r2 = ADDR_DEDUP_M * ADDR_DEDUP_M
    for (p in kept) {
        val dLat = (lat - p.latitude.inDegrees) * mPerDeg
        val dLon = (lon - p.longitude.inDegrees) * lonScale
        if (dLat * dLat + dLon * dLon < r2) return true
    }
    kept += pt
    return false
}

/** Place/POI label importance (higher = kept first) for collision priority + the per-view budget, so
 *  capitals/cities survive over hamlets. Reads whatever the source exposes: `population`, then
 *  `rank`/`symbolrank` (smaller = bigger), else the `class`/`kind` ordinal. */
private fun placeImportance(props: Map<String, Any?>): Int {
    val population = (props["population"] as? Number)?.toInt()
    if (population != null && population > 0) return 1_000_000 + population
    val rank = ((props["rank"] ?: props["symbolrank"]) as? Number)?.toInt()
    if (rank != null) return 1_000_000 - rank.coerceIn(0, 999) * 1000
    return when ((props["class"] ?: props["kind"] ?: props["subclass"])?.toString()) {
        "country" -> 900_000
        "state", "province", "region" -> 800_000
        "city", "capital" -> 700_000
        "town" -> 500_000
        "village" -> 300_000
        "suburb", "neighbourhood", "quarter", "borough" -> 200_000
        "hamlet", "locality", "isolated_dwelling", "farm" -> 100_000
        else -> 50_000
    }
}

/** Curved line-label collision priority (higher wins) by road/water class. Motorways/trunks beat
 *  lesser roads, rivers sit mid, residential/service lowest. Kept BELOW the major-place point
 *  importances (city = 700_000) so a street name yields to a city name in line-vs-point collision.
 *  Falls back to the rule's [zOrder] (clamped) when the class tag is unknown. */
private fun lineLabelImportance(props: Map<String, Any?>, zOrder: Int): Int =
    when ((props["class"] ?: props["kind"] ?: props["subclass"])?.toString()) {
        "motorway", "trunk" -> 690_000
        "primary" -> 600_000
        "secondary" -> 500_000
        "tertiary" -> 400_000
        "river", "canal" -> 350_000
        "minor", "residential", "living_street", "street", "unclassified" -> 250_000
        "stream", "ditch", "drain" -> 200_000
        "service", "track", "path", "footway", "cycleway" -> 150_000
        else -> 100_000 + zOrder.coerceIn(0, 99)
    }

/** Sentinel minzoom above the max zoom — a label with this minzoom is never eligible (used to hide
 *  region/state admin labels). */
private const val HIDDEN_MINZOOM = 99

/** Place kinds that are SETTLEMENTS (ranked by population) rather than admin areas — incl. capitals and
 *  regional capitals, which often also carry an admin_level but must be ranked as cities, not hidden. */
private val SETTLEMENT_KINDS = setOf(
    "city", "town", "village", "hamlet", "suburb", "borough", "quarter", "neighbourhood",
    "locality", "isolated_dwelling", "farm", "capital", "state_capital",
)

/** Data-driven minzoom — the slippy zoom at which a label becomes eligible (Mapbox/MapLibre symbol
 *  placement, stage 1). Lower = appears more zoomed out. Calibrated so countries/regions/capitals survive
 *  a globe-scale view while towns/villages only enter near the foreground:
 *   - `boundary_labels` carry `admin_level`: ≤2 (country) → 2, 3-4 (region/state) → 4.
 *   - `place_labels` continent/country kinds → 2, state/region → 4; otherwise by population
 *     (capital/big city → 4-5, medium → 6, town → 8, village → 10), per Shortbread/OpenMapTiles rank. */
private fun placeMinZoom(props: Map<String, Any?>): Int {
    // House numbers (addresses layer) gate to building zoom only, else ~3800/tile flood the collider.
    if (props.containsKey("housenumber")) return 14
    val kind = (props["class"] ?: props["kind"] ?: props["subclass"])?.toString()
    val popTag = (props["population"] as? Number)?.toDouble()
    // Admin-area labels (boundary_labels) only — checked only when NOT a settlement, so a capital/
    // regional-capital city carrying an admin_level isn't swallowed here (the "capitals excluded" bug).
    val isSettlement = popTag != null || kind in SETTLEMENT_KINDS
    if (!isSettlement) {
        // COUNTRY labels (admin_level ≤ 2) show from continental zoom; REGION/state (admin 3-4) stay
        // HIDDEN — their long multilingual names crowd the view. Capitals are settlements (handled above),
        // so country labels no longer swallow them.
        (props["admin_level"] as? Number)?.toDouble()?.let { return if (it <= 2.0) 4 else HIDDEN_MINZOOM }
        when (kind) {
            "continent" -> return 3
            "country" -> return 4
            "state", "province", "region" -> return HIDDEN_MINZOOM
        }
        // OpenMapTiles `rank` (lower = more important): rank n first appears ~zoom n-1.
        (props["rank"] as? Number)?.toInt()?.let { return (it - 1).coerceIn(4, 15) }
    }
    // Settlements (incl. capitals) → minzoom by POPULATION (Mapbox Streets / OpenMapTiles place tiers):
    // mega z3, 1M+ z4, 500k z5, 200k z6, 100k z7, then towns/villages step to street. Per-kind default
    // when population is absent; any other point label falls through here too (default → street zoom).
    val pop = popTag ?: when (kind) {
        "capital" -> 1_000_000.0      // national capital → continental zoom even without a population tag
        "state_capital" -> 250_000.0  // regional admin center → country zoom
        "city" -> 150_000.0
        "town" -> 20_000.0
        "village" -> 2_000.0
        "suburb", "borough" -> 5_000.0
        "quarter", "neighbourhood" -> 1_000.0
        else -> 200.0 // hamlet, isolated_dwelling, farm, locality, water/street point labels, …
    }
    return when {
        pop >= 5_000_000 -> 3
        pop >= 1_000_000 -> 4
        pop >= 500_000 -> 5
        pop >= 200_000 -> 6
        pop >= 100_000 -> 7
        pop >= 50_000 -> 8
        pop >= 20_000 -> 9
        pop >= 10_000 -> 10
        pop >= 2_000 -> 11
        else -> 12
    }
}
