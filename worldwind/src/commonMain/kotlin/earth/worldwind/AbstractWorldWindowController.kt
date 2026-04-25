package earth.worldwind

import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Vec3
import earth.worldwind.gesture.FlingAnimator
import earth.worldwind.gesture.FrameScheduler
import earth.worldwind.gesture.ReleaseVelocitySampler
import earth.worldwind.gesture.ZoomAnchorState
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
    /** Cartesian projection of [beginLookAt].position; populated by [gestureDidBegin] for 2D pan. */
    protected val beginLookAtPoint = Vec3()
    /** The current viewing parameters during a gesture. */
    protected val lookAt = LookAt()
    /** Counts in-progress real and "virtual" (fling) gestures. The first gesture snapshots [beginLookAt]. */
    protected var activeGestures = 0

    /** Anchor state used by `handlePinch`/`handleWheelEvent`-style code in subclasses.
     *  Lazy because [engine] may not be initialized yet at controller-construction time. */
    val zoomAnchor: ZoomAnchorState by lazy { ZoomAnchorState(engine, lookAt) }

    /** Trailing-window velocity tracker used to seed [fling] on gesture release. */
    val velocitySampler = ReleaseVelocitySampler()

    /** Inertial pan animator. Each platform's [createFlingScheduler] supplies the per-frame clock. */
    val fling: FlingAnimator by lazy {
        FlingAnimator(
            scheduler = createFlingScheduler(),
            applyPanDelta = { dx, dy -> applyPanDelta3D(dx, dy) },
            // Hold a "virtual gesture" while the fling runs so a fresh real gesture cleanly
            // cancels us via gestureDidBegin -> fling.cancel.
            onActiveChange = { active -> if (active) activeGestures++ else if (activeGestures > 0) activeGestures-- },
        )
    }

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

    /** Snapshots the camera at the start of a gesture and aborts any in-progress fling. */
    protected open fun gestureDidBegin() {
        fling.cancel() // a new gesture interrupts any in-progress fling
        if (activeGestures++ == 0) {
            engine.cameraAsLookAt(beginLookAt)
            lookAt.copy(beginLookAt)
            // Pre-compute the begin point in Cartesian so 2D pan handlers can translate from it
            // without re-running the geographic→Cartesian transform on every CHANGED.
            engine.globe.geographicToCartesian(
                beginLookAt.position.latitude,
                beginLookAt.position.longitude,
                beginLookAt.position.altitude,
                beginLookAtPoint
            )
        }
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

        val mpp = engine.pixelSizeAtDistance(max(1.0, lookAt.range)) * gestureToViewportPixels
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
     * Releases any platform resources held by the controller (timers, frame callbacks). Subclasses
     * should override and call `super.release()` to add their own cleanup (e.g. VC repeat timers).
     */
    open fun release() {
        fling.cancel()
    }
}
