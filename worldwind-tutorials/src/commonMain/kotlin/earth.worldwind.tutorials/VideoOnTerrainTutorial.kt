package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Location
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.shape.ProjectedMediaSurface

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
     * Per-source PTS skew between the KLV timeline and the video stream, in milliseconds.
     * Negative when telemetry leads the video (the typical case for transcoded MISB clips
     * where the muxer dropped the PTS-aligned audio track and the stream's KLV ESID got
     * reset relative to the video ESID); positive when telemetry lags. Applied at lookup
     * time as `timeline.sampleAt(now - telemetryDelayMs)` so the stored JSON tMs values
     * stay raw (matching the source's PTS deltas) and any user-supplied media can pass its
     * own calibration without re-extracting the JSON.
     *
     * For the bundled drone_motion clip the calibrated value is [BUNDLED_DRONE_MOTION_DELAY_MS].
     */
    private val telemetryDelayMs: Long = 0L,
) : AbstractTutorial() {

    /** Reusable interpolated sample, written in place by [KlvTimeline.sampleAt]. */
    private val scratchSample = KlvSample()

    /**
     * Reusable corner locations. Naming matches the source-image corner convention KLV uses
     * (tag set 26-33 / 82-89): `imgTl` is the *image* top-left whose ground location moves
     * with the gimbal, etc. These four are mapped onto [ProjectedMediaSurface]'s
     * (bottomLeft, bottomRight, topRight, topLeft) inputs in [tick] so the texture's image
     * orientation matches the gimbal-relative footprint.
     */
    private val imgTl = Location()
    private val imgTr = Location()
    private val imgBr = Location()
    private val imgBl = Location()

    private val surface: ProjectedMediaSurface
    private val layer = VideoLayer()

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
        val s = timeline.sampleAt(now - telemetryDelayMs, scratchSample) ?: return
        lastTickTMs = now
        applyCorners(s)
        surface.setLocations(
            bottomLeft = imgBl, bottomRight = imgBr, topRight = imgTr, topLeft = imgTl,
        )
    }

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

    companion object {
        /**
         * PTS skew baked into the bundled drone_motion clip: the KLV timeline leads the
         * video by ~2.29 s on this source. Pass this as `telemetryDelayMs` when wiring the
         * bundled tutorial; user-supplied media will need its own calibration (or 0 if the
         * source preserves PTS alignment between video and KLV).
         */
        const val BUNDLED_DRONE_MOTION_DELAY_MS = -2290L
    }
}
