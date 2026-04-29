package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.CameraPose
import earth.worldwind.geom.Location
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import earth.worldwind.shape.ProjectedMediaSurface
import earth.worldwind.shape.ShapeAttributes
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Cross-platform video-on-terrain tutorial. Drapes a video [Texture] onto the actual terrain
 * via the engine's [ProjectedMediaSurface], driven by the four ground corners that MISB ST 0601
 * KLV carries per sample (tags 26-33 / 82-89).
 *
 * Per-frame, [tick] re-samples [KlvTimeline] for the player's current playback time and pushes
 * the four interpolated ground corners through [ProjectedMediaSurface.setLocations]. The shape
 * draws on the engine's surface-projection pipeline (no 3D mesh, no terrain intersection), and
 * its fragment shader applies a 2D homography from the four corners, so the interior is
 * perspective-correct even for the trapezoidal footprints a tilted gimbal produces.
 *
 * The hot path is allocation-free after warm-up: a single [KlvSample] scratch buffer and four
 * [Location] scratches are mutated each tick.
 */
open class VideoOnTerrainTutorial(
    protected val engine: WorldWind,
    private val timeline: KlvTimeline,
    private val texture: Texture,
    private val currentTimeMs: () -> Long,
    /**
     * Initial value for [useCameraProjection]. The runtime property is exposed via the
     * [ACTION_TOGGLE_3D] action (auto-rendered as a button on JS/JVM hosts; mapped to the
     * toolbar `is3d` checkbox on Android).
     */
    initialUseCameraProjection: Boolean = false,
) : AbstractTutorial() {

    /**
     * When `true`, drives [ProjectedMediaSurface] in 3D camera-frustum mode: a world-ECEF
     * -> image-UV matrix derived from the KLV sensor pose is fed to the fragment shader,
     * so projection is correct over relief. When `false` (default), uses the cheaper
     * 2D-homography path which is exact on flat ground.
     *
     * **Use 3D only when the KLV stream is self-consistent** - i.e. the four reported
     * corners are the flat-ground projection of the reported pose (tags 5-7 or 90/91),
     * FOV (tags 16-17), and altitude (tag 15 MSL or 75 HAE) at the sensor position
     * (tags 13-14). Calibrated platforms (DJI raw export, etc.) satisfy this and 3D
     * matches 2D exactly. Platforms that fold a terrain DB into corner generation
     * (BlackHornet) or emit nominal-not-calibrated FOV produce visibly inconsistent 3D
     * output even though the math is correct - the data is over-specified.
     *
     * Mutable at runtime: flipping swaps paths on the next tick. Falls back to 2D for
     * samples missing sensor lat/lon.
     */
    var useCameraProjection: Boolean = initialUseCameraProjection
        set(value) {
            field = value
            // Property initialisers go to the backing field directly, so `surface` is
            // already initialised whenever this setter runs.
            if (!value) surface.imageProjection = null
        }

    /** Reusable interpolated sample, written in place by [KlvTimeline.sampleAt]. */
    private val scratchSample = KlvSample()

    // Image-frame corner scratches. Naming matches KLV tag-set 26-33 / 82-89; mapped to
    // [ProjectedMediaSurface]'s (bl, br, tr, tl) inputs so the texture orientation lines
    // up with the gimbal-relative footprint.
    private val imgTl = Location()
    private val imgTr = Location()
    private val imgBr = Location()
    private val imgBl = Location()

    private val surface: ProjectedMediaSurface
    private val layer = VideoLayer()

    /** Reused per tick. Only fed to the surface when [useCameraProjection] is true. */
    private val cameraPose = CameraPose()

    /**
     * Last `tMs` we propagated to the surface. Skips redundant pose updates when the
     * player hasn't advanced (paused, or two render frames inside the same telemetry sample).
     */
    private var lastTickTMs: Long = Long.MIN_VALUE

    init {
        // Prime the surface with the first telemetry sample so its mesh extent is valid
        // before the first tick. Falls back to a degenerate sample when the timeline is
        // empty (the bundled JSON always has samples).
        val first = timeline.samples.firstOrNull() ?: KlvSample(
            tlLat = 59.93, tlLon = 10.69, trLat = 59.93, trLon = 10.70,
            brLat = 59.92, brLon = 10.70, blLat = 59.92, blLon = 10.69,
        )
        applyCorners(first)
        surface = ProjectedMediaSurface(
            bottomLeft = imgBl, bottomRight = imgBr, topRight = imgTr, topLeft = imgTl,
            attributes = ShapeAttributes().apply {
                isDrawOutline = true
                outlineWidth = 2.0f
                outlineColor = Color(1.0f, 0.85f, 0.0f, 1.0f)
            },
        ).also { it.texture = texture }
        layer.addRenderable(surface)
    }

    override fun start() {
        super.start()
        engine.layers.addLayer(layer)
        // Look at the centroid of the first frame's footprint, far enough up that the whole
        // projected texture is in view and a few seconds of motion stay on-screen without
        // re-centering.
        timeline.samples.firstOrNull()?.let { s ->
            val centroidLat = (s.tlLat + s.trLat + s.brLat + s.blLat) / 4.0
            val centroidLon = (s.tlLon + s.trLon + s.brLon + s.blLon) / 4.0
            engine.camera.set(
                centroidLat.degrees, centroidLon.degrees, 1500.0,
                AltitudeMode.ABSOLUTE, heading = Angle.ZERO, tilt = Angle.ZERO, roll = Angle.ZERO,
            )
        }
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(layer)
    }

    /**
     * Re-samples the timeline at the player's current time and publishes the new four-corner
     * footprint to the surface. Called from [VideoLayer.doRender] every render frame;
     * idempotent on identical times.
     */
    private fun tick() {
        val now = currentTimeMs()
        if (now == lastTickTMs) return
        val s = timeline.sampleAt(now, scratchSample) ?: return
        lastTickTMs = now
        applyCorners(s)
        surface.setLocations(
            bottomLeft = imgBl, bottomRight = imgBr, topRight = imgTr, topLeft = imgTl,
        )
        if (useCameraProjection) updateImageProjection(s) else surface.imageProjection = null
    }

    override val actions = arrayListOf(ACTION_TOGGLE_3D)

    override fun runAction(actionName: String) {
        if (actionName == ACTION_TOGGLE_3D) useCameraProjection = !useCameraProjection
    }

    /**
     * Build a world-ECEF -> image-UV matrix from the sample's pose+FOV and publish it to
     * the surface. Composes platform body angles (5-7 / 90-91) with sensor relative
     * angles (18-20) for the world-frame optical axis. Altitude resolution: HAE (Tag 75)
     * direct, MSL (Tag 15) + geoid undulation, else geometric estimate. Falls back to
     * homography (clears `imageProjection`) when sensor lat/lon are missing. Missing
     * angle tags default to 0 (level platform / boresighted sensor).
     */
    private fun updateImageProjection(s: KlvSample) {
        if (s.sLat.isNaN() || s.sLon.isNaN()) {
            surface.imageProjection = null
            return
        }
        val sAlt = when {
            !s.sHae.isNaN() -> s.sHae
            !s.sAlt.isNaN() -> s.sAlt +
                engine.globe.geoid.getOffset(s.sLat.degrees, s.sLon.degrees).toDouble()
            else -> estimateAltitudeFrom(s)
        }

        val vFovDeg = if (!s.vFov.isNaN()) s.vFov else 60.0
        val hFovDeg = if (!s.hFov.isNaN()) s.hFov else 75.0
        val aspect = tan(hFovDeg * 0.5 * PI_DIV_180) / tan(vFovDeg * 0.5 * PI_DIV_180)

        cameraPose.setFromPlatformAndSensorPose(
            cameraLat = s.sLat.degrees, cameraLon = s.sLon.degrees, cameraAltMeters = sAlt,
            pYaw = orZero(s.pHdg), pPitch = orZero(s.pPit), pRoll = orZero(s.pRol),
            sAz = orZero(s.rAz), sEl = orZero(s.rEl), sRoll = orZero(s.rRol),
            vFov = vFovDeg.degrees, aspect = aspect,
        )
        surface.imageProjection = cameraPose.matrix
    }

    private fun orZero(deg: Double): Angle = (if (deg.isNaN()) 0.0 else deg).degrees

    /** Mutate [imgTl]/[imgTr]/[imgBr]/[imgBl] from the sample's image-frame ground corners. */
    private fun applyCorners(s: KlvSample) {
        imgTl.setDegrees(s.tlLat, s.tlLon)
        imgTr.setDegrees(s.trLat, s.trLon)
        imgBr.setDegrees(s.brLat, s.brLon)
        imgBl.setDegrees(s.blLat, s.blLon)
    }

    /**
     * Layer that ticks the pose update just before its renderables draw. Inheriting from
     * [RenderableLayer] gets us a per-frame hook for free: no separate timer, and the timing
     * aligns naturally with whatever cadence the engine renders at.
     */
    private inner class VideoLayer : RenderableLayer("Video on terrain") {
        override fun doRender(rc: RenderContext) {
            tick()
            super.doRender(rc)
        }
    }

    /**
     * Estimate sensor altitude AGL when KLV Tag 15 (Sensor True Altitude) is missing,
     * which lighter UAS payloads frequently omit. Geometry: with a level-enough ground
     * and a known relative-elevation angle, the sensor altitude over the frame center is
     * the horizontal sensor-to-frame-center distance times tan(|relEl|).
     */
    private fun estimateAltitudeFrom(s: KlvSample): Double {
        val rEl = s.rEl
        if (rEl.isNaN()) return DEFAULT_FALLBACK_ALTITUDE_M
        val tanAbs = tan(abs(rEl) * PI_DIV_180)
        if (tanAbs < 1e-3) return DEFAULT_FALLBACK_ALTITUDE_M  // looking near-horizontal

        val cLat = (s.tlLat + s.trLat + s.brLat + s.blLat) * 0.25
        val cLon = (s.tlLon + s.trLon + s.brLon + s.blLon) * 0.25
        val avgLatDeg = (cLat + s.sLat) * 0.5
        // Small-angle haversine-equivalent at this latitude. Sub-percent error for typical
        // sub-km drone footprints; the fallback path is itself approximate so refining
        // further isn't worthwhile.
        val mPerLat = 111_132.92 - 559.82 * cos(2 * avgLatDeg * PI_DIV_180)
        val mPerLon = mPerLat * cos(avgLatDeg * PI_DIV_180)
        val dx = (cLon - s.sLon) * mPerLon
        val dy = (cLat - s.sLat) * mPerLat
        val horizontal = sqrt(dx * dx + dy * dy)
        return horizontal * tanAbs
    }

    companion object {
        /**
         * Action label for the 3D-projection toggle. JS / JVM tutorial hosts render this
         * automatically via [actions]; the Android fragment owns its own Switch overlay
         * and reads the same string for the label.
         */
        const val ACTION_TOGGLE_3D = "Toggle 3D projection"

        private const val PI_DIV_180 = 0.017453292519943295
        private const val DEFAULT_FALLBACK_ALTITUDE_M = 100.0
    }
}
