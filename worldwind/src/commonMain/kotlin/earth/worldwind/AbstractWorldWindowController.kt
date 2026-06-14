package earth.worldwind

import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Vec3
import earth.worldwind.gesture.FlingAnimator
import earth.worldwind.gesture.FrameScheduler
import earth.worldwind.gesture.PivotAnchorState
import earth.worldwind.gesture.ReleaseVelocitySampler
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Platform-agnostic base for `BasicWorldWindowController`. Holds the camera state, gesture-begin
 * bookkeeping, and pan/fling/zoom-anchor math that is identical across Android, JS, and JVM. Each
 * platform subclass plugs in its own redraw call, gesture-pixel scaling, and frame scheduler.
 */
abstract class AbstractWorldWindowController {
    /**
     * The WorldWind engine driving the host window. Resolved lazily so subclasses can back this
     * with `wwd.engine` even when the host's engine is `lateinit` (JVM creates it on the first GL
     * init callback, well after the controller has been constructed).
     */
    protected abstract val engine: WorldWind

    /** A copy of the viewing parameters at the start of a gesture as a look-at view. */
    protected val beginLookAt = LookAt()
    /** Cartesian projection of [beginLookAt].position; refreshed by [captureBeginLookAtPoint]
     *  at the start of each 2D pan, after [beginLookAt] is set. */
    protected val beginLookAtPoint = Vec3()
    /** The current viewing parameters during a gesture. */
    protected val lookAt = LookAt()
    /** Counts in-progress real and "virtual" (fling) gestures. The first gesture snapshots [beginLookAt]. */
    protected var activeGestures = 0

    /** Shared anchor for pinch / rotate / wheel handlers. Lazy because [engine] may not be
     *  initialized yet at controller-construction time. */
    val pivotAnchor: PivotAnchorState by lazy { PivotAnchorState(engine, lookAt) }

    /** Trailing-window velocity tracker used to seed [fling] on gesture release. */
    val velocitySampler = ReleaseVelocitySampler()

    /**
     * When true, the pinch / rotate / wheel pivot anchor and 3D pan speed are resolved from the
     * rendered depth buffer — i.e. the visible 3D Tiles / shape surface — instead of the terrain
     * mesh or reference ellipsoid. This keeps gestures anchored to photogrammetry, buildings, or
     * other tile geometry when no elevation coverage is loaded and the globe surface sits far below
     * what is drawn. Off by default: each enabled gesture-begin costs one synchronous pick render.
     * Platforms supply the actual depth via [pickSurfaceCartesian].
     */
    var depthAnchorEnabled = false

    /** Eye-to-surface distance frozen at 3D-pan begin from the depth buffer; 0.0 means "fall back
     *  to [lookAt].range". Drives pan pixel→meter scaling off the rendered surface so dragging over
     *  tall 3D Tiles doesn't race ahead at the ellipsoid's larger range. */
    private var panAnchorDistance = 0.0
    private val panAnchorEye = Vec3()

    /** Inertial pan animator. Each platform's [createFlingScheduler] supplies the per-frame clock.
     *  The per-frame action dispatches by projection: 2D uses incremental projected-meters math,
     *  3D uses great-circle radians. Picking the right one matters because Mercator metersPerPixel
     *  diverges from the 3D radians-on-a-sphere formula away from the equator. */
    val fling: FlingAnimator by lazy {
        FlingAnimator(
            scheduler = createFlingScheduler(),
            applyPanDelta = { dx, dy ->
                if (engine.globe.is2D) applyPanDelta2D(dx, dy) else applyPanDelta3D(dx, dy)
            },
            // Hold a "virtual gesture" while the fling runs so a fresh real gesture cleanly
            // cancels us via gestureDidBegin -> fling.cancel.
            onActiveChange = { active -> if (active) activeGestures++ else if (activeGestures > 0) activeGestures-- },
        )
    }

    /** Scratch buffer for [applyPanDelta2D]; reused across frames. */
    private val panDelta2DCart = Vec3()

    /** Schedules a redraw of the host WorldWindow. */
    protected abstract fun requestRedraw()

    /**
     * Multiplier converting gesture-pixel deltas to engine-viewport-pixel deltas. On Android the
     * gesture and viewport coordinate systems already match (1.0); on JS / JVM the gesture is in
     * CSS / Swing pixels but the viewport is in physical pixels, so this is `engine.densityFactor`.
     */
    protected abstract val gestureToViewportPixels: Double

    /** Constructs the platform's vsync-aligned (or close-enough) frame scheduler for [fling]. */
    protected abstract fun createFlingScheduler(): FrameScheduler

    /** Pushes [lookAt] into the camera and requests a redraw. */
    protected open fun applyChanges() {
        engine.cameraFromLookAt(lookAt)
        requestRedraw()
    }

    /** Snapshots the camera at gesture start and aborts any in-progress fling or goTo. */
    protected open fun gestureDidBegin() {
        fling.cancel()
        // Without this, an in-progress goTo (e.g. WorldMapLayer minimap tap) keeps writing
        // the camera between our CHANGED events and the two writers fight, producing
        // forward/backward jitter for the duration of the animation.
        engine.goToAnimator.cancel()
        if (activeGestures++ == 0) {
            engine.cameraAsLookAt(beginLookAt)
            lookAt.copy(beginLookAt)
            // Fresh interaction: drop any surface distance frozen by a previous pan so a pinch (or a
            // pan that misses tile geometry) doesn't inherit a stale value. A subsequent pan-begin
            // re-freezes it via capturePanAnchorDistance.
            panAnchorDistance = 0.0
        }
    }

    /**
     * Platform hook: synchronously pick at the given GL-viewport pixel and return the depth-derived
     * Cartesian point of the topmost rendered object (e.g. a 3D Tiles surface), or null when nothing
     * depth-pickable was hit — empty sky, or plain terrain, both of which fall back to the
     * terrain / ellipsoid anchor. Default returns null so platforms that don't wire picking keep the
     * legacy ellipsoid-anchor behavior.
     */
    protected open fun pickSurfaceCartesian(viewportX: Double, viewportY: Double): Vec3? = null

    /** Captures the shared pinch / rotate / wheel pivot anchor at a screen pixel, preferring the
     *  rendered depth-buffer surface when [depthAnchorEnabled]; otherwise the terrain / ellipsoid. */
    protected fun capturePivotAnchor(viewportX: Double, viewportY: Double) {
        pivotAnchor.capture(
            viewportX, viewportY,
            if (depthAnchorEnabled) pickSurfaceCartesian(viewportX, viewportY) else null
        )
    }

    /** Freezes the eye-to-surface distance used for 3D pan pixel→meter scaling. Reverts to
     *  [lookAt].range unless [depthAnchorEnabled] and a depth-pickable surface was hit. */
    protected fun capturePanAnchorDistance(viewportX: Double, viewportY: Double) {
        panAnchorDistance = if (depthAnchorEnabled) {
            pickSurfaceCartesian(viewportX, viewportY)?.let { surface ->
                val eye = engine.globe.getAbsolutePosition(engine.camera.position, engine.camera.altitudeMode)
                engine.globe.geographicToCartesian(eye.latitude, eye.longitude, eye.altitude, panAnchorEye)
                surface.distanceTo(panAnchorEye)
            } ?: 0.0
        } else 0.0
    }

    /** Distance for pan pixel→meter scaling: the frozen rendered-surface distance when available,
     *  else the camera range. */
    private fun panSurfaceDistance() = if (panAnchorDistance > 0.0) panAnchorDistance else lookAt.range

    /**
     * Refreshes [beginLookAtPoint] from the current [beginLookAt]. 2D pan handlers must call this
     * at gesture begin so the per-CHANGED translation operates from the correct origin. Kept
     * separate from [gestureDidBegin] so subclasses overriding it (e.g. to source [beginLookAt]
     * from a navigator event instead of the camera) don't silently break 2D pan.
     */
    protected fun captureBeginLookAtPoint() {
        engine.globe.geographicToCartesian(
            beginLookAt.position.latitude,
            beginLookAt.position.longitude,
            beginLookAt.position.altitude,
            beginLookAtPoint
        )
    }

    protected open fun gestureDidEnd() {
        if (activeGestures > 0) activeGestures--
    }

    /**
     * Applies a screen-pixel pan delta to [lookAt].position using the current heading and range.
     * `deltaPxX/Y` are in gesture-pixel units; the formula scales them up to physical viewport
     * pixels via [gestureToViewportPixels] before converting to meters.
     */
    protected open fun applyPanDelta3D(deltaPxX: Double, deltaPxY: Double) {
        var lat = lookAt.position.latitude
        var lon = lookAt.position.longitude

        val mpp = engine.pixelSizeAtDistance(max(1.0, panSurfaceDistance())) * gestureToViewportPixels
        // A fling can keep ticking while the viewport height is 0 (layout transition / teardown),
        // making mpp infinite; the radians math below would then produce NaN and crash Angle.
        if (!mpp.isFinite()) return
        val forwardMeters = deltaPxY * mpp
        val sideMeters = -deltaPxX * mpp
        val globeRadius = engine.globe.getRadiusAt(lat, lon)
        val forwardRadians = forwardMeters / globeRadius
        val sideRadians = sideMeters / globeRadius

        val heading = lookAt.heading
        val sinHeading = sin(heading.inRadians)
        val cosHeading = cos(heading.inRadians)
        lat = lat.plusRadians(forwardRadians * cosHeading - sideRadians * sinHeading)
        lon = lon.plusRadians(forwardRadians * sinHeading + sideRadians * cosHeading)

        // If the camera has panned over either pole, compensate by flipping the longitude and
        // heading 180° to move the camera to the appropriate spot on the other side.
        if (lat.inDegrees < -90.0 || lat.inDegrees > 90.0) {
            lookAt.position.latitude = lat.normalizeLatitude()
            lookAt.position.longitude = lon.plusDegrees(180.0).normalizeLongitude()
            lookAt.heading = heading.plusDegrees(180.0).normalize360()
        } else if (lon.inDegrees < -180.0 || lon.inDegrees > 180.0) {
            lookAt.position.latitude = lat
            lookAt.position.longitude = lon.normalizeLongitude()
        } else {
            lookAt.position.latitude = lat
            lookAt.position.longitude = lon
        }
        applyChanges()
    }

    /**
     * Applies a screen-pixel pan delta to [lookAt].position in 2D-projection Cartesian space. Reads
     * the current lookAt back to Cartesian each call so the delta accumulates smoothly across many
     * fling frames; using `beginLookAtPoint` here would only work for the first event of a sequence.
     * `metersPerPixel` is true for the projection at the current range, so this stays accurate at
     * any zoom level — unlike [applyPanDelta3D]'s great-circle approximation, which diverges from
     * Mercator metersPerPixel away from the equator.
     */
    protected open fun applyPanDelta2D(deltaPxX: Double, deltaPxY: Double) {
        val mpp = engine.pixelSizeAtDistance(max(1.0, panSurfaceDistance())) * gestureToViewportPixels
        // See applyPanDelta3D: a zero-height viewport makes mpp infinite, poisoning the result with NaN.
        if (!mpp.isFinite()) return
        val forwardMeters = deltaPxY * mpp
        val sideMeters = -deltaPxX * mpp

        val heading = lookAt.heading
        val sinHeading = sin(heading.inRadians)
        val cosHeading = cos(heading.inRadians)
        engine.globe.geographicToCartesian(
            lookAt.position.latitude, lookAt.position.longitude, lookAt.position.altitude, panDelta2DCart
        )
        val x = panDelta2DCart.x + forwardMeters * sinHeading + sideMeters * cosHeading
        val y = panDelta2DCart.y + forwardMeters * cosHeading - sideMeters * sinHeading
        engine.globe.cartesianToGeographic(x, y, panDelta2DCart.z, lookAt.position)
        applyChanges()
    }

    /**
     * Releases any platform resources held by the controller (timers, frame callbacks). Subclasses
     * should override and call `super.release()` to add their own cleanup (e.g. VC repeat timers).
     */
    open fun release() {
        fling.cancel()
    }
}
