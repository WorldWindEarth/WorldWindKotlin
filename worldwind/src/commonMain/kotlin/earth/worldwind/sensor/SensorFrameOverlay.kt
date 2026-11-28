package earth.worldwind.sensor

import earth.worldwind.draw.DrawableSensorFrameDepth
import earth.worldwind.draw.DrawableSensorFrames
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import earth.worldwind.render.program.SensorFrameDepthProgram
import earth.worldwind.render.program.SensorFrameHatchProgram
import earth.worldwind.render.program.SensorFrameMediaProgram
import earth.worldwind.render.program.SensorFrameMediaProgramOES
import earth.worldwind.render.program.SensorFrameProgram
import earth.worldwind.geom.Sector
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Draws where sensors' frames fall on the ground, entirely on the GPU: the terrain is drawn once
 * more, and each fragment is carried into every sensor's frame the way a shadow map carries it
 * into the sun's view, then tinted if it lands inside. The frame's edge comes out as an outline
 * [outlineWidth] screen pixels wide that follows the terrain exactly, hills and valleys alike,
 * with an optional fill inside.
 *
 * Per frame the CPU builds one matrix per sensor and nothing else: no rays, no polygons, no
 * surface textures to redraw, so the frames can follow their sensors every frame. All frames are
 * drawn in one pass over the terrain, each tile with only the frames that reach it, and a tile
 * no frame reaches is skipped: sensors kilometres apart cost about one redraw of the terrain
 * under their frames. Up to [DrawableSensorFrames.MAX_TOTAL] frames are drawn.
 *
 * With [isOcclusionEnabled] (the default) it is a shadow map in full: before the scene is drawn,
 * the terrain is rendered from each sensor into a small depth map, and inside a frame only ground
 * the sensor actually sees gets the [Frame.fillColor]; ground behind a ridge gets
 * [Frame.occludedColor] (transparent by default). The outline is drawn either way. The first
 * [DrawableSensorFrameDepth.MAX_CELLS] enabled frames are occluded, any more are drawn without.
 * That costs one depth pass of the visible terrain per sensor, on the GPU; it needs depth
 * textures (GLES 3, WebGL 2, desktop GL 3), and without them the frames are drawn unoccluded.
 *
 * What it does not do:
 *  - Occlusion by what the map has not drawn: the depth maps are rendered from the map's own
 *    terrain tiles, so a ridge off screen does not hide the ground behind it. Shapes and 3D
 *    tiles do not occlude either.
 *  - Coordinates: it only colours pixels. Cast the frame's corner rays with a
 *    [FootprintSolver] (or [SensorFootprintEngine]) for positions.
 *  - Terrain the map has not drawn: it tints the map's own terrain, at the map's level of
 *    detail. On a 3D globe it assumes no vertical exaggeration.
 *
 * On a 2D globe the frames are drawn on the flat map the same way, any projection: each sensor's
 * frame is the map's projection made linear at the sensor, with a second-order term added back
 * per vertex for the Earth's curvature and the projection's change of scale, so the frame lands
 * on the map where it does on the 3D globe (a thousandth of its width at worst, see
 * [SensorLocalFrame]). A continuous map's wrapped copies each get the frames too. A sensor
 * outside the projection's limits is not drawn.
 */
open class SensorFrameOverlay : AbstractRenderable("Sensor frames") {
    /** One sensor's frame and its colours; change them in place at any time. */
    class Frame(
        val sensor: Sensor,
        var outlineColor: Color,
        var fillColor: Color,
        /** Inside the frame where the sensor cannot see the ground; transparent for none. */
        var occludedColor: Color = Color(0f, 0f, 0f, 0f),
    ) {
        var isEnabled = true
        /**
         * The sensor's picture, shown on the ground it sees inside the frame, with [mediaOpacity];
         * null for none. Its coordinate transform maps the frame's 0..1 to the texture.
         */
        var media: Texture? = null
        var mediaOpacity = 1f
        /** Whether the picture is cut to the ground the sensor sees (the default), or laid on all the ground in the frame. */
        var isMediaClipped = true
        /** Width of the picture's fade towards the frame's sides, a fraction of the frame's half width; 0 for a hard edge. */
        var mediaSoftEdge = 0f
        /**
         * The hidden ground to hatch, from the sensor's viewshed, in [hatchColor] with lines
         * [hatchSpacing] pixels apart and [hatchWidth] wide; null for none. Independent of the
         * depth pass: the hatching is the viewshed's, the same on every map and at every zoom.
         */
        var hatch: ViewshedMask? = null
        var hatchColor = Color(0f, 0f, 0f, 0f)
        var hatchSpacing = 9f
        /** Width of the lines in pixels; as wide as [hatchSpacing], they fill the hidden ground. */
        var hatchWidth = 1f
        /**
         * Hatch the ground the depth pass finds hidden, the seen tint's test inverted, instead of
         * the viewshed's [hatch]: it follows the map's drawn relief and needs no viewshed, but
         * only an occluded frame ([isOccluded]) has it.
         */
        var isHatchFromDepth = false
        /** How the hatching's lines are coloured. */
        var hatchContrast = HatchContrast.FIXED
        /** Whether the last frame drew this frame: enabled and within [DrawableSensorFrames.MAX_TOTAL]. */
        var isDrawn = false
            internal set
        /**
         * Whether the last frame drew this frame occluded, with a cell of the depth atlas: false for
         * frames past [DrawableSensorFrameDepth.MAX_CELLS], and for all when occlusion is off.
         */
        var isOccluded = false
            internal set
        // Terrain height range under the frame, refreshed as the sensor moves (for the scissor)
        internal var lowAltitude = 0.0
        internal var highAltitude = 0.0
        internal var limitsLatitude = Double.NaN
        internal var limitsLongitude = 0.0
        internal var limitsRange = 0.0
        internal var limitsTimestamp = 0L
    }

    /** How the hidden ground's lines are coloured. */
    enum class HatchContrast {
        /** In [Frame.hatchColor]. */
        FIXED,
        /**
         * Darker over light ground and lighter over dark ground, chosen per pixel from the
         * luminance of what the map drew under it, at [Frame.hatchColor]'s opacity. Where the
         * framebuffer cannot be read back (a multisampled one) it is drawn [TWO_TONE].
         */
        AUTO,
        /** A light line with a thin dark edge on either side, which reads on any ground. */
        TWO_TONE,
    }

    val frames = mutableListOf<Frame>()
    /** Width of the frames' outline in screen pixels. */
    var outlineWidth = 2f
    /** Side in screen pixels of the outline's dashes, a checkerboard so a line of any direction is dashed alike; 0 for solid. */
    var outlineDash = 0f
    /** Whether the frames are occluded by the terrain between the sensor and the ground. */
    var isOcclusionEnabled = true

    init {
        isPickEnabled = false
    }

    /**
     * Adds [sensor]'s frame, outlined in [outlineColor], with [fillColor] where the sensor sees the
     * ground and [occludedColor] where it does not (each transparent for none).
     */
    fun add(
        sensor: Sensor, outlineColor: Color,
        fillColor: Color = Color(0f, 0f, 0f, 0f), occludedColor: Color = Color(0f, 0f, 0f, 0f),
    ) = Frame(sensor, outlineColor, fillColor, occludedColor).also { frames += it }

    fun remove(sensor: Sensor) {
        frames.removeAll { it.sensor === sensor }
    }

    override fun doRender(rc: RenderContext) {
        if (rc.isPickMode || frames.isEmpty()) return
        val globe = rc.globe
        val pool = rc.getDrawablePool(DrawableSensorFrames.KEY)
        val program = rc.getShaderProgram { SensorFrameProgram() }
        // Depth pass first, in BACKGROUND: one atlas cell per occluded frame
        val depth = if (isOcclusionEnabled) DrawableSensorFrameDepth.obtain(rc.getDrawablePool(DrawableSensorFrameDepth.KEY)).also {
            it.program = rc.getShaderProgram { SensorFrameDepthProgram() }
            it.offset = globe.offset
            it.wrapWidth = if (globe.is2D && globe.isContinuous) 2.0 * PI * globe.equatorialRadius else 0.0
        } else null
        val d = DrawableSensorFrames.obtain(pool).also {
            it.program = program
            it.outlineWidth = outlineWidth
            it.outlineDash = outlineDash
            it.offset = globe.offset
        }
        assignDepthCells(frames, isOcclusionEnabled)
        for (frame in frames) {
            if (!frame.isDrawn) continue
            val sensor = frame.sensor
            // Outside a 2D projection's limits there is no frame to draw
            if (!local.set(globe, sensor.position)) {
                frame.isDrawn = false
                frame.isOccluded = false
                continue
            }
            val k = d.count++
            sensor.frameTransform(local, d.frameMatrices[k], sensor.frameNear(), sensor.frameFar(), d.bendClips, DrawableSensorFrames.CLIP_FLOATS * k)
            d.sensorPoints[3 * k] = local.origin.x; d.sensorPoints[3 * k + 1] = local.origin.y; d.sensorPoints[3 * k + 2] = local.origin.z
            for (i in 0..8) d.sensorAxes[9 * k + i] = local.toLocal[i].toFloat()
            d.bendSlack[k] = bendSlack(local, sensor.maxRange)
            premultiplied(frame.outlineColor, d.outlineColors, 4 * k)
            premultiplied(frame.fillColor, d.fillColors, 4 * k)
            premultiplied(frame.occludedColor, d.occludedColors, 4 * k)
            d.media[k] = frame.media
            d.mediaOpacity[k] = frame.mediaOpacity
            d.mediaClipped[k] = frame.isMediaClipped
            d.mediaSoftEdge[k] = frame.mediaSoftEdge
            if (frame.media != null && d.mediaProgram == null) {
                d.mediaProgram = rc.getShaderProgram { SensorFrameMediaProgram() }
                d.mediaProgramOes = rc.getShaderProgram { SensorFrameMediaProgramOES() }
            }
            val hatch = frame.hatch.takeUnless { frame.isHatchFromDepth }
            val hatched = frame.hatchColor.alpha > 0f && (hatch != null || frame.isHatchFromDepth && frame.isOccluded)
            d.hatchFromDepth[k] = hatched && frame.isHatchFromDepth
            d.hatchContrast[k] = when (frame.hatchContrast) {
                HatchContrast.FIXED -> SensorFrameHatchProgram.CONTRAST_FIXED
                HatchContrast.AUTO -> SensorFrameHatchProgram.CONTRAST_AUTO
                HatchContrast.TWO_TONE -> SensorFrameHatchProgram.CONTRAST_TWO_TONE
            }
            if (hatched) {
                d.hatchMasks[k] = hatch
                d.hatchTextures[k] = if (hatch == null) null else rc.renderResourceCache[hatch] as? Texture
                    ?: hatch.newTexture().also { rc.renderResourceCache.put(hatch, it, hatch.byteCount) }
                premultiplied(frame.hatchColor, d.hatchColors, 4 * k)
                d.hatchParams[2 * k] = frame.hatchSpacing
                d.hatchParams[2 * k + 1] = frame.hatchWidth
                if (d.hatchProgram == null) d.hatchProgram = rc.getShaderProgram { SensorFrameHatchProgram() }
            } else {
                d.hatchMasks[k] = null
                d.hatchTextures[k] = null
            }
            d.frameParams[4 * k] = tan(sensor.horizontalFov.inRadians / 2).toFloat()
            d.frameParams[4 * k + 1] = tan(sensor.verticalFov.inRadians / 2).toFloat()
            d.frameParams[4 * k + 2] = min(sensor.maxRange, Float.MAX_VALUE.toDouble()).toFloat()
            d.frameParams[4 * k + 3] = sensor.frameNear().toFloat()
            // Where on screen the frame's ground can be: each draw is scissored to it
            updateHeightLimits(rc, frame)
            if (!sensor.groundScreenRect(
                    globe, local, rc.modelviewProjection, rc.viewport, frame.lowAltitude, frame.highAltitude, d.frameRects, 4 * k
                )) d.frameRects[4 * k + 2] = -1
            if (depth != null && frame.isOccluded) {
                val cell = depth.count++
                depth.frameMatrices[cell].copy(d.frameMatrices[k])
                depth.ranges[cell] = sensor.maxRange
                d.sensorPoints.copyInto(depth.sensorPoints, 3 * cell, 3 * k, 3 * k + 3)
                d.sensorAxes.copyInto(depth.sensorAxes, 9 * cell, 9 * k, 9 * k + 9)
                val clips = DrawableSensorFrames.CLIP_FLOATS
                d.bendClips.copyInto(depth.bendClips, clips * cell, clips * k, clips * k + clips)
                depth.bendSlack[cell] = d.bendSlack[k]
                DrawableSensorFrameDepth.cellOrigin(cell, d.depthCells, 4 * k)
                d.depthCells[4 * k + 2] = DrawableSensorFrameDepth.CELL_SCALE
                d.depthCells[4 * k + 3] = 1f
            } else d.depthCells[4 * k + 3] = 0f
        }
        if (d.count > 0) rc.offerSurfaceDrawable(d, zOrder) else d.recycle()
        if (depth != null && depth.count > 0) rc.offerBackgroundDrawable(depth) else depth?.recycle()
    }

    /**
     * The terrain's height range within the sensor's range, from the elevation model's limits:
     * read again when the sensor has moved a tenth of its range, or when the elevation data has
     * changed since, as tiles under the frame arrive. The limits of data still loading are those
     * of what is loaded, so a hill that arrives later would otherwise stay outside the band, and
     * the frame on it outside the scissor, until the sensor moved far enough.
     */
    private fun updateHeightLimits(rc: RenderContext, frame: Frame) {
        val sensor = frame.sensor
        val lat = sensor.position.latitude.inDegrees
        val lon = sensor.position.longitude.inDegrees
        val range = sensor.maxRange
        val timestamp = rc.globe.elevationModel.timestamp
        val moved = if (frame.limitsLatitude.isNaN()) Double.POSITIVE_INFINITY else {
            val dy = (lat - frame.limitsLatitude) * 111_320.0
            val dx = (lon - frame.limitsLongitude) * 111_320.0 * cos(lat * PI / 180.0)
            sqrt(dx * dx + dy * dy)
        }
        if (moved < 0.1 * range && range == frame.limitsRange && timestamp == frame.limitsTimestamp) return
        val dLat = range / 111_320.0
        val dLon = dLat / max(cos(lat * PI / 180.0), 0.01)
        val sector = Sector.fromDegrees(lat - dLat, lon - dLon, 2 * dLat, 2 * dLon)
        limits[0] = Float.MAX_VALUE
        limits[1] = -Float.MAX_VALUE
        rc.globe.getElevationLimits(sector, limits)
        if (limits[0] <= limits[1]) {
            // A margin for finer data arriving with higher peaks or deeper valleys, and for the
            // limits of coarse levels, which smooth the peaks away
            frame.lowAltitude = limits[0] - HEIGHT_MARGIN
            frame.highAltitude = limits[1] + HEIGHT_MARGIN
        } else {
            frame.lowAltitude = -500.0
            frame.highAltitude = 9000.0
        }
        frame.limitsLatitude = lat
        frame.limitsLongitude = lon
        frame.limitsRange = range
        frame.limitsTimestamp = timestamp
    }

    private val limits = FloatArray(2)
    private val local = SensorLocalFrame()

    /**
     * How far, in Cartesian units, the second-order term can move ground within [range] of the
     * sensor: a bound on it at the range, by the longest of the frame's metre steps. 0 on a 3D globe.
     */
    private fun bendSlack(local: SensorLocalFrame, range: Double): Double {
        if (!local.hasQuad) return 0.0
        val w = local.toWorld
        val q = local.quad
        var step = 0.0
        var metres = 0.0
        for (j in 0..2) {
            step = max(step, sqrt(w[j] * w[j] + w[3 + j] * w[3 + j] + w[6 + j] * w[6 + j]))
            // |e²·a + e·n·b + n²·c| ≤ (|a| + |b| + |c|)·r² for e, n within r
            metres += abs(q[3 * j]) + abs(q[3 * j + 1]) + abs(q[3 * j + 2])
        }
        return metres * range * range * step
    }

    internal companion object {
        /** Metres added above and below the elevation limits read for the scissor's height band. */
        const val HEIGHT_MARGIN = 300.0

        /**
         * Marks which [frames] are drawn, the first [DrawableSensorFrames.MAX_TOTAL] enabled ones,
         * and which of those are occluded, the first [DrawableSensorFrameDepth.MAX_CELLS] of them
         * when [occlusion] is on: the atlas cell of an occluded frame is its rank among them.
         */
        fun assignDepthCells(frames: List<Frame>, occlusion: Boolean) {
            var drawn = 0
            for (frame in frames) {
                frame.isDrawn = frame.isEnabled && drawn < DrawableSensorFrames.MAX_TOTAL
                frame.isOccluded = frame.isDrawn && occlusion && drawn < DrawableSensorFrameDepth.MAX_CELLS
                if (frame.isDrawn) drawn++
            }
        }
    }

    private fun premultiplied(color: Color, out: FloatArray, offset: Int) {
        out[offset] = color.red * color.alpha
        out[offset + 1] = color.green * color.alpha
        out[offset + 2] = color.blue * color.alpha
        out[offset + 3] = color.alpha
    }
}
