package earth.worldwind.layer.mvt

import earth.worldwind.WorldWind
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.globe.Globe
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Offset
import earth.worldwind.geom.OffsetMode
import earth.worldwind.layer.AbstractLayer
import earth.worldwind.layer.VectorLayer
import earth.worldwind.layer.buildings.OsmBuilding
import earth.worldwind.layer.buildings.OsmBuildingsTile
import earth.worldwind.layer.source.TileSource
import earth.worldwind.layer.mercator.MercatorSector
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
import earth.worldwind.util.LruMemoryCache
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.INFO
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.sinh
import kotlin.math.tan
import kotlin.time.Clock
import kotlin.concurrent.Volatile
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sqrt

/**
 * Renders Mapbox Vector Tile (MVT) data as flat surface-draped polygons and polylines.
 *
 * Pipeline: HTTP fetch → protobuf decode → command-stream geometry → Web-Mercator
 * unprojection → batched surface drawables (one VBO+EBO per tile per [Globe.State]).
 *
 * Tile management — center-spiral selection, LRU + pending + exponential backoff, scope
 * cancellation on close — mirrors [earth.worldwind.layer.buildings.OsmBuildingsLayer].
 * Appearance is delegated to [style]; feature → shape conversion lives in [toRenderables].
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
     * Minimum slippy-map zoom the layer will request. Below this altitude band the layer
     * stops fetching — features at lower zooms are visually unreadable at globe-spanning
     * distances and the imagery basemap covers the same role.
     */
    val minZoom: Int = 2,
    /**
     * Maximum slippy-map zoom the layer will request. Most OpenMapTiles-schema servers
     * stop publishing past z14; raising this just produces 404s and triggers backoff.
     */
    val maxZoom: Int = 14,
    val tileRadius: Int = 3,
    val maxLoadedTiles: Int = 128,
    // 2 keeps tile arrivals trickling in instead of arriving in bursts of 4 that all trigger
    // first-render GL-buffer-upload on the same frame. Raise if your tile server can handle
    // higher concurrency and you'd rather minimize cold-start latency than smooth zoom.
    maxConcurrentFetches: Int = 2,
    /** Layer-name → [ShapeAttributes] resolver. Swap to drive a custom appearance. */
    var style: MvtStyle = DefaultMvtStyle,
    /**
     * When true (default), POLYGON features in a tile are packed into one
     * [MvtBatchedPolygonTile] and LINESTRING features into one [MvtBatchedLineTile] —
     * one VBO + one EBO per tile per [Globe.State], sub-draws bucketed by (color, width).
     *
     * Set false to fall back to per-feature [Polygon]/[Path] instances — required when
     * overriding [toRenderables] to attach per-feature pick metadata or unique attribute
     * mutations the batched path doesn't carry.
     */
    val useBatchedRendering: Boolean = true,
    /**
     * Optional sprite atlas used to resolve `icon-image` paint to a per-icon image source.
     * When null, rules with icon paint are silently skipped — useful when you want the
     * label-only render path without the per-icon image fetches. Load via
     * [MvtSpriteAtlasLoader.load] or construct directly from bundled assets.
     */
    var spriteAtlas: MvtSpriteAtlas? = null,
    displayName: String? = "Vector Tiles",
) : AbstractLayer(displayName), VectorLayer {

    private val semaphore = Semaphore(maxConcurrentFetches.coerceAtLeast(1))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val results = Channel<TileResult>(capacity = Channel.UNLIMITED)
    private val tiles = object : LruMemoryCache<TileKey, List<Renderable>>(maxLoadedTiles.toLong()) {
        override fun entryRemoved(key: TileKey, oldValue: List<Renderable>, newValue: List<Renderable>?, evicted: Boolean) {
            cachedValues.remove(oldValue)
            // Drop any feature-state held for features sourced from this tile — the
            // MvtPickedFeature key embeds the tile, so we can prune by matching the tile
            // coord triple. Done in the LRU callback so memory stays bounded under churn.
            if (featureStates.isNotEmpty()) {
                featureStates.entries.removeAll { it.key.tile == key }
            }
            // Batched tiles own their GL buffers directly; the legacy per-feature path drops
            // to RenderResourceCache LRU pressure normally. Release proactively to keep the
            // KGSL shared-memory pool from filling with stale buffers.
            val rc = latestRc ?: return
            for (r in oldValue) {
                try {
                    when (r) {
                        is MvtBatchedPolygonTile -> r.releaseRenderResources(rc)
                        is MvtBatchedLineTile -> r.releaseRenderResources(rc)
                    }
                } catch (_: Exception) {}
            }
        }
    }
    private val pending = HashSet<TileKey>()
    private val backoff = HashMap<TileKey, BackoffEntry>()
    private var isClosed = false
    // RC seen on the most recent doRender — read by [tiles].entryRemoved on eviction so the
    // batched tile's GL buffers can be released. doRender + LRU put + entryRemoved all run on
    // the render thread, so this is safe without synchronisation.
    private var latestRc: RenderContext? = null

    /**
     * Most recent [Globe] / [Globe.State] snapshot, written each [doRender] and read by the
     * fetch coroutine on `Dispatchers.Default` so tile geometry can be pre-assembled off the
     * render thread. Tiles fetched before any [doRender] has run see null here and skip
     * pre-assembly; the render thread then assembles on first paint.
     */
    @Volatile
    private var capturedGlobe: Globe? = null
    @Volatile
    private var capturedGlobeState: Globe.State? = null

    init {
        // Surface-clamped vector geometry past ~150 km is a visual nothing — features are too
        // dense to read and too small to distinguish from the imagery basemap. Gate to keep
        // background fetches from competing with imagery tile traffic when the user is
        // looking at hemispheres.
        maxActiveAltitude = 150_000.0
    }

    override fun doRender(rc: RenderContext) {
        latestRc = rc
        capturedGlobe = rc.globe
        capturedGlobeState = rc.globeState
        drainResults()

        // Centre on lookAt when available (true ground point under the camera focus); fall
        // back to the camera's footprint lat/lon. Same heuristic as OsmBuildingsLayer.
        val center = rc.lookAtPosition ?: rc.camera.position
        val tileZoom = hysteresisTileZoom(rc).coerceIn(minZoom, maxZoom)
        // Real-valued camera zoom — [cameraZoomScale] reads this to derive the within-tile
        // zoom delta so line widths scale smoothly between tile-zoom boundaries.
        lastCameraZoomReal = selectTileZoomReal(rc)
        val n = 1 shl tileZoom
        val (cx, cy) = lonLatToTile(center.longitude.inDegrees, center.latitude.inDegrees, tileZoom)

        // Tracks which ancestor tiles have already been rendered this frame so we don't
        // paint the same parent multiple times when several missing-children share it. Used
        // by the zoom-transition fallback path in [processTile].
        renderedAncestorsThisFrame.clear()
        // Reset the per-frame label group collection. processTile fills this; the global
        // collision pass at the end drains it.
        activeLabelGroups.clear()

        // Centre-first spiral so the tile under the user's view target enters the fetch
        // queue ahead of corner tiles.
        for ((dx, dy) in spiralOffsets) {
            val y = cy + dy
            if (y !in 0 until n) continue
            val x = ((cx + dx) % n + n) % n
            processTile(rc, TileKey(tileZoom, x, y))
        }

        // Cross-tile label collision + frame-to-frame stickiness. Runs once per frame across
        // all visible tile groups; assigns each group an [MvtLabelGroup.enabledMask], then
        // renders the groups in deferred order (after all polygons + lines so labels stay
        // on top).
        //
        // Safety cap: if the total candidate count exceeds [MAX_CROSS_TILE_LABELS], the
        // layer falls back to each group's own per-tile collision. The global pass is O(N²)
        // and would dominate the frame budget at unbounded N (1000+ candidates → 1M ops);
        // per-tile collision is O(M²) per group where M is much smaller, visually slightly
        // worse at tile borders but stays under budget.
        if (activeLabelGroups.isNotEmpty()) {
            val totalLabels = activeLabelGroups.sumOf { it.labels.size }
            if (totalLabels > MAX_CROSS_TILE_LABELS) {
                labelCollider.disableAll(activeLabelGroups)
            } else {
                labelCollider.run(rc, activeLabelGroups, totalLabels)
            }
            for (g in activeLabelGroups) {
                try {
                    g.render(rc)
                } catch (e: Exception) {
                    logMessage(ERROR, "MvtVectorLayer", "doRender", "label group render failed", e)
                }
            }
        }
        labelCollider.endFrame()
    }

    // Per-frame scratch — see [doRender] / [processTile] for the rationale. Lives on the
    // layer so the [HashSet] is reused frame-to-frame and we don't allocate per render pass.
    private val renderedAncestorsThisFrame = HashSet<TileKey>()

    // Per-frame collection of [MvtLabelGroup]s from all visible tiles — populated during
    // [processTile], drained by the global collision pass at the end of [doRender]. Kept
    // as a field to avoid per-frame allocation.
    private val activeLabelGroups = ArrayList<MvtLabelGroup>()
    // Owns all the cross-frame stickiness state + scratch buffers for the global collision
    // pass. Reused frame-to-frame.
    private val labelCollider = MvtLabelCollider()

    /**
     * Pick a slippy-map zoom level for the current camera. Override to change the
     * altitude/zoom mapping or to pin a fixed zoom (return a constant, ignoring [rc]).
     *
     * Default formula: solve for the zoom that makes one tile span ~256 screen pixels at
     * the camera's distance to the view target. Equivalent to:
     *
     *   metersPerPixel = pixelSizeAtDistance(distanceToTarget)
     *   zoom          = log2(equatorialCircumference / (256 * metersPerPixel))
     *
     * Result is clamped by the caller to `[minZoom, maxZoom]`. Note that the layer applies
     * **hysteresis** on top of this — see [hysteresisTileZoom] — so a one-frame change here
     * doesn't immediately trigger a tile-zoom switch.
     */
    protected open fun selectTileZoom(rc: RenderContext): Int = selectTileZoomReal(rc).toInt()

    /**
     * Real-valued (continuous) tile zoom from the camera. Same formula as [selectTileZoom],
     * unfloored. Used by [cameraZoomScale] to derive a smooth line-width multiplier between
     * tile-zoom boundaries.
     */
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

    /**
     * Hysteresis filter over [selectTileZoom]. Returns the currently-active tile zoom unless
     * the camera has been at a different integer zoom level for at least [zoomStableFrames]
     * consecutive frames. Prevents pinch-zoom from thrashing the fetch queue when the camera
     * teeters around a zoom boundary.
     *
     * Override [zoomStableFrames] to disable (set to 0) or tighten the response.
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
     * Multiplier applied to surface-shape line widths each frame to compensate for the
     * difference between the tile's baked zoom and the camera's current real zoom. With this
     * in place, lines smoothly fatten as the user zooms in to the same tile, rather than
     * jumping when a new-zoom tile arrives.
     *
     * Mathematically: lines at tile zoom Z look 2× thicker at camera zoom Z+1 (one zoom step
     * doubles screen-space-per-meter), so the scale is `2^(cameraZoom - tileZoom)`. Clamped
     * to `[0.5, 2.0]` so a long lag between cameras can't blow widths up unbounded.
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

    // Hysteresis state — last-committed tile zoom, currently-pending zoom under observation,
    // and the consecutive-frame count of that pending zoom.
    private var activeTileZoom: Int = -1
    private var pendingTileZoom: Int = -1
    private var pendingFrameCount: Int = 0
    // Tracks the most recent real-valued zoom for [cameraZoomScale].
    private var lastCameraZoomReal: Double = 0.0

    private val spiralOffsets: List<Pair<Int, Int>> = buildList {
        for (dy in -tileRadius..tileRadius) {
            for (dx in -tileRadius..tileRadius) add(dx to dy)
        }
    }.sortedBy { (dx, dy) -> maxOf(abs(dx), abs(dy)) }

    private fun processTile(rc: RenderContext, key: TileKey) {
        val tile = tiles[key]
        if (tile != null) {
            val scale = cameraZoomScale()
            try {
                for (r in tile) {
                    // Push the per-frame camera-zoom interpolation factor into batched line
                    // tiles so their drawState.lineWidth scales smoothly between baked tile
                    // zooms — no EBO change needed. Polygon tiles and label groups don't
                    // benefit (per-vertex / baked-attributes).
                    if (r is MvtBatchedLineTile) r.widthScale = scale
                    if (r is MvtLabelGroup) {
                        // Defer label rendering to the layer's global collision pass; the
                        // group's local collision will get bypassed via its [enabledMask].
                        activeLabelGroups += r
                    } else {
                        r.render(rc)
                    }
                }
            } catch (e: Exception) {
                logMessage(ERROR, "MvtVectorLayer", "doRender", "Render failure for tile $key", e)
            }
            return
        }

        // Tile missing — render the coarsest cached ancestor so the user doesn't see a blank
        // during zoom transitions. Each frame paints any given ancestor at most once (via the
        // [renderedAncestorsThisFrame] set), so a parent shared by many missing children
        // doesn't multiply the draw cost. Includes the ancestor's [MvtLabelGroup]; parent-
        // zoom labels are slightly off-scale but visually preferable to dropping them
        // entirely during a zoom transition.
        val ancestor = findCachedAncestor(key)
        if (ancestor != null && renderedAncestorsThisFrame.add(ancestor.first)) {
            val scale = cameraZoomScale()
            try {
                for (r in ancestor.second) {
                    if (r is MvtBatchedLineTile) r.widthScale = scale
                    if (r is MvtLabelGroup) activeLabelGroups += r else r.render(rc)
                }
            } catch (e: Exception) {
                logMessage(
                    ERROR, "MvtVectorLayer", "doRender",
                    "Render failure for ancestor ${ancestor.first}", e,
                )
            }
        }

        if (!isClosed && !isInBackoff(key) && pending.add(key)) {
            scope.launch { fetch(key) }
        }
    }

    /**
     * Walk up the slippy-tile pyramid from [key]'s parent, returning the closest cached
     * ancestor (or null if none exist). Used to paint a coarse tile in the gap while the
     * requested fine tile is fetching.
     */
    private fun findCachedAncestor(key: TileKey): Pair<TileKey, List<Renderable>>? {
        var z = key.z - 1
        var x = key.x shr 1
        var y = key.y shr 1
        while (z >= minZoom) {
            val parentKey = TileKey(z, x, y)
            val parent = tiles[parentKey]    // bumps MRU so the ancestor stays fresh
            if (parent != null) return parentKey to parent
            z--; x = x shr 1; y = y shr 1
        }
        return null
    }

    private fun isInBackoff(key: TileKey): Boolean {
        val entry = backoff[key] ?: return false
        return Clock.System.now().toEpochMilliseconds() < entry.nextRetryEpochMs
    }

    /** Cancels in-flight fetches and frees the cache. Idempotent. The layer cannot be reused after [close]. */
    open fun close() {
        if (isClosed) return
        isClosed = true
        scope.cancel()
        results.close()
        // [LruMemoryCache.clear] doesn't fire [entryRemoved] (see its impl) — release batched
        // tiles' GL buffers ourselves while [latestRc] is still valid. `close` is normally
        // called from the render thread or just before context teardown.
        val rc = latestRc
        if (rc != null) {
            for (value in cachedValues) {
                for (r in value) {
                    try {
                        when (r) {
                            is MvtBatchedPolygonTile -> r.releaseRenderResources(rc)
                            is MvtBatchedLineTile -> r.releaseRenderResources(rc)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        cachedValues.clear()
        tiles.clear()
        pending.clear()
        backoff.clear()
        featureStates.clear()
        // Release the source's per-instance resources (e.g. UrlTemplateMvtTileSource's shared
        // HttpClient + OkHttp pool). Sources that need no cleanup default to a no-op.
        try { source.close() } catch (_: Exception) {}
    }

    /**
     * Parallel set tracking values currently in [tiles]. [LruMemoryCache] doesn't expose its
     * values; we need them here to fire batched tiles' GPU release on [close]. Kept in sync
     * via [drainResults] (insert) and the `entryRemoved` override on [tiles] (remove). Same
     * workaround [earth.worldwind.layer.buildings.OsmBuildingsLayer] uses for the same reason.
     */
    private val cachedValues = HashSet<List<Renderable>>()

    private fun drainResults() {
        while (true) {
            val result = results.tryReceive().getOrNull() ?: return
            pending.remove(result.key)
            val value = result.value
            if (value == null) {
                val entry = backoff.getOrPut(result.key) { BackoffEntry() }
                entry.failCount++
                val delayMs = backoffDelayMs(entry.failCount)
                entry.nextRetryEpochMs = Clock.System.now().toEpochMilliseconds() + delayMs
                scheduleBackoffRedraw(delayMs)
                continue
            }
            backoff.remove(result.key)
            cachedValues.add(value)
            tiles.put(result.key, value, 1)
        }
    }

    private fun scheduleBackoffRedraw(delayMs: Long) {
        scope.launch {
            delay(delayMs)
            WorldWind.requestRedraw()
        }
    }

    /**
     * Tile acquisition. Cache-backed sources serve hits cheaply via [TileSource.tryReadCachedTile]
     * (no [semaphore] permit so cached neighbours aren't stalled behind a slow network fetch);
     * misses and plain network sources fall through to [TileSource.fetchTile]. Empty blob →
     * `null` so [fetch] can substitute an empty [MvtTile].
     */
    private suspend fun loadTileViaCache(key: TileKey): MvtTile? {
        val src = source
        val blob = src.tryReadCachedTile(key.z, key.x, key.y)
            ?: semaphore.withPermit { src.fetchTile(key.z, key.x, key.y) }
            ?: return null
        return if (blob.isEmpty) null else MvtDecoder.decode(blob.bytes)
    }

    private suspend fun fetch(key: TileKey) {
        val value = try {
            // Network concurrency is throttled inside [loadTileViaCache] (network path only) so
            // cache hits aren't gated behind it. Decode + renderable building run unthrottled —
            // CPU work already bounded by the Default dispatcher; gating it here would
            // re-introduce the stall for cached tiles.
            val tile = loadTileViaCache(key) ?: MvtTile(emptyList())
            val renderables = toRenderables(key, tile)
            // One-time INFO with the tile's layer/feature breakdown so a style/schema
            // mismatch (e.g. OpenMapTiles style against a Shortbread server) is visible
            // even when the fetch succeeds with zero visible renderables.
            // Schema detection re-runs while the result is UNKNOWN — an empty first
            // tile (404 / 5xx → MvtTile(emptyList())) would otherwise lock the schema
            // to UNKNOWN for the layer's lifetime.
            if (tile.layers.isNotEmpty() && detectedSchema.let { it == null || it == MvtSchemaDetector.Schema.UNKNOWN }) {
                detectedSchema = MvtSchemaDetector.detect(tile)
            }
            if (!firstFetchLogged) {
                firstFetchLogged = true
                val layerSummary = tile.layers.joinToString { "${it.name}=${it.features.size}" }
                logMessage(
                    INFO, "MvtVectorLayer", "fetch",
                    "First tile $key decoded: schema=$detectedSchema, layers=[$layerSummary], renderables=${renderables.size}",
                )
            }
            renderables
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logMessage(
                WARN, "MvtVectorLayer", "fetch",
                "Failed to fetch tile $key: ${e::class.simpleName}: ${e.message}\n${e.stackTraceToString()}",
            )
            null
        }
        results.trySend(TileResult(key, value))
        WorldWind.requestRedraw()
    }

    @Volatile
    private var firstFetchLogged: Boolean = false

    /** Count of tiles currently in the LRU cache (visible + fringe). Best-effort, may briefly
     *  disagree with reality when the fetch coroutine is mid-update. */
    val loadedTileCount: Int get() = tiles.entryCount
    /** Count of tiles whose fetch coroutine is in flight. */
    val pendingTileCount: Int get() = pending.size
    /** Count of tile keys currently in exponential-backoff (recently failed). */
    val backoffTileCount: Int get() = backoff.size

    /**
     * Schema reported by [MvtSchemaDetector] for the first non-empty decoded tile. `null`
     * until detection succeeds; re-runs as long as detection stays UNKNOWN to handle empty
     * first-tile responses. Useful for surfacing a "loaded <schema>-flavoured tiles" status
     * in UI, or for selecting a matching style at runtime.
     */
    @Volatile
    var detectedSchema: MvtSchemaDetector.Schema? = null
        private set

    // Per-feature dynamic state read by ["feature-state", key] expressions. Keyed by the
    // MvtPickedFeature instance handed out at pick time; values are arbitrary so callers can
    // store booleans (hover/select), animation phase, severity, etc.
    private val featureStates = HashMap<MvtPickedFeature, MutableMap<String, Any?>>()

    /**
     * Set dynamic feature state read by `["feature-state", key]` expressions. After
     * mutating state for already-styled features, call [invalidate] (or wait for natural
     * tile churn) to force re-styling — feature-state isn't continuously re-evaluated on
     * every frame, only at tile-resolve time.
     */
    fun setFeatureState(feature: MvtPickedFeature, key: String, value: Any?) {
        val map = featureStates.getOrPut(feature) { HashMap() }
        if (value == null) map.remove(key) else map[key] = value
        if (map.isEmpty()) featureStates.remove(feature)
    }

    /** Read all state set for [feature]. Returns null when no state has been set. */
    fun featureState(feature: MvtPickedFeature): Map<String, Any?>? = featureStates[feature]

    /**
     * Drop all cached tiles so [toRenderables] re-runs on the next render. Use after
     * mutating [setFeatureState] in bulk.
     */
    fun invalidate() {
        tiles.clear()
        WorldWind.requestRedraw()
    }

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
        val tileLabelPriorities = ArrayList<Int>()
        val tileLabelSizes = ArrayList<Int>()

        // Rule-based styles want the tile zoom passed through so zoom-interpolated paint
        // properties (line widths, palette shifts) resolve correctly. Hand-coded MvtStyle
        // implementations don't read zoom — they go through the single-arg overload.
        val ruleStyle = style as? MvtRuleBasedStyle

        for (layer in tile.layers) {
            for (feature in layer.features) {
                val props = feature.toProperties(layer)

                // Rule lookup is split between shape and text paint roles. A single feature
                // can be matched by independent rules for its stroke and its label — Mapbox
                // GL works the same way (each `layer` in a style document evaluates against
                // all features independently). For hand-coded MvtStyle implementations the
                // text rule is always null (text payload isn't carried by that interface).
                // Per-feature pick payload. Built only when the layer is pick-enabled so styles
                // with picking disabled don't pay for the property-map retention. Doubles as
                // the lookup key for [featureStates] — `["feature-state", ...]` expressions
                // see this feature's dynamic state when resolving paint.
                val pickPayload = if (isPickEnabled)
                    MvtPickedFeature(layer.name, feature.type, props, key)
                else null
                val featureState = pickPayload?.let { featureStates[it] }
                val shapeRule: MvtStyleRule?
                val textRule: MvtStyleRule?
                val zOrder: Int
                val shapeAttrs: ShapeAttributes?
                if (ruleStyle != null) {
                    shapeRule = ruleStyle.firstMatching(layer.name, feature.type, key.z, props) { it.paint.hasShape }
                    textRule = ruleStyle.firstMatching(layer.name, feature.type, key.z, props) { it.paint.hasText }
                    if (shapeRule == null && textRule == null) continue
                    val ruleForZ = shapeRule ?: textRule!!
                    zOrder = ruleForZ.zOrder
                    shapeAttrs = shapeRule?.resolve(key.z, props, featureState, feature.type)
                } else {
                    shapeRule = null
                    textRule = null
                    val attrs = style.styleFor(layer.name, feature.type, props) ?: continue
                    zOrder = style.zOrderFor(layer.name, feature.type, props)
                    shapeAttrs = attrs
                }
                // Used below: matchedRule for POINT label resolution stays the textRule when
                // present (where the text payload lives).
                val matchedRule = textRule ?: shapeRule
                when (feature.type) {
                    MvtGeometryType.POLYGON -> {
                        val attrs = shapeAttrs ?: continue
                        val rings = MvtGeometry.decodePolygons(feature, key.z, key.x, key.y, layer.extent)
                        // 3D extrusion path — collect buildings into per-attribute buckets that
                        // emit as OsmBuildingsTile renderables below.
                        val extrusion = shapeRule?.paint?.buildExtrusion(key.z, props, featureState, feature.type)
                        if (extrusion != null) {
                            val (height, base) = extrusion
                            val bucket = extrusionBuckets.getOrPut(attrs.interiorColor) {
                                ExtrusionBucket(ShapeAttributes(attrs))
                            }
                            for (poly in rings) {
                                if (poly.outer.size < 3) continue
                                bucket.buildings += OsmBuilding(
                                    id = "mvt-${key.z}-${key.x}-${key.y}-${bucket.buildings.size}",
                                    outerRing = poly.outer,
                                    innerRings = poly.holes,
                                    height = height,
                                    minHeight = base,
                                )
                            }
                            continue
                        }
                        for (poly in rings) {
                            if (poly.outer.size < 3) continue
                            if (polygonBatch != null) {
                                polygonBatch += MvtBatchedPolygonTile.BatchFeature(
                                    outer = poly.outer, holes = poly.holes,
                                    attributes = attrs, zOrder = zOrder,
                                    pickPayload = pickPayload,
                                )
                            } else {
                                val p = Polygon(poly.outer, attrs).apply {
                                    altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                                    isFollowTerrain = true
                                    pathType = PathType.LINEAR
                                    this.zOrder = zOrder.toDouble()
                                }
                                for (hole in poly.holes) if (hole.size >= 3) p.addBoundary(hole)
                                out += p
                            }
                        }
                    }
                    MvtGeometryType.LINESTRING -> {
                        val lines = MvtGeometry.decodeLines(feature, key.z, key.x, key.y, layer.extent)
                        // Stroke geometry (when the rule has fill/line paint).
                        if (shapeAttrs != null) {
                            // Resolve casing once per feature; emit a wider stroke at zOrder-1
                            // so it paints under the main line.
                            val casingAttrs = shapeRule?.paint?.buildCasing(key.z, props, featureState, feature.type)
                            // Loop-invariant: gradient-rule detection happens once per feature,
                            // not per polyline-within-feature.
                            val gradientRule = shapeRule?.takeIf { it.paint.hasLineGradient }
                            for (line in lines) {
                                if (line.size < 2) continue
                                // Casing always emits to the batched path (or as its own Path
                                // when batching is off).
                                if (casingAttrs != null) {
                                    if (lineBatch != null) {
                                        lineBatch += MvtBatchedLineTile.BatchLineFeature(
                                            positions = line, attributes = casingAttrs,
                                            zOrder = zOrder - 1, pickPayload = pickPayload,
                                        )
                                    } else {
                                        val casingPath = Path(line, casingAttrs).apply {
                                            altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                                            isFollowTerrain = true
                                            pathType = PathType.LINEAR
                                            this.zOrder = (zOrder - 1).toDouble()
                                        }
                                        lineRenderables?.let { it += (zOrder - 1) to casingPath }
                                            ?: run { out += casingPath }
                                    }
                                }
                                // Line-gradient path: subdivide the polyline into
                                // GRADIENT_SUBDIVISIONS arc-length segments, sample the
                                // gradient at each segment's midpoint, emit each as its own
                                // batched feature with its sampled color. Falls through to
                                // the default solid-color path when no gradient.
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
                                // Dashed features render through per-feature [Path]: the
                                // batched line tile's vertex layout sets texCoord1d = 0 on
                                // every corner, so even with the right stipple texture bound
                                // every fragment would sample the same texel and the line
                                // would appear solid. Path computes proper per-vertex
                                // texCoord + texCoordMatrix via computeRepeatingTexCoordTransform.
                                val forceUnbatched = shapeAttrs.outlineImageSource != null
                                if (lineBatch != null && !forceUnbatched) {
                                    lineBatch += MvtBatchedLineTile.BatchLineFeature(
                                        positions = line, attributes = shapeAttrs, zOrder = zOrder,
                                        pickPayload = pickPayload,
                                    )
                                } else {
                                    val path = Path(line, shapeAttrs).apply {
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
                                for (line in lines) {
                                    if (line.size < 2) continue
                                    out += MvtCurvedLineLabel(
                                        polyline = line,
                                        text = labelSpec.text,
                                        attributes = labelSpec.attributes,
                                        charWidths = charWidths,
                                        textWidth = textWidth,
                                        pixelSize = labelSpec.pixelSize,
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
                        val points = MvtGeometry.decodePoints(feature, key.z, key.x, key.y, layer.extent)
                        // Shield path: when a rule has BOTH icon and text, render them as
                        // ONE composite Placemark (Mapbox's symbol-with-text-on-icon =
                        // highway shield). Otherwise emit a Label and/or Placemark separately.
                        val isShield = iconSpec != null && labelSpec != null
                        for (pt in points) {
                            if (labelSpec != null && !isShield) {
                                val label = Label(pt, labelSpec.text, labelSpec.attributes).apply {
                                    altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                                    this.zOrder = zOrder.toDouble()
                                }
                                tileLabels += label
                                tileLabelPriorities += zOrder
                                tileLabelSizes += labelSpec.pixelSize
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
                                        // icon-text-fit: width — stretch the shield only when
                                        // the label is wider than the icon's reserved content
                                        // area (70% of native width). The extra factor goes
                                        // into imageScaleX so the shield gets WIDER but stays
                                        // the same HEIGHT.
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

        // Captured globe snapshot for off-thread pre-assembly. Null until the first
        // [doRender] runs; pre-assembly is skipped in that case and the render thread
        // assembles lazily on first paint.
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
            // Off-thread tessellation. GLU instance is fresh per call, math is pure ECEF —
            // moves the expensive work out of the render thread so zoom storms don't stall.
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
                displayName = "mvt-labels-${key.z}-${key.x}-${key.y}",
            )
        }
        return out
    }

    /**
     * Subdivide [line] into [MvtStyleRule.PaintSpec.GRADIENT_SUBDIVISIONS] arc-length
     * segments, sample [rule]'s gradient at each segment's midpoint, and emit each segment
     * as its own batched feature with the sampled color. Visually approximates a smooth
     * gradient with piecewise-constant segments; render cost scales linearly with the
     * subdivision count.
     */
    private fun emitGradientSegments(
        line: List<Position>,
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
        val n = line.size
        val cum = DoubleArray(n)
        for (i in 1 until n) {
            val dy = line[i].latitude.inDegrees - line[i - 1].latitude.inDegrees
            val dx = line[i].longitude.inDegrees - line[i - 1].longitude.inDegrees
            cum[i] = cum[i - 1] + sqrt(dx * dx + dy * dy)
        }
        val total = cum.last()
        if (total <= 0.0) return
        // For each segment 0..k-1, gather the sub-polyline between progress fractions
        // i/k and (i+1)/k by interpolating endpoints at exact arc-length boundaries and
        // including any waypoints that fall within.
        var startIdx = 0
        var prevPosition = line[0]
        for (i in 0 until k) {
            val startT = i.toDouble() / k
            val endT = (i + 1).toDouble() / k
            val endArc = total * endT
            // Build the sub-polyline.
            val sub = ArrayList<Position>(8)
            sub += prevPosition
            // Walk forward through waypoints that fall before endArc.
            var j = startIdx + 1
            while (j < n && cum[j] < endArc) { sub += line[j]; j++ }
            // Interpolate the segment's end position at exact endArc (if there's a next
            // waypoint to interpolate against; otherwise use the last waypoint).
            val endPosition = if (j < n) interpolatePositionAtArc(line, cum, endArc, j - 1) else line[n - 1]
            sub += endPosition
            if (sub.size >= 2) {
                val midProgress = (startT + endT) * 0.5
                val color = rule.paint.buildGradientColor(
                    zoom, midProgress, properties, featureState, geometryType,
                )
                if (color != null) {
                    val segAttrs = ShapeAttributes(baseAttrs).apply {
                        outlineColor = color
                    }
                    if (lineBatch != null) {
                        lineBatch += MvtBatchedLineTile.BatchLineFeature(
                            positions = sub, attributes = segAttrs, zOrder = zOrder,
                            pickPayload = pickPayload,
                        )
                    } else {
                        val path = Path(sub, segAttrs).apply {
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
            prevPosition = endPosition
            startIdx = if (j < n && cum[j] == endArc) j else j - 1
        }
    }

    /**
     * Linearly interpolate a [Position] at arc-length [arc] within the segment
     * `[segIdx, segIdx + 1]` of [line], using [cum] (cumulative arc lengths).
     */
    private fun interpolatePositionAtArc(
        line: List<Position>, cum: DoubleArray, arc: Double, segIdx: Int,
    ): Position {
        val i = segIdx.coerceIn(0, line.size - 2)
        val segStart = cum[i]
        val segLen = cum[i + 1] - segStart
        val u = if (segLen <= 0.0) 0.0 else ((arc - segStart) / segLen).coerceIn(0.0, 1.0)
        val a = line[i]
        val b = line[i + 1]
        return Position(
            (a.latitude.inDegrees + (b.latitude.inDegrees - a.latitude.inDegrees) * u).degrees,
            (a.longitude.inDegrees + (b.longitude.inDegrees - a.longitude.inDegrees) * u).degrees,
            a.altitude + (b.altitude - a.altitude) * u,
        )
    }

    private class ExtrusionBucket(val attributes: ShapeAttributes) {
        val buildings = ArrayList<OsmBuilding>()
    }

    /** Slippy-map tile coordinate triple used as the [tiles] LRU key. */
    data class TileKey(val z: Int, val x: Int, val y: Int) {
        val sector: Sector get() = tileToSector(z, x, y)
    }

    private data class TileResult(val key: TileKey, val value: List<Renderable>?)

    private class BackoffEntry {
        var failCount: Int = 0
        var nextRetryEpochMs: Long = 0L
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

        // Above this candidate count the global O(N²) cross-tile collision is replaced with
        // each group's own O(M²) per-tile pass — visually slightly worse at tile borders,
        // but stays under the frame budget when label density spikes.
        private const val MAX_CROSS_TILE_LABELS: Int = 300

        // Exponential backoff: 2 s, 5 s, 15 s, 60 s cap. Same schedule as OsmBuildingsLayer —
        // conservative enough for a recovering tile mirror, aggressive enough that transient
        // failures retry quickly.
        private fun backoffDelayMs(failCount: Int): Long = when (failCount) {
            1 -> 2_000
            2 -> 5_000
            3 -> 15_000
            else -> 60_000
        }

        fun lonLatToTile(lonDegrees: Double, latDegrees: Double, zoom: Int): Pair<Int, Int> {
            val n = 1 shl zoom
            val x = ((lonDegrees + 180.0) / 360.0 * n).toInt()
            val latRad = latDegrees.coerceIn(-MercatorSector.MAX_LATITUDE_DEG, MercatorSector.MAX_LATITUDE_DEG) * PI / 180.0
            val y = ((1.0 - asinh(tan(latRad)) / PI) / 2.0 * n).toInt()
            return x to y
        }

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
