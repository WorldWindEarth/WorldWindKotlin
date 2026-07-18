package earth.worldwind.layer

import earth.worldwind.WorldWind
import earth.worldwind.geom.Sector
import earth.worldwind.globe.Globe
import earth.worldwind.render.RenderContext
import earth.worldwind.util.LevelSet
import earth.worldwind.util.LruMemoryCache
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.Tile
import earth.worldwind.util.TileFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlin.concurrent.Volatile

/**
 * Reusable base for tiled vector layers (WFS features, MVT vector tiles, OSM buildings). Owns the
 * parts every such layer shares so they aren't reimplemented per layer:
 *
 *  * **Selection** — a screen-space-error quadtree over a [LevelSet] (`Tile.mustSubdivide` +
 *    frustum culling), the same machinery the imagery layer uses; the tile scheme (geographic vs
 *    Web-Mercator slippy) is the caller's [LevelSet]/[TileFactory].
 *  * **Transitions** — each frame [doRender] assembles a single non-overlapping **cut**: for every
 *    visible ground cell it draws the best-available tile, substituting a coarser ancestor (zoom-in)
 *    or finer resident descendants (zoom-out) while the ideal tile loads. A tile is RENDERED *or*
 *    REFINED, never both, so there is no blank gap and no double-paint of translucent fills.
 *  * **Lifecycle** — an LRU content cache, an in-flight `pending` set, exponential backoff, an
 *    off-thread fetch → render-thread drain channel, stale-while-revalidate invalidation, and
 *    globe-state invalidation.
 *
 * Subclasses provide only the content type [C] and three things: how to [loadTileContent], how to
 * [renderTileContent], and (if the content owns GPU buffers) how to release it in [onContentEvicted].
 * The thin per-frame seams [beginFrame]/[endFrame]/[prepareTileForRender] cover cross-tile passes
 * (e.g. MVT label collision) and per-frame content tweaks (e.g. MVT line zoom-scale). Always [close].
 *
 * @param C the per-tile content type held in the cache (e.g. `List<Renderable>`, a batched tile).
 */
abstract class TiledVectorLayer<C : Any>(
    protected val levelSet: LevelSet,
    protected val tileFactory: TileFactory,
    maxLoadedTiles: Int = 256,
    maxConcurrentFetches: Int = 4,
    displayName: String? = null,
    /** When `> 0`, the resident-tile LRU is bounded by summed [contentWeight] (GPU bytes) with this
     *  budget instead of by tile count — so eviction reacts to real GPU-buffer footprint, not a flat
     *  count that a few dense tiles can blow past. `0` (default) keeps the tile-count bound
     *  ([maxLoadedTiles]); subclasses whose content owns big per-tile VBO/EBOs pass a byte budget. */
    maxLoadedTileBytes: Long = 0,
    /** When `> 0`, at most this much freshly-fetched content weight (same unit as [contentWeight]:
     *  GPU bytes when [maxLoadedTileBytes] is set, else tiles) is admitted into the cache per frame;
     *  the rest waits for the next frame (a redraw is requested). Spreads a pan's burst of new tiles —
     *  and their GPU uploads — across several frames instead of one multi-hundred-ms hitch. `0`
     *  (default) admits everything each frame (legacy behaviour). */
    private val maxTileAdmitWeightPerFrame: Int = 0,
) : AbstractLayer(displayName), VectorLayer {

    // Real data availability sector can be smaller than the tile-grid sector
    final override val sector = Sector().setFullSphere()

    /** Screen-space-error knob: lower = finer (more, smaller on-screen tiles); higher = coarser. */
    var detailControl: Double = 1.0

    /** Cap on launched-but-unfinished fetch [Job]s (vs [semaphore], which gates concurrent round-trips); bounds the queue so a huge changing cut can't pile up unbounded coroutines + HTTP/SSL → OOM. Over-cap tiles re-request a later frame. */
    val maxPendingFetches: Int = 128

    /** Network concurrency gate for subclasses to wrap the round-trip in [loadTileContent] (cache
     *  hits should skip it). */
    protected val semaphore = Semaphore(maxConcurrentFetches.coerceAtLeast(1))
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Last render context — used to release GPU buffers on eviction/close on the render thread. */
    protected var latestRc: RenderContext? = null
        private set

    private val results = Channel<TileResult>(capacity = Channel.UNLIMITED)
    private val invalidations = Channel<String>(capacity = Channel.UNLIMITED)
    private val topLevelTiles = mutableListOf<Tile>()
    private val subtreeCache = LruMemoryCache<String, Array<Tile>>(2000)
    // When byteWeighted the LRU capacity is a GPU-byte budget and each entry is weighted by
    // contentWeight(); otherwise capacity is a tile count and every entry weighs 1.
    private val byteWeighted = maxLoadedTileBytes > 0
    private val tiles = object : LruMemoryCache<String, C>(
        if (byteWeighted) maxLoadedTileBytes else maxLoadedTiles.toLong()
    ) {
        override fun entryRemoved(key: String, oldValue: C, newValue: C?, evicted: Boolean) {
            liveContent.remove(key)
            // Prune the per-key invalidation epoch on a real removal with no fetch pending (keep it for put-replacements and in-flight fetches that still need it), so a long pan doesn't leak dead keys.
            if (newValue == null && !inFlight.containsKey(key)) invalidationEpoch.remove(key)
            latestRc?.let { try { onContentEvicted(it, oldValue) } catch (e: Exception) {
                logMessage(WARN, "TiledVectorLayer", "entryRemoved", "GPU release failed for tile $key", e)
            } }
        }
    }
    // Mirror of resident content keyed by tileKey — LruMemoryCache.clear() does NOT fire
    // entryRemoved, so close() iterates this to release GPU buffers.
    private val liveContent = HashMap<String, C>()
    // In-flight fetch jobs keyed by tileKey — dedups requests; cancelled only on invalidation/close.
    private val inFlight = HashMap<String, Job>()
    private val backoff = TileBackoff<String>()
    // Per-key invalidation generation. A fetch captures it at request time; [drainResults] drops a result
    // whose generation is stale (the tile was invalidated mid-fetch), so a pre-invalidation snapshot can't
    // be re-cached and pin out the revalidation — the tile re-requests instead.
    private val invalidationEpoch = HashMap<String, Int>()
    private val renderCut = ArrayList<C>()
    private var lastGlobeState: Globe.State? = null
    @Volatile private var isClosed = false

    /** Tiles currently resident in the content cache (visible + fallback). */
    val loadedTileCount: Int get() = liveContent.size
    /** Tiles with a fetch coroutine in flight. */
    val pendingTileCount: Int get() = inFlight.size
    /** Tile keys currently in fetch-failure backoff. */
    val backoffTileCount: Int get() = backoff.size

    // ---- Subclass extension points -------------------------------------------------------------

    /** OPAQUE content: keep loaded finer tiles and draw the coarser one UNDER them to fill gaps,
     *  instead of collapsing the quad to a coarse ancestor while children load (the flicker). Leave
     *  false for TRANSLUCENT fills (overlap would double-paint). */
    protected open val progressiveRefinement: Boolean get() = false

    /** When true (default), a not-yet-fully-covered quad falls back to its best-loaded coarser
     *  ancestor (under finer children if [progressiveRefinement], else via the kick). For LINE/sparse
     *  vector content a coarse ancestor shows THROUGH the finer tiles — both resolutions visible at
     *  once. Set false to keep only the finest loaded tiles per cell and leave still-loading cells
     *  blank (no ancestor ever drawn), trading a momentary gap for never showing doubled geometry. */
    protected open val coarseAncestorFallback: Boolean get() = true

    /** Fetch + build the content for one tile, off the render thread. `(z, x, y)` are the tile's
     *  `(level, column, row)`; [sector] is its geographic extent (for BBOX sources). Wrap the
     *  network round-trip in [semaphore]. Return null on failure (→ exponential backoff). */
    protected abstract suspend fun loadTileContent(z: Int, x: Int, y: Int, sector: Sector): C?

    /** Draw one tile's [content] on the render thread. Called once per tile in the frame's cut. */
    protected abstract fun renderTileContent(rc: RenderContext, content: C)

    /** Release any GPU buffers owned by evicted [content]. Default no-op (content with no GL state,
     *  e.g. renderables that the [RenderContext] resource cache evicts on its own). */
    protected open fun onContentEvicted(rc: RenderContext, content: C) {}

    /** Approximate GPU-byte footprint of [content], used to weight the resident-tile LRU when a
     *  [maxLoadedTileBytes] budget is set. Default `1` (the count-based bound). Subclasses whose
     *  content owns VBO/EBOs override this to return the tile's real buffer bytes. */
    protected open fun contentWeight(content: C): Int = 1

    /** LRU weight for [content] in the cache's active unit: [contentWeight] (GPU bytes) when a byte
     *  budget is set, else a flat `1` (tile count). Always ≥ 1 so a zero-geometry tile still occupies
     *  a slot and can be evicted. */
    private fun weightOf(content: C): Int = if (byteWeighted) contentWeight(content).coerceAtLeast(1) else 1

    /** Push per-frame state into [content] just before it's rendered (e.g. a zoom-interpolated line
     *  width). Default no-op. Runs for ancestor/descendant fallback tiles too. */
    protected open fun prepareTileForRender(rc: RenderContext, content: C) {}

    /** Per-frame setup before the cut is assembled (e.g. reset a cross-tile label collector). */
    protected open fun beginFrame(rc: RenderContext) {}

    /** Per-frame teardown after the cut is drawn (e.g. run a cross-tile label collision pass). */
    protected open fun endFrame(rc: RenderContext) {}

    /** Close the underlying tile source, if any. Called from [close]. */
    protected open fun closeSource() {}

    /** Drop a tile from the cache (thread-safe; marshalled to the render thread) and request a
     *  redraw — for stale-while-revalidate. `(z, x, y)` are `(level, column, row)`. */
    protected fun invalidateTile(z: Int, x: Int, y: Int) {
        invalidations.trySend(tileKeyOf(z, x, y))
        WorldWind.requestRedraw()
    }

    /** Drop ALL cached content (releasing GPU buffers) and abort in-flight fetches so everything
     *  reloads on the next frame — for a layer-wide restyle/invalidate. Render-thread only. */
    protected fun clearContent() {
        latestRc?.let { rc -> liveContent.forEach { (k, c) -> try { onContentEvicted(rc, c) } catch (e: Exception) {
            logMessage(WARN, "TiledVectorLayer", "releaseContent", "GPU release failed for tile $k", e)
        } } }
        liveContent.clear()
        tiles.clear()
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        backoff.clear()
        invalidationEpoch.clear() // tiles.clear() doesn't fire entryRemoved, so prune the epoch map here too
    }

    // ---- Render loop ---------------------------------------------------------------------------

    final override fun doRender(rc: RenderContext) {
        if (rc.terrain.sector.isEmpty) { WorldWind.requestRedraw(); return } // terrain not ready yet
        latestRc = rc
        drainInvalidations()
        drainResults()
        checkGlobeState(rc)
        beginFrame(rc)

        if (topLevelTiles.isEmpty()) Tile.assembleTilesForLevel(levelSet.firstLevel, tileFactory, topLevelTiles)
        renderCut.clear()
        for (i in topLevelTiles.indices) selectTileOrDescendants(rc, topLevelTiles[i])
        for (i in renderCut.indices) {
            val content = renderCut[i]
            try {
                prepareTileForRender(rc, content)
                renderTileContent(rc, content)
            } catch (e: Exception) {
                logMessage(ERROR, "TiledVectorLayer", "doRender", "Tile render failure", e)
            }
        }
        endFrame(rc)
    }

    /** Per-tile detail factor for [Tile.mustSubdivide]. Defaults to layer-wide [detailControl];
     *  override to coarsen specific tiles (e.g. the far band on a pitched view) without touching the
     *  shared cut or global [Tile]. Larger = coarser (subdivision stops earlier). */
    protected open fun effectiveDetailControl(rc: RenderContext, tile: Tile): Double = detailControl

    /**
     * Adds the best-available tiles covering [tile]'s visible area to [renderCut] and returns whether
     * that area is fully covered. Descends to the SSE-ideal level; if all children cover, keeps them;
     * otherwise truncates them (the "kick") and paints this coarser tile instead — so every ground
     * cell is painted by exactly one tile.
     */
    private fun selectTileOrDescendants(rc: RenderContext, tile: Tile): Boolean {
        if (!isVisible(rc, tile)) return true // off-screen / outside data → nothing to cover
        if (tile.level.isLastLevel || !tile.mustSubdivide(rc, effectiveDetailControl(rc, tile))) {
            contentFor(tile)?.let { renderCut.add(it); return true }
            requestTile(tile)
            return coverWithDescendants(rc, tile, MAX_FALLBACK_DEPTH) // zoom-in residue bridges the gap
        }
        val mark = renderCut.size
        val children = tile.subdivideToCache(tileFactory, subtreeCache, 4)
        var allCovered = true
        for (i in children.indices) if (!selectTileOrDescendants(rc, children[i])) allCovered = false
        if (allCovered) return true
        if (!coarseAncestorFallback) {
            // Line/sparse vector content: a coarser ancestor would show THROUGH the finer tiles (both
            // resolutions at once). Keep the finer descendants already added, never paint a coarser
            // ancestor; still-loading cells stay blank. Report covered so no ancestor draws over them.
            return true
        }
        if (progressiveRefinement) {
            // Slip the coarser tile UNDER the loaded finer children; report covered only if this quad
            // added content, else a not-yet-loaded intermediate level blanks instead of falling back.
            // Fetch ONLY on a miss — re-requesting an already-cached coarse tile every frame re-reads
            // the store and rebuilds its geometry (cyclic reload / CPU churn / flicker while panning).
            val content = contentFor(tile)
            if (content != null) renderCut.add(mark, content) else requestTile(tile)
            return renderCut.size > mark
        }
        truncate(mark) // kick the partial children, paint this whole quad coarser
        contentFor(tile)?.let { renderCut.add(it); return true }
        requestTile(tile)
        return false // a coarser ancestor must cover this cell
    }

    /** Fill [tile]'s area from already-loaded descendants (no fetches), bounded to [depth] levels —
     *  the zoom-OUT bridge. Adds nothing and returns false unless fully covered by loaded descendants. */
    private fun coverWithDescendants(rc: RenderContext, tile: Tile, depth: Int): Boolean {
        if (depth <= 0 || tile.level.isLastLevel) return false
        val mark = renderCut.size
        val children = tile.subdivideToCache(tileFactory, subtreeCache, 4)
        var allCovered = true
        for (i in children.indices) {
            val child = children[i]
            if (!isVisible(rc, child)) continue
            val content = contentFor(child)
            if (content != null) renderCut.add(content)
            else if (!coverWithDescendants(rc, child, depth - 1)) allCovered = false
        }
        if (allCovered) return true
        truncate(mark)
        return false
    }

    private fun truncate(toSize: Int) { while (renderCut.size > toSize) renderCut.removeAt(renderCut.size - 1) }

    private fun isVisible(rc: RenderContext, tile: Tile) =
        tile.intersectsSector(sector) && // data-availability gate ([VectorLayer.sector])
            tile.intersectsSector(rc.terrain.sector) && tile.intersectsFrustum(rc) &&
            rc.globe.projectionLimits?.let { tile.intersectsSector(it) } != false && !tile.isFullyFogged(rc)

    /** Loaded content for [tile], or null below [LevelSet.levelOffset] (never fetched) or not yet
     *  resident. Reading bumps LRU recency so a fallback ancestor/descendant stays put. */
    private fun contentFor(tile: Tile): C? =
        if (tile.level.levelNumber < levelSet.levelOffset) null else tiles[tile.tileKey]

    private fun requestTile(tile: Tile) {
        if (isClosed || tile.level.levelNumber < levelSet.levelOffset) return
        val key = tile.tileKey
        // No view-change abort: a started load always finishes and caches. Cancelling on a tile briefly
        // leaving the cut (tilt/zoom) made tiles disappear/reappear; concurrency is gated by the semaphore.
        if (backoff.isInBackoff(key) || inFlight.containsKey(key)) return
        // Bound the launch queue so a giant cut can't pile up unbounded coroutines + HTTP/SSL → OOM; over-cap tiles re-request a later frame as in-flight slots drain (the semaphore still gates network concurrency).
        if (inFlight.size >= maxPendingFetches) return
        val z = tile.level.levelNumber
        val x = tile.column
        val y = tile.row
        val sector = tile.sector
        val epoch = invalidationEpoch[key] ?: 0 // captured now; compared on arrival to detect mid-fetch invalidation
        inFlight[key] = scope.launch {
            val value = try {
                loadTileContent(z, x, y, sector)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                logMessage(earth.worldwind.util.Logger.WARN, "TiledVectorLayer", "loadTileContent",
                    "Failed to load tile $key: ${e::class.simpleName}: ${e.message}")
                null
            }
            results.trySend(TileResult(key, value, epoch))
            WorldWind.requestRedraw()
        }
    }

    private fun drainResults() {
        var admittedWeight = 0
        while (true) {
            val result = results.tryReceive().getOrNull() ?: return
            inFlight.remove(result.key)
            // Tile invalidated after this fetch started → drop the stale snapshot; it'll re-request.
            if (result.epoch != (invalidationEpoch[result.key] ?: 0)) continue
            val value = result.value
            if (value == null) { // failure → exponential backoff + wake-up redraw
                // Give up after MAX_BACKOFF_RETRIES (null delay): TileBackoff parks the key far in the
                // future so a persistently-failing tile (404 / no coverage) stops both retrying and
                // waking the render loop until source invalidation re-enables it.
                val delayMs = backoff.recordFailure(result.key, MAX_BACKOFF_RETRIES) ?: continue
                scope.launch { delay(delayMs); WorldWind.requestRedraw() }
                continue
            }
            backoff.clear(result.key)
            @Suppress("UNCHECKED_CAST")
            val content = value as C
            val weight = weightOf(content)
            liveContent[result.key] = content
            tiles.put(result.key, content, weight)
            // Per-frame admission budget: once we've admitted enough new content this frame, leave the
            // rest queued (the results channel is unbounded) and wake a follow-up frame. This spreads a
            // pan's burst of freshly-fetched tiles — and the GPU buffer uploads they trigger on first
            // render — across frames instead of one multi-hundred-ms uploadBuffers hitch.
            if (maxTileAdmitWeightPerFrame > 0) {
                admittedWeight += weight
                if (admittedWeight >= maxTileAdmitWeightPerFrame) { WorldWind.requestRedraw(); return }
            }
        }
    }

    private fun drainInvalidations() {
        while (true) {
            val key = invalidations.tryReceive().getOrNull() ?: return
            invalidationEpoch[key] = (invalidationEpoch[key] ?: 0) + 1 // stale-mark any in-flight fetch
            tiles.remove(key)
            inFlight.remove(key)?.cancel()
            backoff.clear(key)
        }
    }

    private fun checkGlobeState(rc: RenderContext) {
        if (rc.globeState != lastGlobeState) {
            topLevelTiles.clear()
            subtreeCache.clear()
            lastGlobeState = rc.globeState
        }
    }

    /** Cancel in-flight fetches, release GPU buffers, and free the caches. Idempotent. */
    open fun close() {
        if (isClosed) return
        isClosed = true
        scope.cancel()
        results.close()
        invalidations.close()
        try { closeSource() } catch (_: Exception) {}
        latestRc?.let { rc -> liveContent.forEach { (k, c) -> try { onContentEvicted(rc, c) } catch (e: Exception) {
            logMessage(WARN, "TiledVectorLayer", "releaseContent", "GPU release failed for tile $k", e)
        } } }
        liveContent.clear()
        tiles.clear()
        inFlight.clear()
        backoff.clear()
        topLevelTiles.clear()
        subtreeCache.clear()
    }

    private class TileResult(val key: String, val value: Any?, val epoch: Int)

    companion object {
        /** Levels of already-loaded finer tiles to search when bridging a zoom-OUT gap. */
        private const val MAX_FALLBACK_DEPTH = 3

        /** Stop retrying (and waking the render loop) after this many consecutive fetch failures. */
        private const val MAX_BACKOFF_RETRIES = 6

        /** Cache key matching [Tile.tileKey] = "level.row.column"; sources receive `(z=level,
         *  x=column, y=row)`, so revalidation callbacks map back the same way. */
        fun tileKeyOf(z: Int, x: Int, y: Int) = "$z.$y.$x"
    }
}
