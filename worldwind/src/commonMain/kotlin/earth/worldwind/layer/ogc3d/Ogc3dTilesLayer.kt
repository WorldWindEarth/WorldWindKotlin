package earth.worldwind.layer.ogc3d

import earth.worldwind.WorldWind
import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.Drawable
import earth.worldwind.formats.gltf.GlbReader
import earth.worldwind.formats.gltf.GltfModel
import earth.worldwind.formats.gltf.GltfReader
import earth.worldwind.formats.ogc3d.B3dmLoader
import earth.worldwind.formats.ogc3d.CmptLoader
import earth.worldwind.formats.ogc3d.I3dmLoader
import earth.worldwind.formats.ogc3d.PntsLoader
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.globe.Globe
import earth.worldwind.geom.BoundingSphere
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import kotlin.math.abs
import earth.worldwind.layer.AbstractLayer
import earth.worldwind.layer.cache.BlobStore
import earth.worldwind.layer.cache.NoOpBlobStore
import earth.worldwind.layer.shadow.ShadowMode
import earth.worldwind.layer.ogc3d.content.GaussianContent
import earth.worldwind.layer.ogc3d.content.GaussianLoader
import earth.worldwind.layer.ogc3d.content.MeshContent
import earth.worldwind.layer.ogc3d.content.MeshContentPrep
import earth.worldwind.layer.ogc3d.content.PointCloudContent
import earth.worldwind.layer.ogc3d.content.spz.GltfGaussianLoader
import earth.worldwind.layer.ogc3d.content.isResourcesLoaded
import earth.worldwind.layer.ogc3d.content.prepareGaussianContent
import earth.worldwind.layer.ogc3d.content.prepareMeshPrep
import earth.worldwind.layer.ogc3d.content.preparePointCloudContent
import earth.worldwind.layer.ogc3d.content.releaseSortState
import earth.worldwind.layer.ogc3d.content.resortByCamera
import earth.worldwind.layer.ogc3d.content.syncGaussianContentGpu
import earth.worldwind.layer.ogc3d.content.syncPointCloudContentGpu
import earth.worldwind.layer.ogc3d.content.touchCache
import earth.worldwind.util.ByteArrayPool
import earth.worldwind.layer.ogc3d.content.uploadMeshContent
import earth.worldwind.layer.ogc3d.draw.DrawableGaussianPass
import earth.worldwind.layer.ogc3d.draw.DrawableTileGaussian
import earth.worldwind.layer.ogc3d.draw.DrawableTileMesh
import earth.worldwind.layer.ogc3d.draw.DrawableTilePoints
import earth.worldwind.layer.ogc3d.program.GaussianCompositeProgram
import earth.worldwind.layer.ogc3d.program.Ogc3dTilesGaussianProgram
import earth.worldwind.layer.ogc3d.program.Ogc3dTilesPointsProgram
import earth.worldwind.layer.ogc3d.program.Ogc3dTilesProgram
import earth.worldwind.render.program.SceneDepthProgram
import earth.worldwind.PickedObject
import earth.worldwind.layer.ogc3d.stream.ContentDispatcher
import earth.worldwind.layer.ogc3d.stream.HttpStatusException
import earth.worldwind.layer.ogc3d.stream.HttpTileByteSource
import earth.worldwind.layer.ogc3d.stream.TileByteSource
import earth.worldwind.layer.ogc3d.stream.TileContentRequest
import earth.worldwind.layer.ogc3d.stream.TileFetchQueue
import earth.worldwind.layer.ogc3d.style.BoundStyle
import earth.worldwind.layer.ogc3d.style.TilesetStyle
import earth.worldwind.layer.ogc3d.tileset.GltfUpAxis
import earth.worldwind.layer.ogc3d.tileset.Tile3d
import earth.worldwind.layer.ogc3d.tileset.Tileset
import earth.worldwind.layer.ogc3d.tileset.TilesetParser
import earth.worldwind.layer.ogc3d.traverse.ScreenSpaceError
import earth.worldwind.layer.ogc3d.traverse.Traverser
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.logMessage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * OGC / Cesium 3D Tiles layer. Streams b3dm / i3dm / cmpt / pnts / glTF / Gaussian-splat
 * payloads from a self-hosted endpoint, Cesium Ion, Google Photorealistic, or any
 * custom-headers / Bearer-authenticated endpoint via [TilesetSource.authProvider].
 */
open class Ogc3dTilesLayer(
    val source: TilesetSource,
    displayName: String? = source.displayName,
    /** URI-keyed blob cache. Defaults to network-only; pass a real [BlobStore] (typically
     *  `contentManager.openBlobStore(key)`) to persist payloads. */
    blobStore: BlobStore = NoOpBlobStore,
    /** Hard cap on concurrent fetch + parse jobs. Conservative default sized for the
     *  install pipeline's natural throughput (~60-80 installs/sec on a fast GL stack);
     *  higher caps just queue parsed bitmaps that get cancelled before install. Consumers
     *  on known high-end devices can opt up to 16-32. Splat parses self-throttle via
     *  [gaussianParseConcurrency] regardless. */
    fetchConcurrency: Int = 8,
    /** Byte transport for all fetches. Default is HTTP; an
     *  [earth.worldwind.layer.ogc3d.stream.ArchiveTileByteSource] serves a local SLPK in place. */
    byteSource: TileByteSource = HttpTileByteSource(),
) : AbstractLayer(displayName) {
    /** Cast + receive shadows by default. [ShadowMode.RECEIVE_ONLY] for unlit-from-above
     *  datasets; [ShadowMode.DISABLED] to skip the integration. */
    var shadowMode: ShadowMode = ShadowMode.ENABLED

    /** Vertical correction in meters, applied along the ellipsoid normal at [altitudeAnchor]
     *  (or the root tile's bounding-volume centre by default). Interpretation depends on
     *  [altitudeMode]. Common case: the tileset's vertical datum doesn't match WGS84
     *  ellipsoid (geoid offset, local survey datum) — a constant offset realigns it. */
    var altitudeOffset: Double = 0.0
        set(value) { if (field != value) { field = value; altitudeOffsetDirty = true } }

    /** How [altitudeOffset] is anchored.
     *  - [AltitudeMode.ABSOLUTE] (default): offset adds to the tileset's baked altitude.
     *  - [AltitudeMode.RELATIVE_TO_GROUND]: offset is meters above terrain at [altitudeAnchor]
     *    (lookup runs against [earth.worldwind.globe.Globe.getElevation]).
     *  - [AltitudeMode.CLAMP_TO_GROUND]: tileset is shifted so its anchor altitude matches
     *    terrain at the anchor, then [altitudeOffset] lifts it from there.
     *  Non-ABSOLUTE modes refresh each frame so the offset auto-adjusts as elevation tiles
     *  load. For globe-spanning tilesets (Google Photorealistic) the single-anchor up
     *  direction stops making sense — use ABSOLUTE + offset = 0 there. */
    var altitudeMode: AltitudeMode = AltitudeMode.ABSOLUTE
        set(value) { if (field != value) { field = value; altitudeOffsetDirty = true } }

    /** Geographic anchor for [altitudeMode]. `null` (default) → root tile's bounding-volume
     *  centre, resolved on first render after parse. Set explicitly when the root centre
     *  isn't representative (e.g. a long-strip drone capture whose centre is over open
     *  water; a wide-area tileset where the anchor should be near the camera). */
    var altitudeAnchor: Position? = null
        set(value) { if (field != value) { field = value; altitudeOffsetDirty = true } }

    /** Pixel error floor for tile refinement. Setter also resets the effective SSE so
     *  changes take effect immediately. */
    var maxScreenSpaceError: Double = 16.0
        set(value) {
            field = value
            memoryAdjustedSSE = value
        }

    /** Hard cap on draw distance in meters. 0 disables. */
    var maxDrawDistance: Double
        get() = traverser.maxDrawDistance
        set(value) { traverser.maxDrawDistance = value }

    /** When true, REPLACE parents un-render as soon as ANY child loads (transient gaps);
     *  when false (default), parent waits for every visible sibling. */
    var skipLevelOfDetail: Boolean
        get() = traverser.skipLevelOfDetail
        set(value) { traverser.skipLevelOfDetail = value }

    /** Soft GPU-byte budget for the working set. Effective SSE drifts up past this and
     *  back down under 70 %. */
    var maxMemoryFootprintBytes: Long = DEFAULT_MAX_MEMORY_FOOTPRINT_BYTES

    /** Base pnts point size in pixels (size-attenuated by the shader). Matches CesiumJS'
     *  default; bump for sparse datasets where 1-px points are hard to see. */
    var pointSize: Float = 1f

    /** Global multiplier on Gaussian-splat projected size. */
    var splatSizeMultiplier: Float = 1f

    /** Per-fragment Gaussian-alpha discard threshold; raise to trim the soft splat fringe
     *  and cut blend-ROP fillrate. */
    var gaussianMinAlpha: Float = 0.01f

    /** Gaussian-splat offscreen render scale in (0, 1]. Values < 1 draw all splat tiles into
     *  a reduced-resolution target and composite-upsample — splat rendering is blended-fill
     *  bound, so 0.5 costs ~1/4 the GPU fragment work at slightly softer splat edges. 1.0
     *  restores direct full-resolution drawing. See [DrawableGaussianPass]. */
    var gaussianRenderScale: Float = 0.5f

    /** Per-frame collector for the reduced-resolution splat pass; render thread only. */
    private var gaussianPass: DrawableGaussianPass? = null

    /** Gaussian-splat decoder. Defaults to [GltfGaussianLoader] (glb-wrapped SPZ via
     *  `KHR_gaussian_splatting`, plus raw `.spz`). Set to `null` to disable splat decoding
     *  or swap for a different [GaussianLoader]. Payloads not matching
     *  b3dm/i3dm/cmpt/pnts/glTF/tileset.json are offered to [GaussianLoader.supports]. */
    var gaussianLoader: GaussianLoader? = GltfGaussianLoader()

    /** Concurrent splat-parse cap. Each in-flight SPZ parse holds tens of MB transient
     *  (compressed buffer + decoded position/scale/rot/SH FloatArrays before GPU upload), so
     *  the general `fetchConcurrency` would peak at ~2 GB on a splat tileset. Mesh fetches
     *  on the same layer keep `fetchConcurrency`'s budget — only the Gaussian branch gates
     *  here. Setter rebinds the semaphore. */
    var gaussianParseConcurrency: Int = 4
        set(value) {
            require(value >= 1) { "gaussianParseConcurrency must be >= 1, got $value" }
            field = value
            gaussianParseSemaphore = Semaphore(value)
        }

    private var gaussianParseSemaphore = Semaphore(gaussianParseConcurrency)

    /** 3D Tiles 1.0 declarative style; needs an [Ogc3dDecoderRegistry.tilesetStyleEvaluator]
     *  registered to do anything. Setter rebinds eagerly. */
    var style: TilesetStyle? = null
        set(value) {
            field = value
            boundStyle = value?.let { Ogc3dDecoderRegistry.tilesetStyleEvaluator?.bind(it) }
        }

    private var boundStyle: BoundStyle? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fetchQueue = TileFetchQueue(
        source = source,
        parentScope = scope,
        maxConcurrent = fetchConcurrency,
        blobStore = blobStore,
        byteSource = byteSource,
        // Drop the parsed tree + reopen the root-fetch latch so the next render rebuilds
        // under the fresh session. Reset the altitude-bake cache too: the next tileset
        // arrives with un-baked tileToWorld matrices, so `rebakeAltitudeOffset` would
        // otherwise short-circuit on a stale "already applied" delta and the new tiles
        // would render at zero offset (Farsight 3D-mesh sinking through terrain).
        onAuthExpired = {
            tileset = null
            resetRootFetch()
            appliedAltitudeTranslationWw.set(0.0, 0.0, 0.0)
            altitudeOffsetDirty = true
            cachedSector = null
            WorldWind.requestRedraw()
        },
    )
    private val traverser = Traverser()

    /** Parse → render-thread handoff. Bounded so `send` suspends and propagates back-
     *  pressure to fetch; an unbounded queue would stockpile parsed mesh preps (each
     *  retaining a decoded Bitmap + vertex FloatArray) when GL upload can't keep up. */
    private val pendingMeshUploads = Channel<MeshUploadEntry>(capacity = PENDING_UPLOAD_CHANNEL_CAPACITY)
    /** Best-effort depth gauge for the bounded upload queue; drifts under cancellation. */
    @Volatile private var pendingUploadDepth: Int = 0

    /** Tile → in-flight fetch [Job]. Walked each frame so fetches whose tile is no longer
     *  traversed get cancelled, freeing the permit for the new selection. */
    private val inFlightFetches = HashMap<Tile3d, Job>(64)

    /** Reused membership set for [reapInFlightFetches] — cleared and refilled each frame. */
    private val reapActiveSet = HashSet<Tile3d>(256)

    /** Snapshot of the last normal frame's selected tiles. Pick frames render this list
     *  instead of re-traversing, since the pick viewport is 1×1 and would collapse SSE
     *  to ~0 for every tile, dropping wrappers out of `requestedTiles` and tripping the
     *  reaper to cancel every in-flight fetch. With pick-on-mouse-move on web, that lockstep
     *  cancels several fetches per pointer event. */
    private val lastSelectedTiles = ArrayList<Tile3d>(64)

    /** Per-frame counter driving the gaussian eviction grace period. */
    private var frameCounter: Long = 0

    /** Loaded gaussian tile → last frame it was requested. Tiles whose age exceeds
     *  [GAUSSIAN_EVICTION_GRACE_FRAMES] get evicted by [sweepEvictedGaussianTiles]. */
    private val loadedGaussianTiles = HashMap<Tile3d, Long>(64)

    /** Last `rc.contextVersion` seen — advance on Android pause/resume triggers [invalidateOnContextLoss]. */
    private var lastSeenContextVersion: Long = 0L

    private class MeshUploadEntry(val tile: Tile3d, val shell: MeshContent, val prep: MeshContentPrep)

    @Volatile private var tileset: Tileset? = null
    @Volatile private var rootFetching = false
    @Volatile private var rootFailed = false

    /** Geographic lat/lon envelope of the root tile's bounding volume, populated on first
     *  render after `tileset` parses. Null until then. Used for fly-to / zoom-to-extent — not
     *  for culling. Computation is one-shot: altitude-offset shifts don't change the lat/lon
     *  footprint meaningfully at any realistic offset. */
    @Volatile private var cachedSector: Sector? = null

    /** Geographic lat/lon envelope of the tileset's root bounding volume. `null` until the
     *  root `tileset.json` has been fetched, parsed, and rendered at least once. Safe to read
     *  from any thread. */
    val sector: Sector? get() = cachedSector

    /** Working effective SSE, driven by the SSE-pressure feedback loop. @Volatile because
     *  the setter for [maxScreenSpaceError] can fire from any thread. */
    @Volatile private var memoryAdjustedSSE: Double = 16.0

    /** Throttle for the SSE relax half — prevents per-frame oscillation at borderline
     *  working sets. */
    @Volatile private var lastSseDecreaseAt: Instant = Instant.DISTANT_PAST

    /** Vertical translation (WW frame) currently baked into every tile's tileToWorld.
     *  rebakeAltitudeOffset(rc) walks the tree, applies the delta from this to the latest
     *  target, and updates this to match. */
    private val appliedAltitudeTranslationWw: Vec3 = Vec3()

    /** Set when [altitudeOffset] / [altitudeMode] / [altitudeAnchor] changes — also re-set
     *  every frame for non-ABSOLUTE modes so terrain-relative offsets pick up elevation
     *  updates. */
    @Volatile private var altitudeOffsetDirty: Boolean = true

    /** Scratch buffers for the offset computation — kept on the layer so each render frame
     *  doesn't allocate. */
    private val tmpUpEcef = Vec3()
    private val tmpAnchorEcef = Vec3()
    private val tmpAnchorPosition = Position()

    /** Globe captured each render so [parseRootDocument] (off-thread) can reach geographic↔Cartesian
     *  without a [RenderContext]. Null before the first frame. */
    @Volatile protected var lastGlobe: Globe? = null

    override fun doRender(rc: RenderContext) {
        lastGlobe = rc.globe
        val current = tileset
        if (current == null) {
            ensureRootRequested()
            return
        }
        if (cachedSector == null) {
            cachedSector = current.root.boundingVolume.worldBoundingSector(rc.globe, current.root.tileToWorld, Sector())
        }
        // Publish coverage + flag so terrain stencil-tests against GROUND_COVERED_BIT only
        // for tiles that intersect this layer's footprint.
        cachedSector?.let { rc.groundCoverageRegions.add(it) }
        rc.hasGroundCoverageMask = true
        // Pick frames run with a 1×1 pickViewport so SSE collapses to ~0 for every tile —
        // re-traversing here would drop every sub-tileset wrapper below the SSE budget,
        // reapInFlightFetches would cancel every in-flight wrapper fetch, and the next
        // normal frame would re-fire them. Reuse the last normal frame's selection for
        // pick — pick frames are sandwiched between normal frames, so it's still the
        // current on-screen tile set.
        if (rc.isPickMode) {
            for (tile in lastSelectedTiles) enqueueDrawable(rc, tile)
            return
        }
        invalidateOnContextLoss(rc)
        frameCounter++
        rebakeAltitudeOffset(rc, current)
        traverser.maxScreenSpaceError = memoryAdjustedSSE
        traverser.includeShadowCasters =
            shadowMode.castsShadows && (rc.shadowState?.offscreenCasterCascades ?: 0) > 0
        val result = traverser.traverse(rc, current)

        // Touch loaded tiles before draining uploads so that the put()/makeSpace eviction
        // triggered by new uploads sees them as recently used and skips them.
        var occupiedBytes = 0L
        var hasUnloadedRequests = false
        for (tile in result.requestedTiles) {
            val content = tile.content
            when {
                content is MeshContent -> if (content.isResourcesLoaded(rc)) {
                    content.touchCache(rc)
                    occupiedBytes += content.gpuByteCount
                } else {
                    // Evicted out from under us; re-request next frame.
                    tile.content = null
                    tile.loadState = Tile3d.LoadState.UNLOADED
                    hasUnloadedRequests = true
                }
                content is PointCloudContent -> if (content.isResourcesLoaded(rc)) {
                    content.touchCache(rc)
                    occupiedBytes += content.gpuByteCount
                } else {
                    // VBO evicted; CPU-side data is gone — re-fetch + re-decode.
                    tile.content = null
                    tile.loadState = Tile3d.LoadState.UNLOADED
                    hasUnloadedRequests = true
                }
                content is GaussianContent -> {
                    // Sync here, not in the per-tile drawable enqueue: requested-but-not-
                    // selected tiles would otherwise pin their parse-time CPU arrays
                    // indefinitely and report 0 gpuByteCount to the SSE-pressure feedback.
                    if (!content.firstSyncDone && content.centerArrayQ != null) {
                        content.syncGaussianContentGpu(rc)
                    }
                    loadedGaussianTiles[tile] = frameCounter
                    if (content.isResourcesLoaded(rc)) {
                        content.touchCache(rc)
                        occupiedBytes += content.gpuByteCount
                    } else {
                        // sortJob lambda would otherwise pin the 10 b/splat CPU arrays until
                        // the coroutine and any queued GL upload both drain.
                        content.releaseSortState()
                        tile.content = null
                        tile.loadState = Tile3d.LoadState.UNLOADED
                        hasUnloadedRequests = true
                        loadedGaussianTiles.remove(tile)
                    }
                }
                content != null -> occupiedBytes += content.gpuByteCount
                tile.loadState == Tile3d.LoadState.UNLOADED && tile.contentUri != null -> {
                    requestContent(tile)
                    hasUnloadedRequests = true
                }
            }
        }
        sweepEvictedGaussianTiles()
        adjustMemorySSE(occupiedBytes, hasUnloadedRequests)
        reapInFlightFetches(result.requestedTiles)

        drainPendingMeshUploads(rc)

        lastSelectedTiles.clear()
        lastSelectedTiles.addAll(result.selectedTiles)
        for (tile in result.selectedTiles) enqueueDrawable(rc, tile)
        offerGaussianPass(rc)

        // Stamp touched tiles for the cold-subtree sweep.
        val now = frameCounter
        for (tile in result.selectedTiles) tile.lastSelectedFrame = now
        for (tile in result.requestedTiles) tile.lastSelectedFrame = now
        if (now % TILE_TREE_SWEEP_FRAMES == 0L) {
            tileset?.let { evictColdSubtrees(it.root, now, TILE_TREE_EVICT_THRESHOLD_FRAMES) }
        }

        // Sustain the load loop via rc.requestRedraw — WorldWind.requestRedraw() from inside
        // doRender is dropped by WorldWindow's isWaitingForRedraw gate.
        if (hasUnloadedRequests || inFlightFetches.isNotEmpty()) rc.requestRedraw()
    }

    /** Post-order walk returning subtree max [Tile3d.lastSelectedFrame]. Evicts every
     *  `.json`-grafted node whose subtree is cold for [thresholdFrames]+ — restores the
     *  wrapper URI for on-demand re-fetch. O(N) over the tree. */
    private fun evictColdSubtrees(root: Tile3d, now: Long, thresholdFrames: Long): Long {
        var maxFrame = root.lastSelectedFrame
        for (child in root.children) {
            val childMax = evictColdSubtrees(child, now, thresholdFrames)
            if (childMax > maxFrame) maxFrame = childMax
        }
        val graftUri = root.graftedFromUri
        if (graftUri != null && maxFrame < now - thresholdFrames) {
            root.children = emptyList()
            root.contentUri = graftUri
            root.graftedFromUri = null
            root.content = null
            root.loadState = Tile3d.LoadState.UNLOADED
            root.invalidateWorldBoundingSphere()
            return root.lastSelectedFrame  // descendants gone; only our own freshness propagates up
        }
        return maxFrame
    }

    /** SSE-pressure feedback: bump every frame when over budget; relax at most once per
     *  [SSE_RELAX_INTERVAL] when under [SSE_RELAX_OCCUPANCY_FRACTION]. The interval gate
     *  prevents per-frame relax→fetch→overshoot oscillation at borderline working sets. */
    /** On GL-context recreate, drop every loaded gaussian tile and purge its three RR-cache
     *  entries before the first post-resume draw. Android's `RenderResourceCache.clear()` is
     *  scheduled asynchronously by `destroyContext`, so without this the cache may still
     *  hand the drawable a `BufferObject` whose `id` is a dead old-context GL name. Mesh /
     *  points recover via the touch-loop `isResourcesLoaded` re-fetch path; only gaussian
     *  keeps per-tile state on the layer that needs an explicit reset. */
    private fun invalidateOnContextLoss(rc: RenderContext) {
        if (rc.contextVersion <= lastSeenContextVersion) return
        lastSeenContextVersion = rc.contextVersion
        if (loadedGaussianTiles.isEmpty()) return
        val cache = rc.renderResourceCache
        for ((tile, _) in loadedGaussianTiles) {
            (tile.content as? GaussianContent)?.let { c ->
                c.centersKey?.let { cache.remove(it) }
                c.attribsKey?.let { cache.remove(it) }
                c.sortIndicesKey?.let { cache.remove(it) }
                c.releaseSortState()
            }
            tile.content = null
            tile.loadState = Tile3d.LoadState.UNLOADED
        }
        loadedGaussianTiles.clear()
    }

    /** Evict gaussian tiles unseen for [GAUSSIAN_EVICTION_GRACE_FRAMES] — `centerArrayQ` +
     *  `sortIndexArray` would otherwise stay pinned via `tile.content`. Mesh content needs
     *  no equivalent: its CPU footprint is already dropped at `syncMeshContentGpu`. */
    private fun sweepEvictedGaussianTiles() {
        if (loadedGaussianTiles.isEmpty()) return
        val cutoff = frameCounter - GAUSSIAN_EVICTION_GRACE_FRAMES
        val iter = loadedGaussianTiles.entries.iterator()
        while (iter.hasNext()) {
            val (tile, lastSeen) = iter.next()
            if (lastSeen < cutoff) {
                (tile.content as? GaussianContent)?.releaseSortState()
                tile.content = null
                tile.loadState = Tile3d.LoadState.UNLOADED
                iter.remove()
            }
        }
    }

    private fun adjustMemorySSE(occupiedBytes: Long, hasUnloadedRequests: Boolean) {
        val floor = maxScreenSpaceError
        val ceil = floor * SSE_MAX_GROWTH_FACTOR
        if (occupiedBytes > maxMemoryFootprintBytes) {
            memoryAdjustedSSE = min(memoryAdjustedSSE * SSE_STEP_RATIO, ceil)
            return
        }
        val now = Clock.System.now()
        val comfortable = occupiedBytes < (maxMemoryFootprintBytes.toDouble() * SSE_RELAX_OCCUPANCY_FRACTION).toLong()
        if (comfortable && now - lastSseDecreaseAt >= SSE_RELAX_INTERVAL) {
            memoryAdjustedSSE = max(memoryAdjustedSSE / SSE_STEP_RATIO, floor)
            lastSseDecreaseAt = now
        }
    }

    /** Install parsed mesh content into the RR cache, capped by an EWMA-adapted cap that
     *  targets [TARGET_GL_FRAME_WORK_MS] of GL-thread upload work per frame — measured via
     *  [RenderContext.lastGLFrameWorkNanos]. Same target produces ~1 tile/frame on slow
     *  Mali, ~5+ tiles/frame on fast Apple / desktop. Leftovers drain next frame via
     *  `requestRedraw`. Freshly-cached textures route to a background-group drawable so
     *  their Bitmaps get GL-uploaded + recycled this same frame. */
    private fun drainPendingMeshUploads(rc: RenderContext) {
        // EWMA-smoothed feedback from last frame's GL-thread upload stall.
        val lastFrameMs = rc.lastGLFrameWorkNanos / 1_000_000.0
        ewmaGLFrameWorkMs = lastFrameMs * EWMA_ALPHA + ewmaGLFrameWorkMs * (1.0 - EWMA_ALPHA)
        adaptiveInstallCap = when {
            ewmaGLFrameWorkMs > TARGET_GL_FRAME_WORK_MS * 1.5 ->
                maxOf(adaptiveInstallCap - 1, MIN_INSTALL_CAP)
            ewmaGLFrameWorkMs < TARGET_GL_FRAME_WORK_MS * 0.5 ->
                minOf(adaptiveInstallCap + 1, MAX_INSTALL_CAP)
            else -> adaptiveInstallCap
        }
        // Eager-bind budget from MEASURED upload throughput: each batch times its own
        // GL-thread duration, and the budget targets a fixed bind slice. A rare oversized
        // batch spikes one frame yet barely moves a whole-frame-work average, so the
        // install cap's EWMA controller cannot push back on the bind tail.
        val bindBytes = lastEagerBindBytes
        if (bindBytes > 0) {
            lastEagerBindBytes = 0
            val bindMs = lastEagerBindNanos / 1_000_000.0
            if (bindMs > 0.05) {
                val bytesPerMs = bindBytes / bindMs
                ewmaBindBytesPerMs =
                    if (ewmaBindBytesPerMs == 0.0) bytesPerMs
                    else bytesPerMs * EWMA_ALPHA + ewmaBindBytesPerMs * (1.0 - EWMA_ALPHA)
                adaptiveEagerBindBudget = (ewmaBindBytesPerMs * TARGET_EAGER_BIND_MS).toInt()
                    .coerceIn(MIN_EAGER_BIND_BUDGET, MAX_EAGER_BIND_BUDGET)
            }
        }
        var loadedAny = false
        var stoppedAtBudget = false
        var uploaded = 0
        while (uploaded < adaptiveInstallCap) {
            val entry = pendingMeshUploads.tryReceive().getOrNull() ?: break
            uploaded++
            pendingUploadDepth--
            // One bad upload mustn't abort the frame — per-tile catch, FAIL the tile, keep draining.
            try {
                uploadMeshContent(entry.prep, entry.shell, rc)
                entry.tile.content = entry.shell
                entry.tile.loadState = Tile3d.LoadState.LOADED
                entry.shell.submeshes?.forEach { sub ->
                    sub.baseColorTextureKey?.let { key -> pendingEagerBinds.addLast(key) }
                }
                loadedAny = true
            } catch (t: Throwable) {
                entry.tile.loadState = Tile3d.LoadState.FAILED
                logMessage(
                    Logger.WARN, "Ogc3dTilesLayer", "drainPendingMeshUploads",
                    "upload failed for ${entry.tile.contentUri}", t,
                )
            }
        }
        if (uploaded >= adaptiveInstallCap) stoppedAtBudget = true
        // Byte-budgeted eager-bind batch; the rest carries over to following frames. Binding
        // every fresh texture in one frame stalled the GL thread 20-150 ms in city scenes -
        // a single 2048 mipmapped texture is ~21 MB of glTexImage2D. An oversized texture
        // still binds alone. Keys (not Texture refs) so an eviction while pending is a skip,
        // not a resurrected orphan GL texture.
        if (pendingEagerBinds.isNotEmpty()) {
            var batchBytes = 0
            var batch: ArrayList<Texture>? = null
            while (pendingEagerBinds.isNotEmpty() && batchBytes < adaptiveEagerBindBudget) {
                val texture = rc.renderResourceCache[pendingEagerBinds.removeFirst()] as? Texture ?: continue
                batchBytes += texture.byteCount
                (batch ?: ArrayList<Texture>(8).also { batch = it }).add(texture)
            }
            batch?.let { rc.offerBackgroundDrawable(TextureEagerBindDrawable(it, batchBytes)) }
        }
        // Redraw if we installed new content this frame OR if we hit the cap with entries
        // still pending OR if eager binds carried over — without it, an idle camera could
        // leave the channel or the bind queue sitting with work.
        if (loadedAny || stoppedAtBudget || pendingEagerBinds.isNotEmpty()) rc.requestRedraw()
    }

    // Adaptive throttling state — read + written only on the render thread, so plain vars.
    private var ewmaGLFrameWorkMs: Double = 0.0
    private var adaptiveInstallCap: Int = INITIAL_INSTALL_CAP

    /** RR-cache keys of freshly-installed textures awaiting their first GL bind; drained a
     *  byte-budgeted batch per frame by [drainPendingMeshUploads]. Render thread only. */
    private val pendingEagerBinds = ArrayDeque<Any>()
    private var adaptiveEagerBindBudget: Int = INITIAL_EAGER_BIND_BUDGET
    /** EWMA of measured texture-upload throughput in bytes per GL-thread millisecond. */
    private var ewmaBindBytesPerMs = 0.0
    /** Last batch measurement, written by the GL thread ([TextureEagerBindDrawable.draw]) and
     *  consumed once by the render thread. Nanos written BEFORE bytes - bytes > 0 publishes
     *  the pair. */
    @Volatile private var lastEagerBindNanos = 0L
    @Volatile private var lastEagerBindBytes = 0

    /** Force-binds freshly-cached textures so their first [Texture.allocTexImage] runs
     *  this frame, recycling the CPU-side Bitmap that would otherwise sit in Android
     *  native heap until the tile happens to draw. */
    private inner class TextureEagerBindDrawable(
        private val textures: List<Texture>,
        private val bytes: Int,
    ) : Drawable {
        override fun draw(dc: DrawContext) {
            val start = TimeSource.Monotonic.markNow()
            for (texture in textures) texture.bindTexture(dc)
            lastEagerBindNanos = start.elapsedNow().inWholeNanoseconds
            lastEagerBindBytes = bytes
        }
        override fun recycle() {}
    }

    /** Standard-ECEF → WorldWind-Y-up permutation. Pre-multiplied into every tileset's
     *  root transform so 3D Tiles bounding spheres land on the right side of the globe.
     *  Inverse permutation: WW(a,b,c) → ECEF(c,a,b). */
    private val standardEcefToWorldWindFrame = Matrix4().set(
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        1.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 1.0,
    )

    /** Apply / refresh the altitude offset baked into every tile's [Tile3d.tileToWorld].
     *  Short-circuits on [altitudeOffsetDirty]; only walks the tree when the effective
     *  translation actually changed. Translation = ellipsoid normal at the anchor × the
     *  mode-effective offset. RELATIVE/CLAMP mark dirty per frame so the offset retracks
     *  as elevation tiles load in. */
    private fun rebakeAltitudeOffset(rc: RenderContext, tileset: Tileset) {
        if (altitudeMode != AltitudeMode.ABSOLUTE) altitudeOffsetDirty = true
        if (!altitudeOffsetDirty) return

        val anchor = altitudeAnchor ?: resolveRootAnchor(rc, tileset.root) ?: run {
            altitudeOffsetDirty = false
            return
        }

        val terrain: Double = when (altitudeMode) {
            AltitudeMode.RELATIVE_TO_GROUND, AltitudeMode.CLAMP_TO_GROUND ->
                rc.globe.getElevation(anchor.latitude, anchor.longitude, retrieve = true)
            else -> 0.0
        }
        val effectiveOffset = when (altitudeMode) {
            AltitudeMode.ABSOLUTE -> altitudeOffset
            AltitudeMode.RELATIVE_TO_GROUND -> terrain + altitudeOffset
            AltitudeMode.CLAMP_TO_GROUND -> terrain - anchor.altitude + altitudeOffset
            else -> altitudeOffset
        }

        // Ellipsoid normal at anchor (ECEF), permuted into WW frame: ECEF(x,y,z) → WW(y,z,x).
        rc.globe.geographicToCartesianNormal(anchor.latitude, anchor.longitude, tmpUpEcef)
        val targetX = tmpUpEcef.y * effectiveOffset
        val targetY = tmpUpEcef.z * effectiveOffset
        val targetZ = tmpUpEcef.x * effectiveOffset

        val dx = targetX - appliedAltitudeTranslationWw.x
        val dy = targetY - appliedAltitudeTranslationWw.y
        val dz = targetZ - appliedAltitudeTranslationWw.z
        if (abs(dx) + abs(dy) + abs(dz) < 1e-3) {
            altitudeOffsetDirty = false
            return
        }
        walkTilesAndApplyDelta(tileset.root, dx, dy, dz)
        appliedAltitudeTranslationWw.set(targetX, targetY, targetZ)
        altitudeOffsetDirty = false
    }

    /** Translate every tile's [Tile3d.tileToWorld] by (dx, dy, dz) in WW frame. Adds
     *  directly to columns m[3]/m[7]/m[11] — equivalent to LEFT-multiply by a pure
     *  translation (a right-multiply would translate in each tile's local frame). */
    private fun walkTilesAndApplyDelta(root: Tile3d, dx: Double, dy: Double, dz: Double) {
        val stack = ArrayDeque<Tile3d>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val tile = stack.removeLast()
            val m = tile.tileToWorld.m
            m[3] += dx
            m[7] += dy
            m[11] += dz
            tile.invalidateWorldBoundingSphere()
            for (child in tile.children) stack.addLast(child)
        }
    }

    /** Resolve the root tile's bounding-volume centre (already in WW frame after parse) to
     *  a geographic anchor. Returns null when the tileset has no usable centre yet (rare
     *  — implicit-tiling roots without a materialised first child). */
    private fun resolveRootAnchor(rc: RenderContext, root: Tile3d): Position? {
        val sphere = root.worldBoundingSphere(rc.globe)
        if (sphere.radius <= 0.0) return null
        // WW(a,b,c) → ECEF(c,a,b) — inverse of standardEcefToWorldWindFrame's permutation.
        tmpAnchorEcef.set(sphere.center.z, sphere.center.x, sphere.center.y)
        rc.globe.cartesianToGeographic(tmpAnchorEcef.x, tmpAnchorEcef.y, tmpAnchorEcef.z, tmpAnchorPosition)
        return tmpAnchorPosition
    }

    private fun ensureRootRequested() {
        // Permanent-failure latch: once rootFailed is set, only resetRootFetch() un-sticks it.
        if (rootFetching || rootFailed) return
        rootFetching = true
        fetchQueue.fetchTileset(
            url = source.rootUri,
            // onSuccess is suspend so the parse runs inside the epoch coroutine — session recovery's
            // cancelAndJoin then waits for it, so a parse under an expired session can't win the race.
            onSuccess = { responseUrl, body ->
                try {
                    tileset = parseRootDocument(responseUrl, body)
                    WorldWind.requestRedraw()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    val preview = body.take(200).replace('\n', ' ')
                    logMessage(
                        Logger.ERROR, "Ogc3dTilesLayer", "ensureRootRequested",
                        "failed to parse root document for ${source.displayName}; " +
                            "URL ${source.rootUri} returned unparseable body (first 200 chars): $preview",
                        t,
                    )
                    rootFailed = true
                }
            },
            onFailure = {
                logMessage(
                    Logger.ERROR, "Ogc3dTilesLayer", "ensureRootRequested",
                    "tileset.json fetch failed permanently for ${source.displayName}: ${source.rootUri}", it,
                )
                rootFailed = true
            }
        )
    }

    /** Parse the root document into a [Tileset]. Default: a 3D Tiles `tileset.json`. Overridable so
     *  an I3S/SLPK layer can build the tree from `3dSceneLayer.json` + node pages instead. Suspends
     *  inside the fetch queue's epoch coroutine so session recovery can cancel/await it. */
    protected open suspend fun parseRootDocument(responseUrl: String, body: String): Tileset =
        TilesetParser.parse(
            body, responseUrl, source.authProvider,
            parentTransform = standardEcefToWorldWindFrame,
        )

    /** Decode tile content with no 3D-Tiles magic-byte signature (e.g. I3S geometry `.bin`); return
     *  true when handled (default: none). May suspend; build the mesh via [decodeAndEnqueueMesh]. */
    protected open suspend fun decodeCustomContent(tile: Tile3d, bytes: ByteArray, uri: String): Boolean = false

    /** Reset the permanent-failure latch so the next render retries the root fetch. */
    fun resetRootFetch() {
        rootFailed = false
        rootFetching = false
    }

    /** Reset a single tile's FAILED state so the next traversal re-fetches it. */
    fun retryFailedTile(tile: Tile3d) {
        if (tile.loadState == Tile3d.LoadState.FAILED) {
            tile.loadState = Tile3d.LoadState.UNLOADED
            WorldWind.requestRedraw()
        }
    }

    private fun requestContent(tile: Tile3d) {
        val uri = tile.contentUri ?: return
        if (tile.loadState != Tile3d.LoadState.UNLOADED) return
        tile.loadState = Tile3d.LoadState.FETCHING
        // Cesium-style "progressive resolution": coarser parents win over finer children
        // (parent always available as fallback render — no holes during refinement). At
        // same level, closer-to-camera wins. Sub-tileset .json wrappers pinned to the
        // top so structure resolves before any content tile competes.
        val priority = if (uri.endsWith(".json", ignoreCase = true)) {
            Double.POSITIVE_INFINITY
        } else {
            // GE weight (100×) keeps coarseness dominant; distance breaks ties at the same level.
            tile.geometricError * 100.0 - tile.traversalDistance
        }
        val request = TileContentRequest(
            tile = tile,
            contentUri = uri,
            priority = priority,
        )
        val job = fetchQueue.fetchContent(
            request = request,
            // Run handleContentFetched inside the fetch queue's semaphore (onSuccess is
            // suspend) so the permit gates parse + decode + enqueue end-to-end. Launching
            // a separate coroutine would un-cap parse concurrency and pile up decoded
            // bitmaps in native heap whenever cache hits make fetches instant.
            onSuccess = { bytes -> handleContentFetched(tile, bytes) },
            // Only a definitive HTTP status error is permanent. Cancellation + transient
            // IO (Ktor JS engine's `TypeError: Failed to fetch`, QUIC resets, DNS hiccups)
            // leave UNLOADED so the next traversal can retry — OkHttp on JVM/Android retries
            // those internally so they rarely surface, but on the JS fetch engine a single
            // transient miss would otherwise stick the tile as FAILED forever.
            onFailure = { t ->
                tile.loadState = when (t) {
                    is HttpStatusException -> Tile3d.LoadState.FAILED
                    else -> Tile3d.LoadState.UNLOADED
                }
            }
        )
        inFlightFetches[tile] = job
    }

    /** Drop completed jobs and cancel any whose tile is no longer in [activeTiles] —
     *  reclaims fetch-queue permits when the camera moves before the fetch lands. */
    private fun reapInFlightFetches(activeTiles: Collection<Tile3d>) {
        if (inFlightFetches.isEmpty()) return
        reapActiveSet.clear()
        reapActiveSet.addAll(activeTiles)
        val iter = inFlightFetches.entries.iterator()
        while (iter.hasNext()) {
            val (tile, job) = iter.next()
            when {
                job.isCompleted -> iter.remove()
                tile !in reapActiveSet -> {
                    job.cancel()
                    tile.loadState = Tile3d.LoadState.UNLOADED
                    iter.remove()
                }
            }
        }
    }

    private suspend fun handleContentFetched(tile: Tile3d, bytes: ByteArray) {
        tile.loadState = Tile3d.LoadState.PARSING
        val kind = ContentDispatcher.detect(bytes)
        val uri = tile.contentUri ?: ""
        try {
            when (kind) {
                ContentDispatcher.Kind.B3DM -> enqueueMeshUpload(tile, parseB3dm(bytes, uri))
                ContentDispatcher.Kind.I3DM -> enqueueMeshUpload(tile, parseI3dm(bytes, uri))
                ContentDispatcher.Kind.CMPT -> enqueueMeshUpload(tile, parseCmpt(bytes, uri))
                ContentDispatcher.Kind.PNTS -> attachPointCloudContent(tile, parsePnts(bytes, uri))
                ContentDispatcher.Kind.GLTF, ContentDispatcher.Kind.GLTF_JSON -> {
                    // glb can wrap Gaussian splats via KHR_gaussian_splatting; give the
                    // Gaussian loader first refusal so those payloads don't get parsed as
                    // empty mesh geometry.
                    val loader = gaussianLoader
                    if (loader != null && loader.supports(bytes)) {
                        gaussianParseSemaphore.withPermit {
                            attachGaussianContent(tile, parseGaussian(bytes, loader, uri))
                        }
                    } else if (kind == ContentDispatcher.Kind.GLTF) {
                        enqueueMeshUpload(tile, parseGltfBinary(bytes, uri))
                    } else {
                        enqueueMeshUpload(tile, parseGltfJson(bytes, uri))
                    }
                }
                ContentDispatcher.Kind.TILESET_JSON ->
                    attachExternalTileset(tile, bytes.decodeToString())
                else -> {
                    val loader = gaussianLoader
                    when {
                        // Source-specific content with no 3D-Tiles magic (e.g. I3S geometry .bin).
                        decodeCustomContent(tile, bytes, uri) -> {}
                        // No Gaussian codec has a settled magic — last-chance offer to the loader.
                        loader != null && loader.supports(bytes) -> gaussianParseSemaphore.withPermit {
                            attachGaussianContent(tile, parseGaussian(bytes, loader, uri))
                        }
                        else -> {
                            logMessage(
                                Logger.WARN, "Ogc3dTilesLayer", "handleContentFetched",
                                "unrecognised content kind $kind for ${tile.contentUri}; ${bytes.size} bytes"
                            )
                            tile.loadState = Tile3d.LoadState.FAILED
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // Don't poison the tile state on cooperative cancellation.
            tile.loadState = Tile3d.LoadState.UNLOADED
            throw cancelled
        } catch (t: Throwable) {
            logMessage(
                Logger.WARN, "Ogc3dTilesLayer", "handleContentFetched",
                "parse failed for ${tile.contentUri}", t
            )
            tile.loadState = Tile3d.LoadState.FAILED
        }
    }

    private class ParsedMesh(val shell: MeshContent, val prep: MeshContentPrep)

    private suspend fun parseB3dm(bytes: ByteArray, contentUri: String): ParsedMesh {
        val payload = B3dmLoader.parse(bytes)
        val shell = MeshContent(
            rtcCenter = payload.rtcCenter,
            batchLength = payload.batchLength,
            batchTable = payload.batchTable,
            skipYUpToZUp = !shouldRotateYToZ(payload.gltfUpAxisOverride),
        )
        val prep = prepareMeshPrep(payload.gltf, contentUri, shell)
        return ParsedMesh(shell, prep)
    }

    /**
     * 3-step `gltfUpAxis` resolution mirroring Cesium / ATAK practice:
     *  1. Per-tile override in the b3dm feature-table JSON (informal `gltfUpAxis` field).
     *  2. Tileset-level `asset.gltfUpAxis`.
     *  3. 3D Tiles 1.0 spec default = Y-up → apply `Rx(+90°)`. Works for both ENU-with-Y-up
     *     and EDN-with-Z-up publishers because the two non-standardnesses cancel out under
     *     the same rotation. X-up isn't supported (one-shot WARN + skip rotation). */
    private fun shouldRotateYToZ(b3dmOverride: String?): Boolean {
        val explicit = GltfUpAxis.fromStringOrNull(b3dmOverride) ?: tileset?.gltfUpAxis
        return when (explicit) {
            GltfUpAxis.Y, null -> true
            GltfUpAxis.Z -> false
            GltfUpAxis.X -> { logXUpOnce(); false }
        }
    }

    @Volatile private var xUpWarned = false
    private fun logXUpOnce() {
        if (!xUpWarned) {
            xUpWarned = true
            logMessage(
                Logger.WARN, "Ogc3dTilesLayer", "shouldRotateYToZ",
                "tileset declares gltfUpAxis=X; X-up rotation isn't applied yet — content will appear sideways",
            )
        }
    }

    /** Raw GLB (3D Tiles 1.1 / Google Photorealistic / any tileset whose content URIs end
     *  in `.glb`). Same axis resolution as b3dm — Google specifically is `asset.version 1.0`
     *  with `extensionsUsed: 3DTILES_content_gltf`, so the spec-default gltfUpAxis = Y
     *  applies + the standard Y-up→Z-up rotation is needed. */
    private suspend fun parseGltfBinary(bytes: ByteArray, contentUri: String): ParsedMesh {
        val glb = GlbReader.parse(bytes)
        val gltf = GltfReader.parse(glb.jsonText, glb.binChunk)
        val skipRotation = !shouldRotateYToZ(null)
        val shell = MeshContent(rtcCenter = null, skipYUpToZUp = skipRotation)
        val prep = prepareMeshPrep(gltf, contentUri, shell)
        return ParsedMesh(shell, prep)
    }

    /** JSON-only glTF (rare in 3D Tiles; handled for completeness). */
    private suspend fun parseGltfJson(bytes: ByteArray, contentUri: String): ParsedMesh {
        val gltf = GltfReader.parse(bytes.decodeToString(), null)
        val shell = MeshContent(rtcCenter = null, skipYUpToZUp = !shouldRotateYToZ(null))
        val prep = prepareMeshPrep(gltf, contentUri, shell)
        return ParsedMesh(shell, prep)
    }

    /** Graft a sub-tileset.json's root as a child of [parent] (3D Tiles 1.0/1.1 external
     *  tilesets; Google Photorealistic uses this per quadrant). */
    private fun attachExternalTileset(parent: Tile3d, body: String) {
        val parentUri = parent.contentUri ?: return
        // Feed the nested tileset's URL + body to the auth provider so it can refresh any
        // per-subtree credentials embedded in this tileset.json (Google's `?session=` token
        // rotates per subtree — without this, deeper content URIs get rewritten with the
        // ROOT's session token and Google rejects them with HTTP 400).
        source.authProvider.observeTilesetResponse(parentUri, parentUri, body)
        val subTileset = try {
            TilesetParser.parse(
                body, parentUri, source.authProvider,
                parentTransform = parent.tileToWorld,
                parentRefinement = parent.refinement,
            )
        } catch (t: Throwable) {
            logMessage(
                Logger.WARN, "Ogc3dTilesLayer", "attachExternalTileset",
                "failed to parse external tileset $parentUri: ${t::class.simpleName}: ${t.message}"
            )
            parent.loadState = Tile3d.LoadState.FAILED
            return
        }
        // Graft the sub-tileset root in; null contentUri so the traverser refines through.
        // graftedFromUri preserves the original .json URI for the eviction sweep to restore.
        parent.graftedFromUri = parent.contentUri
        parent.children = listOf(subTileset.root)
        parent.contentUri = null
        parent.loadState = Tile3d.LoadState.LOADED
        WorldWind.requestRedraw()
    }

    private suspend fun parseI3dm(bytes: ByteArray, contentUri: String): ParsedMesh {
        val payload = I3dmLoader.parse(bytes)
        val shell = MeshContent(
            rtcCenter = payload.rtcCenter,
            instancePositions = payload.positions,
            instanceScales = payload.scales,
            instancesLength = payload.instancesLength,
            skipYUpToZUp = !shouldRotateYToZ(null),
        )
        val prep = prepareMeshPrep(payload.gltf, contentUri, shell)
        return ParsedMesh(shell, prep)
    }

    /** Composite payload — pick the first b3dm/i3dm inner; multi-content not supported. */
    private suspend fun parseCmpt(bytes: ByteArray, contentUri: String): ParsedMesh {
        val inners = CmptLoader.parse(bytes)
        val firstMesh = inners.firstOrNull { it.magic == "b3dm" }
            ?: inners.firstOrNull { it.magic == "i3dm" }
            ?: error("cmpt has no b3dm/i3dm sub-payload (only ${inners.map { it.magic }})")
        return when (firstMesh.magic) {
            "b3dm" -> parseB3dm(firstMesh.bytes, contentUri)
            "i3dm" -> parseI3dm(firstMesh.bytes, contentUri)
            else -> error("unreachable")
        }
    }

    private fun parsePnts(bytes: ByteArray, contentUri: String): PointCloudContent {
        val payload = PntsLoader.parse(bytes)
        return PointCloudContent(rtcCenter = payload.rtcCenter).also {
            it.preparePointCloudContent(payload, contentUri)
            // payload.colors is pool-borrowed by packRgba; preparePointCloudContent consumed it.
            ByteArrayPool.release(payload.colors)
        }
    }

    private fun attachPointCloudContent(tile: Tile3d, content: PointCloudContent) {
        tile.content = content
        tile.loadState = Tile3d.LoadState.LOADED
        WorldWind.requestRedraw()
    }

    private fun parseGaussian(bytes: ByteArray, loader: GaussianLoader, contentUri: String): GaussianContent {
        val payload = loader.parse(bytes)
        return GaussianContent(rtcCenter = payload.rtcCenter).also {
            it.prepareGaussianContent(payload, contentUri)
        }
    }

    private fun attachGaussianContent(tile: Tile3d, content: GaussianContent) {
        tile.content = content
        tile.loadState = Tile3d.LoadState.LOADED
        WorldWind.requestRedraw()
    }

    /** Hand a parsed mesh to the render thread for RR-cache upload. Suspends on a full
     *  bounded queue, holding the fetch permit — the back-pressure path. */
    private suspend fun enqueueMeshUpload(tile: Tile3d, parsed: ParsedMesh) {
        pendingUploadDepth++
        pendingMeshUploads.send(MeshUploadEntry(tile, parsed.shell, parsed.prep))
        WorldWind.requestRedraw()
    }

    /** Build a [MeshContent] from a decoded [GltfModel] and hand it to the render-thread upload queue —
     *  the entry point a [decodeCustomContent] override uses. [contentUri] keys RR-cache buffers;
     *  [skipYUpToZUp] suppresses the glTF Y-up→Z-up rotation. */
    protected suspend fun decodeAndEnqueueMesh(
        tile: Tile3d, model: GltfModel, contentUri: String, skipYUpToZUp: Boolean,
    ) {
        val shell = MeshContent(rtcCenter = null, skipYUpToZUp = skipYUpToZUp)
        val prep = prepareMeshPrep(model, contentUri, shell)
        enqueueMeshUpload(tile, ParsedMesh(shell, prep))
    }

    private val scratchEffectiveTransform = Matrix4()
    private val scratchWorldSphere = BoundingSphere()
    private val scratchUpVector = Vec3()
    private val scratchTileOriginInLocal = Vec3()

    /** glTF Y-up → tile Z-up rotation (3D Tiles 1.0 §3.4.7 / 1.1 implicit). */
    private val yUpToZUp = Matrix4().set(
        1.0, 0.0,  0.0, 0.0,
        0.0, 0.0, -1.0, 0.0,
        0.0, 1.0,  0.0, 0.0,
        0.0, 0.0,  0.0, 1.0,
    )

    /** Compose the tile's effective model transform into [scratchEffectiveTransform] and return it:
     *  `tileToWorld * RTC_CENTER? * yUpToZUp?`. Shared by the mesh / points / gaussian enqueue paths,
     *  which differ only in whether the glTF Y-up→Z-up step applies (mesh: unless skipped; points:
     *  never — pnts are Z-up per §3.4.7; gaussian: always). */
    private fun composeEffectiveTransform(tile: Tile3d, rtcCenter: DoubleArray?, applyYUpToZUp: Boolean): Matrix4 {
        scratchEffectiveTransform.copy(tile.tileToWorld)
        rtcCenter?.let { scratchEffectiveTransform.multiplyByTranslation(it[0], it[1], it[2]) }
        if (applyYUpToZUp) scratchEffectiveTransform.multiplyByMatrix(yUpToZUp)
        return scratchEffectiveTransform
    }

    /** Register one tile-level PickedObject so pick-mode colour readback resolves the tile's
     *  [pickColor] back to [tile]. Shared by the points / gaussian paths and the mesh tile-level
     *  (non-batched) fallback. */
    private fun registerTilePick(rc: RenderContext, tile: Tile3d, pickColor: Color) {
        val pickedObjectId = rc.nextPickedObjectId()
        PickedObject.identifierToUniqueColor(pickedObjectId, pickColor)
        rc.offerPickedObject(
            PickedObject.fromUserObject(pickedObjectId, tile, rc.currentLayer, useTerrainPosition = false)
        )
    }

    /** Dispatch the per-content-type drawable for the color pass. */
    private fun enqueueDrawable(rc: RenderContext, tile: Tile3d) {
        val uri = tile.contentUri ?: return
        when (val tileContent = tile.content) {
            is MeshContent -> enqueueMeshDrawable(rc, tile, tileContent, uri)
            is PointCloudContent -> enqueuePointCloudDrawable(rc, tile, tileContent, uri)
            is GaussianContent -> enqueueGaussianDrawable(rc, tile, tileContent, uri)
            else -> return
        }
    }

    private fun enqueueMeshDrawable(rc: RenderContext, tile: Tile3d, content: MeshContent, uri: String) {
        val submeshes = content.submeshes ?: return
        if (submeshes.isEmpty()) return

        val pool = rc.getDrawablePool(DrawableTileMesh.KEY)
        val drawable = DrawableTileMesh.obtain(pool)
        drawable.content = content
        drawable.program = Ogc3dTilesProgram.get(rc)
        drawable.shadowMode = shadowMode
        drawable.isFallback = tile.isFallback
        drawable.stencilId = tile.stencilId
        drawable.isOccluderOnly = tile.isShadowOnly
        // Refs resolved once in uploadMeshContent — no per-frame HashMap.getNode. Post-eviction
        // bindBuffer/bindTexture returns false → drawSubmesh skips, same as the prior cache-miss path.
        drawable.ensureSubmeshArrays(submeshes.size)
        val textures = drawable.submeshTextures
        val buffers = drawable.submeshBuffers
        // Indexed for-loop: withIndex() allocates an IndexedValue per submesh, hot at globe
        // scale where the same loop runs for hundreds of visible tiles per frame.
        val n = submeshes.size
        for (idx in 0 until n) {
            val submesh = submeshes[idx]
            buffers[idx] = submesh.buffer
            textures[idx] = submesh.baseColorTexture
        }

        // Composition per spec: tileToWorld * RTC_CENTER * yUpToZUp. Raw glTF
        // (3D Tiles 1.1 / Google Photorealistic) skips the Y→Z step.
        drawable.tileToWorld.copy(composeEffectiveTransform(tile, content.rtcCenter, applyYUpToZUp = !content.skipYUpToZUp))

        // World-bounding sphere for cascade culling + draw-order sort. Tile3d already caches
        // a globe-resolved sphere; we copy here so the drawable doesn't hold a back-reference.
        val sphere = tile.worldBoundingSphere(rc.globe)
        drawable.setWorldBounds(sphere.center, sphere.radius)
        scratchWorldSphere.set(sphere.center, sphere.radius)

        if (rc.isPickMode) {
            val batchTable = content.batchTable
            val batchLength = content.batchLength
            // Use per-feature picking only when (a) the b3dm shipped a batch table and a
            // positive batch length, (b) at least one submesh actually has a batch-id
            // buffer in the RR cache (else the shader will sample default 0 for every
            // vertex — every fragment writes batch 0's color, defeating the point). Falls
            // back to tile-level picking otherwise (one PickedObject for the whole tile).
            // ALL submeshes must carry a batch-id buffer before we enable batch picking —
            // partial coverage would let the non-batched submeshes' disabled vertexBatchId
            // attribute default to zero, painting every fragment with batch 0's pick color
            // and falsely resolving every click on those submeshes to BatchedFeature(0).
            // batchIdOffset >= 0 means the submesh's combined buffer has a batchId section.
            val allBatchIds = submeshes.all { it.batchIdOffset >= 0 }
            if (batchTable != null && batchLength > 0 && allBatchIds) {
                val base = rc.nextPickedObjectId()
                drawable.pickIdBase = base
                // Shader does pickId = pickIdBase + vertexBatchId, so IDs must be contiguous from `base`.
                rc.offerPickedObject(
                    PickedObject.fromUserObject(
                        base, BatchedFeature(tile, 0, batchTable),
                        rc.currentLayer, useTerrainPosition = false,
                    )
                )
                for (b in 1 until batchLength) {
                    val id = rc.nextPickedObjectId()
                    rc.offerPickedObject(
                        PickedObject.fromUserObject(
                            id, BatchedFeature(tile, b, batchTable),
                            rc.currentLayer, useTerrainPosition = false,
                        )
                    )
                }
            } else {
                drawable.pickIdBase = 0
                registerTilePick(rc, tile, drawable.pickColor)
            }
        }

        // SURFACE group with NEG_INFINITY sort key — mesh draws first in SURFACE, before
        // terrain's depth-only quad (TERRAIN_DEPTH_SORT_KEY = -Double.MAX_VALUE), so its
        // GROUND_COVERED_BIT stencil writes are in place when BasicDrawableTerrain reads
        // them. See [DrawableGroup.SURFACE] for the within-group ordering convention.
        rc.offerSurfaceDrawable(drawable, Double.NEGATIVE_INFINITY)
    }

    /** pnts equivalent of [enqueueMeshDrawable]. */
    private fun enqueuePointCloudDrawable(
        rc: RenderContext, tile: Tile3d, content: PointCloudContent, uri: String,
    ) {
        content.syncPointCloudContentGpu(rc)
        if (content.pointCount <= 0) return
        // Resolve the VBO fresh from the RR cache. Mirrors [enqueueMeshDrawable] /
        // [enqueueGaussianDrawable]: eviction surfaces as a null, draw skips, the touch loop
        // catches the cache miss next frame and triggers a re-fetch — no stale-ref bind.
        val vbo = rc.renderResourceCache[content.vboKey ?: return] as? BufferObject ?: return

        val pool = rc.getDrawablePool(DrawableTilePoints.KEY)
        val drawable = DrawableTilePoints.obtain(pool)
        drawable.content = content
        drawable.vertexBuffer = vbo
        drawable.program = Ogc3dTilesPointsProgram.get(rc)
        drawable.shadowMode = shadowMode
        drawable.isFallback = tile.isFallback
        drawable.stencilId = tile.stencilId
        drawable.isOccluderOnly = tile.isShadowOnly
        drawable.basePointSize = pointSize

        // Keeps on-screen point size uniform with distance.
        drawable.focalLengthPixels = ScreenSpaceError.focalLengthPixels(rc)

        // pnts are Z-up per 3D Tiles 1.0 §3.4.7 — no yUpToZUp, only RTC composition.
        drawable.tileToWorld.copy(composeEffectiveTransform(tile, content.rtcCenter, applyYUpToZUp = false))

        val sphere = tile.worldBoundingSphere(rc.globe)
        drawable.setWorldBounds(sphere.center, sphere.radius)

        if (rc.isPickMode) registerTilePick(rc, tile, drawable.pickColor)

        // SURFACE / NEG_INFINITY — same as mesh, so points write GROUND_COVERED_BIT before
        // terrain reads it. See [DrawableGroup.SURFACE] for the ordering convention.
        rc.offerSurfaceDrawable(drawable, Double.NEGATIVE_INFINITY)
    }

    /** Gaussian-splat equivalent of [enqueuePointCloudDrawable]. */
    private fun enqueueGaussianDrawable(
        rc: RenderContext, tile: Tile3d, content: GaussianContent, uri: String,
    ) {
        content.syncGaussianContentGpu(rc)
        if (content.splatCount <= 0) return
        // Resolve fresh BufferObject refs from the RR cache into the drawable. Mirrors the
        // [enqueueMeshDrawable] pattern — eviction between frames (or partial within-frame
        // eviction from another tile's sync) surfaces as a null slot which the draw skips,
        // instead of leaving the drawable bound to a stale `content.centers` ref whose GL
        // name has been deleted or recycled into another buffer.
        val cache = rc.renderResourceCache
        val centersBuf = cache[content.centersKey ?: return] as? BufferObject ?: return
        val attribsBuf = cache[content.attribsKey ?: return] as? BufferObject ?: return
        val sortIndicesBuf = cache[content.sortIndicesKey ?: return] as? BufferObject ?: return

        val pool = rc.getDrawablePool(DrawableTileGaussian.KEY)
        val drawable = DrawableTileGaussian.obtain(pool)
        drawable.content = content
        drawable.centers = centersBuf
        drawable.attribs = attribsBuf
        drawable.sortIndices = sortIndicesBuf
        drawable.program = Ogc3dTilesGaussianProgram.get(rc)
        drawable.shadowMode = shadowMode
        drawable.splatSizeMultiplier = splatSizeMultiplier
        drawable.minAlpha = gaussianMinAlpha

        drawable.focalLengthPixels = ScreenSpaceError.focalLengthPixels(rc)

        drawable.tileToWorld.copy(composeEffectiveTransform(tile, content.rtcCenter, applyYUpToZUp = true))

        // View-aware sort: forward-into-scene in tile-local space = -((mv * tileToWorld) row 2),
        // computed directly from the row dot to stay correct when tileToWorld carries non-uniform
        // scale or shear (3D Tiles spec allows it; an inverse-rotation derivation would silently
        // skew). Normalize so the cos(0.2°) resort gate stays calibrated across tiles with
        // different scales.
        val mv = rc.modelview.m
        val tw = scratchEffectiveTransform.m
        val rfx = -(mv[8] * tw[0] + mv[9] * tw[4] + mv[10] * tw[8])
        val rfy = -(mv[8] * tw[1] + mv[9] * tw[5] + mv[10] * tw[9])
        val rfz = -(mv[8] * tw[2] + mv[9] * tw[6] + mv[10] * tw[10])
        val rfMag = sqrt(rfx * rfx + rfy * rfy + rfz * rfz)
        val invM = if (rfMag > 0.0) (1.0 / rfMag).toFloat() else 1f
        content.resortByCamera(rc, rfx.toFloat() * invM, rfy.toFloat() * invM, rfz.toFloat() * invM)

        val sphere = tile.worldBoundingSphere(rc.globe)
        drawable.setWorldBounds(sphere.center, sphere.radius)

        if (rc.isPickMode) {
            // Register the tile so resolvePick's color-readback decodes the pick-mode fragment's
            // `pickColor` back to a PickedObject. Without this, splats colour the pick FBO but
            // resolvePick falls through to terrain and the splat depth is ignored.
            registerTilePick(rc, tile, drawable.pickColor)
        }

        // Back-to-front sort: depthMask is off, so cross-tile pixel order must follow distance.
        val depthSq = sphere.center.distanceToSquared(rc.cameraPoint)
        if (rc.isPickMode || gaussianRenderScale >= 1f) {
            // Direct full-resolution path. Pick stays direct: it needs full-res depth writes
            // + pick colors in the pick framebuffer, not an offscreen color composite.
            rc.offerShapeDrawable(drawable, depthSq)
        } else {
            // Reduced-resolution path: collect into one offscreen pass drawable per frame,
            // offered after the enqueue loop with the nearest child's depth.
            val pass = gaussianPass ?: DrawableGaussianPass.obtain(rc.getDrawablePool(DrawableGaussianPass.KEY)).also {
                it.depthProgram = rc.getShaderProgram { SceneDepthProgram() }
                it.compositeProgram = GaussianCompositeProgram.get(rc)
                it.renderScale = gaussianRenderScale
                it.owner = this
                gaussianPass = it
            }
            pass.addChild(drawable, depthSq)
        }
    }

    /** Offers the frame's collected splat pass, if any, and resets the per-frame collector. */
    private fun offerGaussianPass(rc: RenderContext) {
        val pass = gaussianPass ?: return
        gaussianPass = null
        rc.offerShapeDrawable(pass, pass.nearestChildDepthSq)
    }

    /** Stop fetch coroutines + close the HTTP client. */
    fun shutdown() {
        fetchQueue.shutdown()
        scope.cancel()
    }

    companion object {
        /** Default [maxMemoryFootprintBytes]: 256 MiB. Soft SSE-pressure trigger — when
         *  exceeded, effective SSE drifts up to load coarser tiles. Sized to throttle
         *  the working set BEFORE RenderResourceCache's own LRU starts evicting (typical
         *  RR-cache capacity is 3/16 of system RAM ≈ 750 MB on a 4 GB phone). If 3D Tiles
         *  consumption climbs past the RR-cache budget the LRU evicts visible tiles every
         *  drainPendingMeshUploads call, causing per-frame fallback-tile flicker. */
        const val DEFAULT_MAX_MEMORY_FOOTPRINT_BYTES: Long = 256L * 1024 * 1024

        /** SSE-pressure step ratio (small enough to converge over 10-30 frames). */
        private const val SSE_STEP_RATIO: Double = 1.02

        /** Cap on effective SSE drift above [maxScreenSpaceError]. */
        private const val SSE_MAX_GROWTH_FACTOR: Double = 4.0

        /** Working-set fraction below which the relax half of the loop kicks in. */
        private const val SSE_RELAX_OCCUPANCY_FRACTION: Double = 0.70

        /** Minimum gap between consecutive SSE relaxations. Slow enough to absorb a
         *  brief working-set spike from one upload without flipping back, fast enough to
         *  walk SSE back to floor in a couple seconds after the load wave settles. */
        private val SSE_RELAX_INTERVAL = 500.milliseconds

        /** Target total GL-thread frame-work ms (= [RenderContext.lastGLFrameWorkNanos]).
         *  Sub-vsync (16.7 ms) so install bursts don't push next frame over budget. */
        private const val TARGET_GL_FRAME_WORK_MS: Double = 12.0

        /** EWMA smoothing factor. 0.3 reacts in ~3 frames while damping single-frame
         *  spikes (GC pauses etc.). */
        private const val EWMA_ALPHA: Double = 0.3

        /** Install-cap bounds and starting value before the first measurement lands. */
        private const val INITIAL_INSTALL_CAP: Int = 2
        private const val MIN_INSTALL_CAP: Int = 1
        private const val MAX_INSTALL_CAP: Int = 8

        /** GL-thread milliseconds one eager-bind batch may take; the byte budget is this
         *  slice times the measured upload throughput. Sub-half-vsync at 120 Hz. */
        private const val TARGET_EAGER_BIND_MS: Double = 3.0

        /** Eager-bind byte-budget bounds and starting value before the first batch
         *  measurement lands; the working value tracks measured throughput. */
        private const val INITIAL_EAGER_BIND_BUDGET: Int = 8 * 1024 * 1024
        private const val MIN_EAGER_BIND_BUDGET: Int = 2 * 1024 * 1024
        private const val MAX_EAGER_BIND_BUDGET: Int = 32 * 1024 * 1024

        /** Pending-upload channel capacity. Back-pressures parsers when uploads can't keep
         *  up. Each in-flight prep retains a decoded bitmap + combined-vertex bytes (~5 MB
         *  CPU memory for Photoreal); 4 in-flight keeps Android native-heap headroom even
         *  with a busy parser pool. Higher values trigger OOM at globe scale. */
        private const val PENDING_UPLOAD_CHANNEL_CAPACITY: Int = 4

        /** Shape-drawable sort key for 3D-Tiles content. Forces every tile to the same
         *  far-distance bucket so DrawableQueue's sort ties on it; the insertion order
         *  set by [Traverser] (non-fallback finest first, fallback coarsest first) then
         *  wins, which is what the stencil masking depends on. */
        private const val TILE_3D_SHAPE_SORT_SENTINEL: Double = 1e20

        /** Frames a gaussian tile can stay outside `requestedTiles` before [sweepEvictedGaussianTiles]
         *  reclaims it. ~1 s at 60 fps — long enough to absorb brief culling stutter and camera
         *  oscillation, short enough to keep memory bounded. */
        private const val GAUSSIAN_EVICTION_GRACE_FRAMES: Long = 60

        /** Sweep interval for cold-subtree eviction. ~2 s at 60 fps. */
        private const val TILE_TREE_SWEEP_FRAMES: Long = 120

        /** `.json`-grafted subtrees idle this many frames (~10 s at 60 fps) get evicted. */
        private const val TILE_TREE_EVICT_THRESHOLD_FRAMES: Long = 600
    }
}
