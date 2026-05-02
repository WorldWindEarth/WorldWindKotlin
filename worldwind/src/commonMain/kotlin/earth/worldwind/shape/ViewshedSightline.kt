package earth.worldwind.shape

import earth.worldwind.WorldWind
import earth.worldwind.draw.DrawableViewshedKernel
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.globe.Globe
import earth.worldwind.globe.elevation.coverage.ElevationCoverage.Companion.MISSING_DATA
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import earth.worldwind.render.program.ViewshedKernelShaderProgram
import earth.worldwind.util.kgl.GL_RGBA
import earth.worldwind.util.kgl.GL_RGBA8
import earth.worldwind.util.kgl.GL_UNSIGNED_BYTE
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.jvm.JvmOverloads
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The geographic footprint that a [ViewshedSightline] analyses.
 */
sealed class ViewshedArea {
    /**
     * Circular footprint centred on the observer.
     *
     * @param radius distance in meters from the observer to the perimeter
     */
    data class Circle(val radius: Double) : ViewshedArea() {
        init {
            require(radius > 0.0) {
                logMessage(ERROR, "ViewshedArea.Circle", "constructor", "Radius must be positive")
            }
        }
    }

    /**
     * Axis-aligned (north/east) rectangular footprint centred on the observer.
     *
     * @param widthMeters east-west extent in meters
     * @param heightMeters north-south extent in meters
     */
    data class Rectangle(val widthMeters: Double, val heightMeters: Double) : ViewshedArea() {
        init {
            require(widthMeters > 0.0 && heightMeters > 0.0) {
                logMessage(ERROR, "ViewshedArea.Rectangle", "constructor", "Width and height must be positive")
            }
        }
    }
}

/**
 * Visibility-from-observer overlay computed against the source elevation model rather than the rendered
 * terrain mesh. Sampling resolution is decoupled from the camera's terrain LoD, so far-away coverage areas
 * remain accurate even when the visible terrain tiles are coarse.
 *
 * Each dirty render frame snapshots inputs and starts a coroutine that samples the elevation grid off the
 * render thread on [Dispatchers.Default]. When sampling completes, the next render frame populates a
 * [DrawableViewshedKernel] with the snapshot + elevation array and enqueues it via
 * [RenderContext.offerSurfaceDrawable]. The drawable runs on the GL thread during that frame's draw
 * phase: uploads the elevations as an `R32F` texture, runs the per-fragment Amanatides–Woo kernel via
 * [earth.worldwind.render.program.ViewshedKernelShaderProgram] into an RGBA8 render target, and the
 * `SurfaceImage` samples that texture in place — no CPU readback round-trip.
 *
 * Requires GLES 3 / WebGL 2 / GL 3.3 core for `R32F` sampling and `texelFetch`. The drawable detects
 * `Kgl.supportsSizedTextureFormats == false` and silently skips rather than rendering garbage.
 *
 * **GPU memory cost** is roughly `samplesPerSide² * 12` bytes per shape: 4 B for the RGBA8 output
 * texture + 4 B for the R32F elevation sampler + ~4 B for the FBO depth/scratch state. At the default
 * 1024² that's ~12 MB GPU, plus two pooled CPU `FloatArray`s of `samplesPerSide² * 4` bytes (8 MB) on
 * the host side. Multiple sightlines multiply linearly. Use [samplesForResolution] to size against the
 * underlying DEM rather than oversampling - kernel work is `O(N³)` so dropping N saves both memory
 * and GPU time. Call [dispose] when removing a sightline to reclaim GPU resources before engine
 * teardown.
 */
open class ViewshedSightline @JvmOverloads constructor(
    position: Position,
    area: ViewshedArea,
    samplesPerSide: Int = 1024,
    /**
     * Attributes used to color terrain that is visible from the observer. Only `interiorColor` is consumed.
     */
    override var attributes: ShapeAttributes = ShapeAttributes(),
) : AbstractRenderable(), Attributable, Highlightable, Movable {
    /**
     * The observer's geographic position. Resolution honors [altitudeMode] when the position is sampled.
     * Mutated in place (via [Position.copy]) and detected by [doRender] comparing against
     * [cachedPosition], so direct field writes (`position.latitude = ...`) trigger a recompute too.
     */
    var position = Position(position)
        set(value) { field.copy(value) }
    /**
     * The geographic footprint analysed by this sightline. Replacing it triggers a recompute.
     */
    var area: ViewshedArea = area
        set(value) {
            if (field != value) {
                field = value
                cancelInFlightSample()
                invalidate()
            }
        }
    /**
     * Number of grid samples along each side of the area-of-interest (output raster is `samplesPerSide` square).
     * Clamped to `[2, MAX_SAMPLES_PER_SIDE]` (default `MAX_SAMPLES_PER_SIDE` is 4096) to prevent
     * GPU allocation footguns - at 4096² the output + sampler textures alone are ~64 MB each.
     *
     * @throws IllegalArgumentException if the value is outside `[2, MAX_SAMPLES_PER_SIDE]`
     */
    var samplesPerSide: Int = samplesPerSide
        set(value) {
            require(value in 2..MAX_SAMPLES_PER_SIDE) {
                logMessage(ERROR, "ViewshedSightline", "setSamplesPerSide",
                    "Samples per side must be in [2, $MAX_SAMPLES_PER_SIDE]")
            }
            if (field != value) {
                field = value
                cancelInFlightSample()
                invalidate()
            }
        }
    /**
     * Altitude reference for [position]. Defaults to ABSOLUTE.
     */
    override var altitudeMode: AltitudeMode = AltitudeMode.ABSOLUTE
        set(value) { if (field != value) { field = value; invalidate() } }
    /**
     * Attributes used to color terrain that is occluded from the observer.
     */
    var occludeAttributes: ShapeAttributes = ShapeAttributes().apply { interiorColor.copy(Color(1f, 0f, 0f, 1f)) }
    override var isHighlighted: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }
    override var highlightAttributes: ShapeAttributes? = null
        set(value) { field = value; invalidate() }
    override val referencePosition: Position get() = position
    override val isPointShape get() = true
    /**
     * Picking flows through the inner per-window `surfaceImage` (it owns the surface texture
     * drawable the user actually clicks on), so propagate the outer's pick state onto every
     * window's [SurfaceImage]. Without this, a click on the viewshed overlay returns the
     * internal [SurfaceImage] as the picked `userObject` instead of this [ViewshedSightline].
     * The outer's `render()` gate already suppresses pick rendering when [isPickEnabled] is
     * false, but we still mirror the flag onto each [SurfaceImage] for defence in depth.
     */
    override var isPickEnabled: Boolean = true
        set(value) {
            field = value
            states.values.forEach { it.surfaceImage?.isPickEnabled = value }
        }
    override var pickDelegate: Any? = null
        set(value) {
            field = value
            states.values.forEach { it.surfaceImage?.pickDelegate = value ?: this }
        }
    override var displayName: String? = null
        set(value) {
            field = value
            states.values.forEach { it.surfaceImage?.displayName = value }
        }

    /**
     * Resolution multiplier applied to [samplesPerSide] while the observer is being dragged.
     * When the position has been settled for [settleFrames] frames, the kernel re-runs at full
     * resolution. `1.0` disables draft mode (always full-res). Default `0.5` halves both axes
     * during interaction for ~4× faster GPU dispatch.
     *
     * @throws IllegalArgumentException if not in `(0.0, 1.0]`
     */
    var draftScale: Double = 0.5
        set(value) {
            require(value > 0.0 && value <= 1.0) {
                logMessage(ERROR, "ViewshedSightline", "setDraftScale", "draftScale must be in (0.0, 1.0]")
            }
            if (field != value) {
                field = value
                invalidate()
            }
        }
    /**
     * Number of consecutive frames with no position change required to trigger a full-resolution
     * refresh after a draft-resolution dispatch. Default ~30 frames (~0.5 s at 60 fps).
     *
     * @throws IllegalArgumentException if non-positive
     */
    var settleFrames: Int = 30
        set(value) {
            require(value > 0) {
                logMessage(ERROR, "ViewshedSightline", "setSettleFrames", "settleFrames must be positive")
            }
            field = value
        }
    /**
     * Number of consecutive frames with a stable [RenderContext.elevationModelTimestamp]
     * required before a tile-arrival burst triggers a re-sample. Default ~30 frames (~0.5 s at
     * 60 fps). On Kotlin/JS where the elevation grid sample runs on the event loop, an
     * unfiltered tick-per-tile would stall the page repeatedly during initial map load;
     * debouncing collapses the burst into one sample after tiles settle. Position changes
     * still bypass this and trigger immediately.
     *
     * @throws IllegalArgumentException if non-positive
     */
    var elevationDebounceFrames: Int = 30
        set(value) {
            require(value > 0) {
                logMessage(ERROR, "ViewshedSightline", "setElevationDebounceFrames", "elevationDebounceFrames must be positive")
            }
            field = value
        }

    protected var dirty = true
    protected var lastElevationModelTimestamp = 0L
    /** Latest seen `rc.elevationModelTimestamp`; may differ from [lastElevationModelTimestamp] while debouncing. */
    protected var pendingElevationTimestamp = 0L
    /** Frames since [pendingElevationTimestamp] last changed. Drives the debounce in [doRender]. */
    protected var framesSinceTimestampChange = 0
    protected val cachedPosition = Position()
    protected var samplingJob: Job? = null
    /**
     * Most-recent finished sample, kept around until every active window has dispatched it
     * (i.e. each window's [PerWindowState.lastDispatchedSampleId] reaches [currentSampleId]).
     */
    protected var currentSample: PendingDispatch? = null
    protected var currentSampleId: Long = 0L
    /** Frames since the observer last moved; reset to 0 each time [position] changes. */
    protected var framesSinceChange = Int.MAX_VALUE
    /**
     * `true` when the most recent dispatch ran in draft mode (sample count scaled by
     * [draftScale] because the observer was being dragged). Drives the settle-to-full-res
     * trigger in [doRender] — we escalate only from a draft cycle, never from anything else,
     * so settle pumping doesn't fire while the user is just zooming or panning.
     */
    protected var lastWasDraft = false
    /** Set by [dispose]; the next [doRender] schedules GPU resource release and stops rendering. */
    protected var disposeRequested = false
    /**
     * `RenderResourceCache` key for the kernel's RGBA8 render target. Stable per [ViewshedSightline]
     * instance so windows that share a cache reuse the same texture; windows with separate
     * caches each get their own entry. RRC handles GL release on `remove` / eviction.
     */
    protected val outputTextureKey = Any()
    /**
     * Ping-pong pool for the elevation `FloatArray` handed to the kernel. Two buffers because
     * sample N+1 starts off-thread inside the same `doRender` that dispatches kernel N - and
     * kernel N's drawable hasn't run yet at that point, so its FloatArray must not be reused
     * by the new sample. We strictly alternate: each acquire returns the buffer that wasn't
     * handed out last time, so the drawable's previous-frame consumer is always the OTHER
     * buffer. Cleared (the array references nulled) when grid size changes.
     */
    private val elevationBuffers = arrayOfNulls<FloatArray>(2)
    private var elevationBuffersW = 0
    private var elevationBuffersH = 0
    private var nextElevationBuffer = 0

    /**
     * Per-`RenderContext` GPU state. The drawable's R32F sampler / FBO are GL-context-bound
     * and the surface image's texture reference must point at *this* window's RRC entry, so
     * neither can be flat single-instance fields once a sightline is shared across windows
     * with separate GL contexts.
     */
    protected open class PerWindowState {
        var drawable: DrawableViewshedKernel? = null
        var surfaceImage: SurfaceImage? = null
        var gpuInFlight = false
        /** Compared with `<` (not `!=`) so cross-window switches don't ping-pong it. */
        var lastSeenContextVersion = 0L
        /** Highest [currentSampleId] this window has dispatched into its RRC's output texture. */
        var lastDispatchedSampleId = 0L
    }
    protected val states = HashMap<RenderContext, PerWindowState>(2)

    init {
        require(samplesPerSide in 2..MAX_SAMPLES_PER_SIDE) {
            logMessage(ERROR, "ViewshedSightline", "constructor",
                "Samples per side must be in [2, $MAX_SAMPLES_PER_SIDE]")
        }
        // Leave [cachedPosition] at its default (0,0,0) so the first [doRender] reads
        // positionChanged=true and resets [framesSinceChange] to 0. That treats the initial
        // render as a "fresh drag" - the first dispatch runs at draft resolution for instant
        // feedback, and the settle-pump escalates to full res over [settleFrames] frames once
        // the placemark stays put. Disable by setting [draftScale] = 1.0.
    }

    /**
     * Marks the cached visibility raster stale; the next render frame will resample the elevation model
     * and re-run the kernel. Useful when [attributes] / [occludeAttributes] colors are mutated in-place.
     */
    open fun invalidate() { dirty = true }

    /**
     * Drop any sample currently running off-thread plus any sampled-but-not-yet-dispatched
     * result. Called by setters whose change invalidates the in-flight sample's geometry
     * ([area], [samplesPerSide]) - otherwise the sample completes and we waste one dispatch
     * at the old configuration before the new one takes over.
     */
    protected fun cancelInFlightSample() {
        samplingJob?.cancel()
        samplingJob = null
        currentSample = null
        states.values.forEach { it.lastDispatchedSampleId = 0L }
    }

    /**
     * Release all GPU resources owned by this sightline (output texture, kernel sampler texture,
     * framebuffer). The actual `glDelete*` calls run on the next render frame's GL thread - the
     * call itself is safe from any thread. After dispose, [doRender] is a no-op; reuse requires
     * a fresh instance.
     *
     * Cancels any in-flight elevation sample. If no further [doRender] occurs (e.g. the
     * containing layer is removed before another frame), GPU resources will only be reclaimed
     * at engine teardown - same fallback as for orphaned outputs from a `samplesPerSide`
     * change.
     */
    open fun dispose() {
        disposeRequested = true
        cancelInFlightSample()
    }

    /** True when no sample is in flight and no window's kernel is in flight. */
    protected val isIdle: Boolean get() = samplingJob == null && states.values.none { it.gpuInFlight }

    /**
     * Compute the [samplesPerSide] count that yields approximately [metersPerPixel] resolution given
     * the shape's current [area]. Useful when the caller wants the kernel grid to match the native
     * resolution of an underlying DEM — for example `30.0` for NASADEM or `90.0` for SRTM3 — so the
     * kernel doesn't oversample beyond what the source data actually provides. Result is clamped to
     * `>= 2` and computed against the longer axis of the area.
     *
     * @throws IllegalArgumentException if [metersPerPixel] is non-positive
     */
    fun samplesForResolution(metersPerPixel: Double): Int {
        require(metersPerPixel > 0.0) {
            logMessage(ERROR, "ViewshedSightline", "samplesForResolution", "metersPerPixel must be positive")
        }
        val (extentX, extentY) = areaExtents()
        return ((max(extentX, extentY) / metersPerPixel).roundToInt() + 1).coerceAtLeast(2)
    }

    override fun moveTo(globe: Globe, position: Position) { this.position.copy(position) }

    override fun doRender(rc: RenderContext) {
        if (disposeRequested) {
            states[rc]?.let { performDispose(rc, it) }
            return
        }
        val state = states.getOrPut(rc) { PerWindowState() }

        // Per-window context-loss detection: only fires when *this* window's contextVersion
        // actually advanced. The next dispatch reallocates fresh GL objects.
        if (state.lastSeenContextVersion < rc.contextVersion) {
            state.lastSeenContextVersion = rc.contextVersion
            state.surfaceImage = null
            state.drawable = null
            state.lastDispatchedSampleId = 0L
        }

        // Skip new sampling and dispatch when the AOI is completely off the visible terrain -
        // the result wouldn't be displayed regardless. The cached [surfaceImage] still renders
        // (its own drawable does its own per-frame frustum cull), so panning back to the AOI
        // shows the last published result immediately. `Sector.intersects` is non-mutating, so
        // this is essentially a couple of float comparisons per frame.
        val aoiSector = computeAoiSector(rc.globe)
        if (aoiSector.isEmpty || !aoiSector.intersects(rc.terrain.sector)) {
            state.surfaceImage?.render(rc)
            return
        }

        // Track interaction stability: positionChanged signals the observer is actively being
        // dragged. Other "dirty" sources (attribute changes, area swap, settle-trigger below)
        // don't reset the counter - we only want draft mode while the observer is moving.
        val positionChanged = cachedPosition != position
        if (positionChanged) framesSinceChange = 0 else if (framesSinceChange < Int.MAX_VALUE) framesSinceChange++

        // Debounce elevation-tile arrivals. On Kotlin/JS the grid sample runs on the event
        // loop, so an unfiltered tick-per-tile during initial map load would freeze the page
        // dozens of times. Reset the debounce counter on every change; only consider the
        // refresh "due" once the timestamp has been stable for [elevationDebounceFrames].
        // Position changes bypass this entirely and trigger immediately below.
        val rcTimestamp = rc.elevationModelTimestamp
        if (rcTimestamp != pendingElevationTimestamp) {
            pendingElevationTimestamp = rcTimestamp
            framesSinceTimestampChange = 0
        } else if (framesSinceTimestampChange < Int.MAX_VALUE) {
            framesSinceTimestampChange++
        }
        val elevationDirty = pendingElevationTimestamp != lastElevationModelTimestamp
        val elevationSettled = elevationDirty && framesSinceTimestampChange >= elevationDebounceFrames

        // After a draft dispatch settles, re-run at full resolution. Gated on [lastWasDraft]
        // so zoom/pan don't trigger continuous re-dispatch. If settled, mark dirty for the
        // trigger below; otherwise pump rc.requestRedraw to keep frames coming on event-driven
        // JVM/Android (the global WorldWind.requestRedraw is gated by isWaitingForRedraw and
        // is a no-op mid-frame; rc.requestRedraw sets the per-frame flag the engine propagates).
        // The same pump also keeps frames flowing while we're waiting for the elevation
        // debounce to expire.
        if (isIdle && (lastWasDraft || elevationDirty)) {
            val readyToTrigger = (lastWasDraft && framesSinceChange >= settleFrames) || elevationSettled
            if (readyToTrigger) dirty = true else rc.requestRedraw()
        }

        // Each window runs its own kernel into its RRC's output texture. Shared RRC → both
        // windows resolve to the same Texture (identical data written twice, wasteful but
        // correct); separate RRC → each window has its own texture.
        val sample = currentSample
        if (sample != null && state.lastDispatchedSampleId < currentSampleId && !state.gpuInFlight) {
            state.lastDispatchedSampleId = currentSampleId
            dispatch(rc, state, sample)
        }

        // Detect input changes and kick off elevation sampling when nothing else is in flight.
        // Sampling runs off-thread on Dispatchers.Default; on completion it publishes
        // [currentSample] + bumps [currentSampleId] and requests a redraw.
        val triggered = dirty || positionChanged || elevationSettled
        if (triggered && isIdle) {
            if (positionChanged) cachedPosition.copy(position)
            recompute(rc)
            lastElevationModelTimestamp = pendingElevationTimestamp
            dirty = false
        }

        state.surfaceImage?.render(rc)
    }

    /**
     * Pick the sample count for the next dispatch: full [samplesPerSide], or scaled by
     * [draftScale] when the observer is being dragged ([framesSinceChange] less than
     * [settleFrames]). The result drives the kernel grid size — keeping it at
     * [samplesPerSide] across zoom levels means the rasterised texture stays maximally
     * detailed regardless of how the camera moves.
     */
    protected open fun chooseEffectiveSamples(): Int =
        if (draftScale < 1.0 && framesSinceChange < settleFrames) {
            (samplesPerSide * draftScale).toInt().coerceAtLeast(2)
        } else samplesPerSide

    /** East-west and north-south extents of the AOI in meters. */
    private fun areaExtents(): Pair<Double, Double> = when (val a = area) {
        is ViewshedArea.Circle -> Pair(2.0 * a.radius, 2.0 * a.radius)
        is ViewshedArea.Rectangle -> Pair(a.widthMeters, a.heightMeters)
    }

    /**
     * Compute the area-of-interest sector around the observer. For [ViewshedArea.Circle] the
     * sector is a square that bounds the circle; for [ViewshedArea.Rectangle] the sector is sized
     * to the rectangle's actual extent so samples aren't wasted on the diagonal padding. cos(lat)
     * is clamped away from zero so near-pole AOIs degrade gracefully rather than diverging.
     */
    protected open fun computeAoiSector(globe: Globe): Sector {
        val (extentXMeters, extentYMeters) = areaExtents()
        val earthRadius = globe.equatorialRadius
        val cosLat = max(0.001, cos(position.latitude.inRadians))
        val deltaLatRad = extentYMeters * 0.5 / earthRadius
        val deltaLonRad = extentXMeters * 0.5 / earthRadius / cosLat
        return Sector.fromRadians(
            position.latitude.inRadians - deltaLatRad,
            position.longitude.inRadians - deltaLonRad,
            2.0 * deltaLatRad,
            2.0 * deltaLonRad,
        )
    }

    /**
     * Compute (samplesX, samplesY) for the elevation grid. [longSideSamples] is the count along
     * the longer axis; the shorter side scales by aspect so each pixel covers the same area,
     * ensuring the kernel can use an isotropic distance metric.
     */
    protected open fun computeSampleCounts(longSideSamples: Int): Pair<Int, Int> = when (val a = area) {
        is ViewshedArea.Circle -> Pair(longSideSamples, longSideSamples)
        is ViewshedArea.Rectangle -> if (a.widthMeters >= a.heightMeters) {
            val sy = (longSideSamples * a.heightMeters / a.widthMeters).roundToInt().coerceAtLeast(2)
            Pair(longSideSamples, sy)
        } else {
            val sx = (longSideSamples * a.widthMeters / a.heightMeters).roundToInt().coerceAtLeast(2)
            Pair(sx, longSideSamples)
        }
    }

    /**
     * Snapshot inputs from the render context and start the elevation sampling coroutine. The
     * coroutine samples the elevation grid off the render thread on [Dispatchers.Default] and,
     * on completion, parks the result in [currentSample] (with [currentSampleId] bumped) for
     * each window's next render frame to enqueue onto its GL thread via [dispatch].
     */
    protected open fun recompute(rc: RenderContext) {
        // Capture the draft predicate so we can record [lastWasDraft] for this dispatch
        // independently of how [chooseEffectiveSamples] derives the count. The settle pump in
        // [doRender] reads [lastWasDraft] to decide whether to escalate to full resolution.
        val isDraft = draftScale < 1.0 && framesSinceChange < settleFrames
        val effectiveSamples = chooseEffectiveSamples()
        val (w, h) = computeSampleCounts(effectiveSamples)
        val sector = computeAoiSector(rc.globe)
        val (extentXMeters, extentYMeters) = areaExtents()
        val mPerPxX = extentXMeters / (w - 1)
        val mPerPxY = extentYMeters / (h - 1)
        val observerH = when (altitudeMode) {
            AltitudeMode.ABSOLUTE,
            AltitudeMode.ABOVE_SEA_LEVEL -> position.altitude
            AltitudeMode.CLAMP_TO_GROUND ->
                rc.globe.getElevation(position.latitude, position.longitude)
            AltitudeMode.RELATIVE_TO_GROUND ->
                rc.globe.getElevation(position.latitude, position.longitude) + position.altitude
        }
        val earthRadius = rc.globe.equatorialRadius
        val verticalExaggeration = rc.globe.verticalExaggeration
        val areaSnapshot = area
        val visibleColor = Color(activeAttributes().interiorColor)
        val occludedColor = Color(occludeAttributes.interiorColor)
        // Capture the globe so the coroutine can sample elevations off the render thread. Reads
        // from the elevation tile cache aren't formally documented as concurrent-safe, but in
        // practice they're cache-hit lookups against in-memory tile data and tolerate concurrent
        // access. Cache misses re-dispatch retrieval back through MainScope, which serialises
        // coverage mutations on the main thread.
        val globe = rc.globe

        val buffer = acquireElevationBuffer(w, h)
        samplingJob = rc.renderResourceCache.mainScope.launch {
            val elevations = withContext(Dispatchers.Default) {
                // Globe.getElevationGrid applies the geoid offset, putting cells in HAE - the
                // same reference frame as [observerH] so the kernel compares them directly
                // without a 10-30 m bias. MISSING_DATA cells render transparent in the kernel.
                buffer.fill(MISSING_DATA)
                globe.getElevationGrid(sector, w, h, buffer)
                buffer
            }
            currentSample = PendingDispatch(
                sector = sector,
                width = w,
                height = h,
                observerAltitude = observerH,
                earthRadius = earthRadius,
                metersPerPixelX = mPerPxX,
                metersPerPixelY = mPerPxY,
                verticalExaggeration = verticalExaggeration,
                area = areaSnapshot,
                visibleColor = visibleColor,
                occludedColor = occludedColor,
                elevations = elevations,
            )
            // Bump after the write so a window observing the new id is guaranteed a non-null sample.
            currentSampleId++
            lastWasDraft = isDraft
            samplingJob = null
            WorldWind.requestRedraw()
        }
    }

    /**
     * Populate this window's drawable with [pending]'s data, sync this window's [SurfaceImage]
     * to the new sector + texture, and enqueue the kernel drawable into [rc]'s draw queue. The
     * SurfaceImage update happens synchronously (not from the drawable's callback) because
     * [earth.worldwind.draw.DrawableSurfaceTexture.set] copies the sector at enqueue time — a
     * callback-based update would land one frame late and the surface drawable would render
     * with the previous sector while the kernel's texture had the new one.
     */
    private fun dispatch(rc: RenderContext, state: PerWindowState, pending: PendingDispatch) {
        val texture = ensureOutputTexture(rc, pending.width, pending.height)

        val existing = state.surfaceImage
        if (existing == null) {
            state.surfaceImage = SurfaceImage(pending.sector, texture).also {
                it.pickDelegate = pickDelegate ?: this
                it.isPickEnabled = isPickEnabled
                it.displayName = displayName
            }
        } else {
            existing.sector.copy(pending.sector)
            if (existing.texture !== texture) existing.texture = texture
        }

        val d = state.drawable ?: DrawableViewshedKernel().also { state.drawable = it }
        d.program = rc.getShaderProgram(ViewshedKernelShaderProgram.KEY) {
            ViewshedKernelShaderProgram()
        }
        d.width = pending.width
        d.height = pending.height
        d.outputTexture = texture
        d.elevations = pending.elevations
        d.observerCellX = (pending.width - 1) / 2
        d.observerCellY = (pending.height - 1) / 2
        d.observerAltitude = pending.observerAltitude.toFloat()
        d.earthRadius = pending.earthRadius.toFloat()
        d.metersPerPixelX = pending.metersPerPixelX.toFloat()
        d.metersPerPixelY = pending.metersPerPixelY.toFloat()
        d.verticalExaggeration = pending.verticalExaggeration.toFloat()
        d.areaMode = if (pending.area is ViewshedArea.Circle) 0 else 1
        d.areaHalfPxX = (pending.width - 1) * 0.5f
        d.areaHalfPxY = (pending.height - 1) * 0.5f
        d.visibleColor.copy(pending.visibleColor)
        d.occludedColor.copy(pending.occludedColor)
        d.missingValue = MISSING_DATA
        d.callback = { state.gpuInFlight = false }
        state.gpuInFlight = true
        rc.offerSurfaceDrawable(d, 0.0)
    }

    /**
     * Acquire one of two ping-pong elevation buffers. Drops both and reallocates when the
     * grid size changes. Caller fills with [MISSING_DATA] before sampling — `Globe.getElevationGrid`
     * doesn't touch cells without coverage, so the pre-fill prevents stale data from a prior
     * dispatch leaking into untouched regions.
     *
     * Safety: ping-pong relies on the strict ordering enforced by `doRender`'s [isIdle] check —
     * only one sampling coroutine in flight at a time, and the drawable from the previous
     * dispatch has consumed its buffer (uploaded to GPU) by the time the next sample launches.
     */
    private fun acquireElevationBuffer(w: Int, h: Int): FloatArray {
        if (elevationBuffersW != w || elevationBuffersH != h) {
            elevationBuffers[0] = null
            elevationBuffers[1] = null
            elevationBuffersW = w
            elevationBuffersH = h
        }
        val idx = nextElevationBuffer
        nextElevationBuffer = (idx + 1) and 1
        return elevationBuffers[idx] ?: FloatArray(w * h).also { elevationBuffers[idx] = it }
    }

    /**
     * Schedule release of this window's GPU resources and drop its [PerWindowState] entry.
     * [disposeRequested] stays `true`, so other windows' next renders run through the same path
     * for their own state. Once the last entry is removed the shared CPU-side state goes too.
     */
    private fun performDispose(rc: RenderContext, state: PerWindowState) {
        state.drawable?.let {
            it.disposeOnNextDraw = true
            rc.offerSurfaceDrawable(it, 0.0)
        }
        rc.renderResourceCache.remove(outputTextureKey)
        states.remove(rc)
        if (states.isEmpty()) {
            elevationBuffers[0] = null
            elevationBuffers[1] = null
            elevationBuffersW = 0
            elevationBuffersH = 0
            currentSample = null
        }
    }

    /**
     * Lazy-allocate (or rebuild on grid-size change) the RGBA8 render target the kernel writes
     * into, stored in `RenderResourceCache` under [outputTextureKey] so two WorldWindows that
     * share a cache see the same texture. The kernel writes south-at-FBO-row-0
     * (`gl_FragCoord.y == 0` reads `elevations[0]`, which `Globe.getElevationGrid` populates
     * as the southernmost row), and `SurfaceImage`'s natural sector → tex-coord mapping puts
     * `v=0` at the south edge — so no vertical flip on [Texture.coordTransform] is needed.
     *
     * Resizing drops the old entry into RRC's eviction queue ahead of the new `put`, so the
     * cache's used-capacity stays consistent and `Texture.release(dc)` runs on the GL thread
     * once all drawables that referenced the old texture this frame have completed.
     */
    private fun ensureOutputTexture(rc: RenderContext, w: Int, h: Int): Texture {
        val cached = rc.renderResourceCache[outputTextureKey] as? Texture
        if (cached != null && cached.width == w && cached.height == h) return cached
        if (cached != null) rc.renderResourceCache.remove(outputTextureKey)
        val fresh = Texture(w, h, GL_RGBA, GL_UNSIGNED_BYTE, isRT = true, internalFormat = GL_RGBA8)
        rc.renderResourceCache.put(outputTextureKey, fresh, fresh.byteCount)
        return fresh
    }

    /** Resolve the active visible-color attributes, accounting for highlight state. */
    protected open fun activeAttributes(): ShapeAttributes {
        val highlight = highlightAttributes
        return if (isHighlighted && highlight != null) highlight else attributes
    }

    /** Snapshot of inputs awaiting GPU dispatch on the next render frame. */
    protected class PendingDispatch(
        val sector: Sector,
        val width: Int,
        val height: Int,
        val observerAltitude: Double,
        val earthRadius: Double,
        val metersPerPixelX: Double,
        val metersPerPixelY: Double,
        val verticalExaggeration: Double,
        val area: ViewshedArea,
        val visibleColor: Color,
        val occludedColor: Color,
        val elevations: FloatArray,
    )

    companion object {
        /**
         * Hard upper bound on [samplesPerSide]. At 4096² each of the output RGBA8 + R32F sampler
         * textures is 64 MB GPU; the host-side ping-pong elevation buffers add another 128 MB
         * combined. Above this, GL_MAX_TEXTURE_SIZE caps come into play on weaker GPUs and
         * kernel runtime gets impractical (`O(N³)` walks).
         */
        const val MAX_SAMPLES_PER_SIDE = 4096
    }
}
