package earth.worldwind.sensor

import earth.worldwind.draw.DrawableSensorFrameDepth
import earth.worldwind.draw.DrawableSensorFrames
import earth.worldwind.render.program.DirectionalDepthProgram
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.program.SensorFrameProgram
import earth.worldwind.geom.Sector
import kotlin.math.PI
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
 *    detail. It is skipped on 2D globes, and assumes no vertical exaggeration.
 */
open class SensorFrameOverlay : AbstractRenderable("Sensor frames") {
    /** One sensor's frame and its colours; change them in place at any time. */
    class Frame(
        val sensor: Sensor,
        val outlineColor: Color,
        val fillColor: Color,
        /** Inside the frame where the sensor cannot see the ground; transparent for none. */
        val occludedColor: Color = Color(0f, 0f, 0f, 0f),
    ) {
        var isEnabled = true
        // Terrain height range under the frame, refreshed as the sensor moves (for the scissor)
        internal var lowAltitude = 0.0
        internal var highAltitude = 0.0
        internal var limitsLatitude = Double.NaN
        internal var limitsLongitude = 0.0
        internal var limitsRange = 0.0
    }

    val frames = mutableListOf<Frame>()
    /** Width of the frames' outline in screen pixels. */
    var outlineWidth = 2f
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
        if (rc.isPickMode || rc.globe.is2D || frames.isEmpty()) return
        val pool = rc.getDrawablePool(DrawableSensorFrames.KEY)
        val program = rc.getShaderProgram { SensorFrameProgram() }
        // Depth pass first, in BACKGROUND: one atlas cell per occluded frame
        val depth = if (isOcclusionEnabled) DrawableSensorFrameDepth.obtain(rc.getDrawablePool(DrawableSensorFrameDepth.KEY)).also {
            it.program = rc.getShaderProgram { DirectionalDepthProgram() }
        } else null
        val d = DrawableSensorFrames.obtain(pool).also {
            it.program = program
            it.outlineWidth = outlineWidth
        }
        for (frame in frames) {
            if (!frame.isEnabled) continue
            if (d.count == DrawableSensorFrames.MAX_TOTAL) break
            val k = d.count++
            val sensor = frame.sensor
            sensor.frameTransform(rc.globe, d.frameMatrices[k])
            premultiplied(frame.outlineColor, d.outlineColors, 4 * k)
            premultiplied(frame.fillColor, d.fillColors, 4 * k)
            premultiplied(frame.occludedColor, d.occludedColors, 4 * k)
            d.frameParams[4 * k] = tan(sensor.horizontalFov.inRadians / 2).toFloat()
            d.frameParams[4 * k + 1] = tan(sensor.verticalFov.inRadians / 2).toFloat()
            d.frameParams[4 * k + 2] = min(sensor.maxRange, Float.MAX_VALUE.toDouble()).toFloat()
            d.frameParams[4 * k + 3] = sensor.frameNear().toFloat()
            // Where on screen the frame's ground can be: each draw is scissored to it
            updateHeightLimits(rc, frame)
            if (!sensor.groundScreenRect(
                    rc.globe, rc.modelviewProjection, rc.viewport, frame.lowAltitude, frame.highAltitude, d.frameRects, 4 * k
                )) d.frameRects[4 * k + 2] = -1
            if (depth != null && depth.count < DrawableSensorFrameDepth.MAX_CELLS) {
                val cell = depth.count++
                depth.frameMatrices[cell].copy(d.frameMatrices[k])
                depth.ranges[cell] = sensor.maxRange
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
     * read again only when the sensor has moved a tenth of its range, since it changes slowly.
     */
    private fun updateHeightLimits(rc: RenderContext, frame: Frame) {
        val sensor = frame.sensor
        val lat = sensor.position.latitude.inDegrees
        val lon = sensor.position.longitude.inDegrees
        val range = sensor.maxRange
        val moved = if (frame.limitsLatitude.isNaN()) Double.POSITIVE_INFINITY else {
            val dy = (lat - frame.limitsLatitude) * 111_320.0
            val dx = (lon - frame.limitsLongitude) * 111_320.0 * cos(lat * PI / 180.0)
            sqrt(dx * dx + dy * dy)
        }
        if (moved < 0.1 * range && range == frame.limitsRange) return
        val dLat = range / 111_320.0
        val dLon = dLat / max(cos(lat * PI / 180.0), 0.01)
        val sector = Sector.fromDegrees(lat - dLat, lon - dLon, 2 * dLat, 2 * dLon)
        limits[0] = Float.MAX_VALUE
        limits[1] = -Float.MAX_VALUE
        rc.globe.getElevationLimits(sector, limits)
        if (limits[0] <= limits[1]) {
            // A margin for finer data arriving with higher peaks or deeper valleys
            frame.lowAltitude = limits[0] - 100.0
            frame.highAltitude = limits[1] + 100.0
        } else {
            frame.lowAltitude = -500.0
            frame.highAltitude = 9000.0
        }
        frame.limitsLatitude = lat
        frame.limitsLongitude = lon
        frame.limitsRange = range
    }

    private val limits = FloatArray(2)

    private fun premultiplied(color: Color, out: FloatArray, offset: Int) {
        out[offset] = color.red * color.alpha
        out[offset + 1] = color.green * color.alpha
        out[offset + 2] = color.blue * color.alpha
        out[offset + 3] = color.alpha
    }
}
