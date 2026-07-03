package earth.worldwind.layer.buildings

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.layer.AbstractLayer
import earth.worldwind.layer.TileBackoff
import earth.worldwind.layer.VectorLayer
import earth.worldwind.layer.cache.RevalidatingSource
import earth.worldwind.layer.source.CachedFeatureRow
import earth.worldwind.layer.source.TiledFeatureSource
import earth.worldwind.layer.mercator.SlippyTiles
import earth.worldwind.layer.shadow.ShadowMode
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.shape.PathType
import earth.worldwind.shape.Polygon
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.util.LruMemoryCache
import earth.worldwind.util.Logger.ERROR
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/**
 * Renders OpenStreetMap-derived schematic 3D buildings. Footprints + height tags are fetched
 * on demand via a [TiledFeatureSource] (defaults to [OverpassBuildingsSource]) and cached per
 * slippy-map tile.
 *
 * Each cached tile is either:
 *   - a single [OsmBuildingsTile] (default, [useBatchedRendering] = true) — one VBO + one EBO
 *     for every building in the tile. Required at dense urban density: per-building polygons
 *     OOM the Adreno KGSL shared-memory pool by *buffer count*, not byte volume.
 *   - a `List<Polygon>`, one extruded [Polygon] per [OsmBuilding] — the legacy path. Needed by
 *     subclasses overriding [toPolygon] / [toPolygons] for per-building customisation the
 *     batched mesh doesn't carry; pass [useBatchedRendering] = false.
 *
 * [useOsmColors] works in both modes — the batched path groups triangles by colour within the
 * tile's element array and issues one sub-draw per colour.
 *
 * ```
 * val buildings = OsmBuildingsLayer()
 * worldWindow.engine.layers.addLayer(buildings)
 * // ...later:
 * buildings.close()
 * ```
 *
 * Tile selection uses a centre-radius window: at each frame the (2×[tileRadius]+1)² tiles around
 * the camera's lookAt point (falling back to the camera lat/lon) are scheduled for fetch. At zoom
 * 15 a radius of 4 covers ~10 km square. Fetching is gated by [maxActiveAltitude] (30 km by
 * default), above which the layer is a no-op.
 *
 * Style every building uniformly via [attributes], or enable [useOsmColors] for per-tag
 * wall/roof colouring. Always call [close] to cancel in-flight fetches.
 */
open class OsmBuildingsLayer(
    var source: TiledFeatureSource = OverpassBuildingsSource(),
    val tileZoom: Int = 15,
    val tileRadius: Int = 4,
    val maxLoadedTiles: Int = 256,
    maxConcurrentFetches: Int = 2,
    /**
     * When true, walls are coloured via [OsmColors.resolve] on each [OsmBuilding]'s tags (falling
     * back to [attributes]'s interior colour); top caps use `roof:colour` (falling back to the
     * wall colour). On by default; set false for uniform-gray output. Works in both rendering modes.
     */
    val useOsmColors: Boolean = true,
    /**
     * When true (default), every building in a tile is packed into one [OsmBuildingsTile] mesh
     * (~500× fewer GL buffers per tile). Set false to keep the legacy "one Polygon per building"
     * path — required when overriding [toPolygon] / [toPolygons] for per-building customisation
     * the batched path doesn't carry (e.g. per-building geometry variation).
     */
    val useBatchedRendering: Boolean = true,
    displayName: String? = "OSM Buildings",
) : AbstractLayer(displayName), VectorLayer {

    /** Shape attributes applied to every building polygon. Mutating between frames is safe. */
    var attributes: ShapeAttributes = defaultBuildingAttributes()

    /**
     * Layer-wide shadow participation. Default [ShadowMode.ENABLED] makes every building both
     * cast shadows onto the terrain and receive cascade shadows. Applies in both batched and
     * legacy rendering paths; in the legacy path it overrides any per-Polygon
     * [ShapeAttributes.shadowMode] so layer-level intent wins.
     */
    var shadowMode: ShadowMode = ShadowMode.ENABLED

    private val semaphore = Semaphore(maxConcurrentFetches.coerceAtLeast(1))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val results = Channel<TileResult>(capacity = Channel.UNLIMITED)
    // Tiles a stale-while-revalidate refresh has replaced in the cache (delivered off the render
    // thread via [RevalidatingSource.onTileRevalidated]). [drainRevalidated] drops each from the
    // in-memory [tiles] LRU on the render thread so the fresh rows reload from cache.
    private val revalidated = Channel<TileKey>(capacity = Channel.UNLIMITED)
    // The source the revalidation callback is currently wired to. [source] can be swapped by a
    // cache attach / rebind, so [wireRevalidation] re-wires whenever it changes.
    private var wiredSource: TiledFeatureSource? = null
    // [latestRc] is the RC seen on the most recent doRender, used by the LRU eviction callback to
    // route to [OsmBuildingsTile.releaseRenderResources] without needing rc passed through the
    // cache plumbing. doRender + LRU put + eviction all run on the render thread, so this is safe.
    private var latestRc: RenderContext? = null
    // Parallel set tracking every value currently in [tiles]. [LruMemoryCache.clear] doesn't fire
    // [LruMemoryCache.entryRemoved] (see its impl), so to release batched tile GPU buffers on
    // [close] we need our own enumerable handle.
    private val cachedValues = HashSet<Any>()
    private val tiles = object : LruMemoryCache<TileKey, Any>(maxLoadedTiles.toLong()) {
        override fun entryRemoved(key: TileKey, oldValue: Any, newValue: Any?, evicted: Boolean) {
            cachedValues.remove(oldValue)
            val rc = latestRc
            if (rc != null && oldValue is OsmBuildingsTile) oldValue.releaseRenderResources(rc)
            // Legacy per-Polygon tiles rely on the RenderResourceCache LRU pressure (now
            // page-aligned) to evict their VBOs/EBOs; eager release would need a public
            // releaseRenderResources on Polygon, deferred until/unless legacy mode bottlenecks.
        }
    }
    private val pending = HashSet<TileKey>()
    // Exponential backoff per failed tile key — bars [processTile] from re-launching a fetch until
    // the deadline passes (cleared on the first success). Without it, a flapping Overpass mirror
    // (429 / 503 under load) would get hammered every render frame.
    private val backoff = TileBackoff<TileKey>()
    private var isClosed = false

    init {
        maxActiveAltitude = 30_000.0
    }

    override fun doRender(rc: RenderContext) {
        latestRc = rc
        wireRevalidation()
        drainRevalidated()
        drainResults()

        // Center on lookAt when available (true look-at point on the globe), else fall back to
        // the camera's lat/lon. terrain.sector was too brittle here — at building zoom it
        // routinely exceeds any reasonable per-frame tile cap.
        val center = rc.lookAtPosition ?: rc.camera.position
        val n = 1 shl tileZoom
        val centerXY = lonLatToTile(center.longitude.inDegrees, center.latitude.inDegrees, tileZoom)
        val cx = centerXY.first
        val cy = centerXY.second

        // Center-first spiral: the centre tile, then each Chebyshev ring outward. Tiles near
        // the camera focus enter the fetch queue ahead of corner tiles, which dramatically
        // shortens the time before *something* visible appears around the user's view target
        // when the Overpass mirror is slow.
        for ((dx, dy) in spiralOffsets) {
            val y = cy + dy
            if (y !in 0 until n) continue
            // Wrap longitudinally so a tile-radius window straddling the antimeridian still works.
            val x = ((cx + dx) % n + n) % n
            processTile(rc, TileKey(tileZoom, x, y))
        }
    }

    /**
     * Cached (dx, dy) offsets ordered by Chebyshev distance from (0, 0). Precomputed once at
     * layer construction so [doRender] doesn't allocate per frame (and tiebreaks are stable
     * regardless of JVM/JS sort behaviour).
     */
    private val spiralOffsets: List<Pair<Int, Int>> = buildList {
        for (dy in -tileRadius..tileRadius) {
            for (dx in -tileRadius..tileRadius) add(dx to dy)
        }
    }.sortedBy { (dx, dy) -> maxOf(abs(dx), abs(dy)) }

    private fun processTile(rc: RenderContext, key: TileKey) {
        val tile = tiles[key]
        if (tile != null) {
            try {
                when (tile) {
                    is OsmBuildingsTile -> tile.render(rc)
                    is List<*> -> for (p in tile) if (p is Polygon) p.render(rc)
                }
            } catch (e: Exception) {
                logMessage(ERROR, "OsmBuildingsLayer", "doRender",
                    "Exception while rendering building tile $key", e)
            }
        } else if (!isClosed && !backoff.isInBackoff(key) && pending.add(key)) {
            scope.launch { fetch(key) }
        }
    }

    /**
     * Cancels in-flight fetches and frees the cache. Idempotent. The layer cannot be reused
     * after [close].
     */
    open fun close() {
        if (isClosed) return
        isClosed = true
        scope.cancel()
        results.close()
        revalidated.close()
        try { source.close() } catch (_: Exception) {}
        // Best-effort GPU release for in-flight batched tiles. Legacy tiles fall through to
        // RenderResourceCache LRU pressure as usual. [close] is normally called from the render
        // thread (or right before the GL context tears down), so [latestRc] is the rc from the
        // most recent frame and routing through it is safe — removing buffer keys from the cache
        // just enqueues for [releaseEvictedResources], which the next render frame consumes.
        val rc = latestRc
        if (rc != null) {
            for (entry in cachedValues) {
                if (entry is OsmBuildingsTile) {
                    try { entry.releaseRenderResources(rc) } catch (_: Exception) {}
                }
            }
        }
        cachedValues.clear()
        tiles.clear()
        pending.clear()
        backoff.clear()
    }

    /**
     * Produce the list of [Polygon]s that draw one building. Used only on the legacy per-Polygon
     * rendering path ([useBatchedRendering] = false). Default is [toPolygon] plus an optional roof
     * cap when `roof:colour` is tagged. Subclasses that want a different polygon layout (extra
     * spires, mast antennas, …) override this.
     */
    protected open fun toPolygons(building: OsmBuilding): List<Polygon> {
        val wall = toPolygon(building)
        val cap = roofCap(building) ?: return listOf(wall)
        return listOf(wall, cap)
    }

    /**
     * Wrap one [OsmBuilding] in an extruded wall [Polygon]. Used only on the legacy per-Polygon
     * rendering path ([useBatchedRendering] = false). Override to customise per-building shape
     * config (lighting, attributes, displayName, …). To add or replace polygons, override
     * [toPolygons] instead.
     */
    protected open fun toPolygon(building: OsmBuilding): Polygon {
        val effectiveAttributes = if (useOsmColors) {
            OsmColors.resolve(building.tags)?.let { color ->
                // Per-polygon copy so the layer-wide [attributes] is not mutated. Allocation
                // is acceptable - one ShapeAttributes per coloured building, only on the
                // background fetch path.
                ShapeAttributes(attributes).apply {
                    interiorColor = color
                    shadowMode = this@OsmBuildingsLayer.shadowMode
                }
            } ?: ShapeAttributes(attributes).apply { shadowMode = this@OsmBuildingsLayer.shadowMode }
        } else ShapeAttributes(attributes).apply { shadowMode = this@OsmBuildingsLayer.shadowMode }
        return Polygon(building.outerRing, effectiveAttributes).apply {
            isExtrude = true
            altitudeMode = AltitudeMode.RELATIVE_TO_GROUND
            baseAltitude = building.minHeight
            // Floating parts (tower on a podium) need a flat bottom; ground-floor walls
            // (minHeight == 0) keep the default draping skirt so they sit flush on slopes.
            isPlanar = building.minHeight > 0.0
            // LINEAR skips great-circle's 63-slot per-edge pre-allocation — at building
            // scale no intermediates are emitted anyway and the spare slots OOM on Android.
            pathType = PathType.LINEAR
            building.name?.let { displayName = it }
            for (hole in building.innerRings) addBoundary(hole)
        }
    }

    /**
     * Optional top-cap polygon painted with `roof:colour`. Null when [useOsmColors] is off
     * or no parseable `roof:colour` is present. [Polygon.isPlanar] keeps the cap flush with
     * the wall top on sloped ground.
     */
    protected open fun roofCap(building: OsmBuilding): Polygon? {
        if (!useOsmColors) return null
        val roofColor = OsmColors.parseColor(building.tags["roof:colour"]) ?: return null
        val capAltitude = building.height + ROOF_CAP_LIFT_METERS
        val capRing = building.outerRing.map { Position(it.latitude, it.longitude, capAltitude) }
        val capAttrs = ShapeAttributes(attributes).apply {
            interiorColor = roofColor
            shadowMode = this@OsmBuildingsLayer.shadowMode
        }
        return Polygon(capRing, capAttrs).apply {
            altitudeMode = AltitudeMode.RELATIVE_TO_GROUND
            isPlanar = true
            pathType = PathType.LINEAR
            for (hole in building.innerRings) {
                addBoundary(hole.map { Position(it.latitude, it.longitude, capAltitude) })
            }
        }
    }

    /**
     * Build the batched mesh shape for a tile of buildings. Override to customise the tile-level
     * shape config (e.g. swap [attributes] per tile, attach metadata to [OsmBuildingsTile]).
     * Used only on the batched rendering path ([useBatchedRendering] = true).
     */
    protected open fun toTile(key: TileKey, buildings: List<OsmBuilding>): OsmBuildingsTile =
        OsmBuildingsTile(buildings, attributes, useOsmColors = useOsmColors, shadowMode = shadowMode)

    /** Resolve buildings for one tile from [source]. Cache-backed sources serve hits via
     *  [TiledFeatureSource.tryReadCachedTile] WITHOUT taking a [semaphore] permit — only the
     *  network round-trip on a cache miss is throttled. Otherwise a slow Overpass fetch for one
     *  uncached tile would hold the (small) fetch budget and stall cached neighbours behind it.
     *  Subclasses override to attach custom per-tile processing. */
    protected open suspend fun loadBuildings(key: TileKey): List<OsmBuilding> {
        val src = source
        val rows = mutableListOf<CachedFeatureRow>()
        val cached = src.tryReadCachedTile(key.z, key.x, key.y, key.sector)
        if (cached != null) {
            cached.collect { rows += it }
        } else {
            // Cache miss (or a non-caching source): the network fetch is gated by [semaphore].
            semaphore.withPermit {
                val flow = src.fetchTile(key.z, key.x, key.y, key.sector) ?: return emptyList()
                flow.collect { rows += it }
            }
        }
        return rows.mapNotNull(OsmBuildingCodec::decode)
    }

    private fun drainResults() {
        while (true) {
            val result = results.tryReceive().getOrNull() ?: return
            pending.remove(result.key)
            val value = result.value
            if (value == null) {
                // Fetch failed. Bump per-key backoff and schedule a redraw for when it expires
                // so an idle scene still picks the retry up without further user input.
                backoff.recordFailure(result.key)?.let { scheduleBackoffRedraw(it) }
                continue
            }
            // Success: clear any prior backoff and cache.
            backoff.clear(result.key)
            // Weight = 1 per tile so [maxLoadedTiles] really is a tile count. Per-Polygon mode
            // earlier tried `polygons.size` and trashed the cache — see git history.
            cachedValues.add(value)
            tiles.put(result.key, value, 1)
        }
    }

    /**
     * Wire the stale-while-revalidate callback to the current [source] (idempotent per source
     * instance). Runs on the render thread each frame so a cache attach / rebind that swaps
     * [source] gets re-wired. The callback fires off the render thread, so it only enqueues the
     * key; [drainRevalidated] does the cache mutation on the render thread.
     */
    private fun wireRevalidation() {
        val src = source
        if (src === wiredSource) return
        wiredSource = src
        (src as? RevalidatingSource)?.onTileRevalidated = { z, x, y ->
            revalidated.trySend(TileKey(z, x, y))
            WorldWind.requestRedraw()
        }
    }

    /**
     * Drop every just-revalidated tile from the in-memory [tiles] LRU so the next [processTile]
     * reloads the fresh rows the background refresh wrote to the cache. Render-thread only —
     * [LruMemoryCache.remove] routes batched tiles through `entryRemoved` to free their GPU
     * buffers, matching normal eviction.
     */
    private fun drainRevalidated() {
        while (true) {
            val key = revalidated.tryReceive().getOrNull() ?: return
            tiles.remove(key)
            pending.remove(key)
            backoff.clear(key)
        }
    }

    /**
     * Schedule a wake-up redraw after [delayMs] so a failed tile retries even when the user has
     * stopped interacting. The coroutine lives on [scope] so [close] cancels it cleanly.
     */
    private fun scheduleBackoffRedraw(delayMs: Long) {
        scope.launch {
            delay(delayMs)
            WorldWind.requestRedraw()
        }
    }

    private suspend fun fetch(key: TileKey) {
        val value = try {
            // Network concurrency is throttled inside [loadBuildings] (network path only) so
            // cache hits aren't gated behind it. Mesh assembly runs unthrottled — it's CPU work
            // already bounded by the Default dispatcher, and gating it here would re-introduce
            // the stall for cached tiles.
            val buildings = loadBuildings(key)
            if (useBatchedRendering) toTile(key, buildings) as Any
            else OsmBuildingsTile.filterRedundantOutlines(buildings).flatMap(::toPolygons) as Any
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Visible failure: silent retries on the next render are still possible, but the
            // user/developer can see *why* tiles aren't appearing. Public Overpass mirrors
            // commonly return 429 / 503 under load; a self-hosted endpoint is the fix for
            // production traffic.
            logMessage(WARN, "OsmBuildingsLayer", "fetch",
                "Failed to fetch tile $key: ${e::class.simpleName}: ${e.message}")
            null
        }
        results.trySend(TileResult(key, value))
        // Wake the render thread so the just-finished tile renders without waiting for a pan.
        // Also fires on failures so doRender drops the key from `pending` and can retry.
        WorldWind.requestRedraw()
    }

    /** Slippy-map tile coordinate triple used as the [tiles] LRU key. */
    data class TileKey(val z: Int, val x: Int, val y: Int) {
        val sector: Sector get() = tileToSector(z, x, y)
    }

    private data class TileResult(val key: TileKey, val value: Any?)

    /**
     * The render-config subset persisted into `WebServiceInfo.metadata` on cache attach and
     * replayed on reopen, so a cached layer comes back with its original flags instead of bare
     * constructor defaults (the white-buildings-on-reopen bug when [useOsmColors] was set).
     * Only the `val` constructor params are carried; [attributes] / [shadowMode] are not.
     * Defaults mirror the constructor so an absent field decodes to the same value.
     */
    @Serializable
    data class CacheConfig(
        val useOsmColors: Boolean = true,
        val useBatchedRendering: Boolean = true,
        val tileZoom: Int = 15,
        val tileRadius: Int = 4,
        val maxLoadedTiles: Int = 256,
    )

    companion object {
        // Tolerant of unknown keys so a config written by a newer build still decodes here.
        private val cacheConfigJson = Json { ignoreUnknownKeys = true }

        /** Serialize [layer]'s render config to the JSON string stored in `WebServiceInfo.metadata`. */
        fun encodeCacheConfig(layer: OsmBuildingsLayer): String = cacheConfigJson.encodeToString(
            CacheConfig.serializer(),
            CacheConfig(
                useOsmColors = layer.useOsmColors,
                useBatchedRendering = layer.useBatchedRendering,
                tileZoom = layer.tileZoom,
                tileRadius = layer.tileRadius,
                maxLoadedTiles = layer.maxLoadedTiles,
            ),
        )

        /** Parse persisted render config; null / blank / unparseable metadata falls back to defaults. */
        fun decodeCacheConfig(metadata: String?): CacheConfig = metadata?.takeIf { it.isNotBlank() }
            ?.let { runCatching { cacheConfigJson.decodeFromString(CacheConfig.serializer(), it) }.getOrNull() }
            ?: CacheConfig()

        // Roof-cap lift above the wall top. 1 cm is invisible at building scale yet wins
        // the depth test against the wall top cleanly without polygon-offset machinery.
        const val ROOF_CAP_LIFT_METERS: Double = 0.01

        fun defaultBuildingAttributes(): ShapeAttributes = ShapeAttributes().apply {
            interiorColor = Color(red = 0.80f, green = 0.80f, blue = 0.78f, alpha = 1f)
            outlineColor = Color(red = 0.45f, green = 0.45f, blue = 0.43f, alpha = 1f)
            outlineWidth = 1f
            // Flat per-face Lambertian shading distinguishes the wall faces of a building box
            // (otherwise all four sides paint the same gray and read as one flat shape).
            isLightingEnabled = true
        }

        fun lonLatToTile(lonDegrees: Double, latDegrees: Double, zoom: Int): Pair<Int, Int> =
            SlippyTiles.lonLatToTile(lonDegrees, latDegrees, zoom)

        fun tileToSector(zoom: Int, x: Int, y: Int): Sector = SlippyTiles.tileToSector(zoom, x, y)
    }
}
