package earth.worldwind.draw

import earth.worldwind.draw.DrawContext.Companion.SENSOR_FRAME_DEPTH_CELL
import earth.worldwind.draw.DrawContext.Companion.SENSOR_FRAME_DEPTH_SIZE
import earth.worldwind.geom.Matrix4
import earth.worldwind.globe.Globe
import earth.worldwind.render.program.SensorFrameDepthProgram
import earth.worldwind.render.program.SensorFrameProgram
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_CULL_FACE
import earth.worldwind.util.kgl.GL_DEPTH_BUFFER_BIT
import earth.worldwind.util.kgl.GL_DEPTH_TEST
import earth.worldwind.util.kgl.GL_LEQUAL
import earth.worldwind.util.kgl.GL_POLYGON_OFFSET_FILL
import kotlin.jvm.JvmStatic

/**
 * Depth pass of the sensor frames, the shadow map of sensors standing in for the sun: renders
 * the visible terrain from each sensor through its frame transform (see
 * `Sensor.frameTransform`) into that sensor's cell of the depth atlas
 * ([DrawContext.sensorFrameDepthFramebuffer]), so the frames' overlay pass
 * ([DrawableSensorFrames]) can tell ground the sensor sees from ground behind a ridge.
 *
 * Enqueued in the BACKGROUND group, so it runs before anything is drawn on screen and the
 * render target switches once a frame, not between on-screen passes (costly on tiled mobile
 * GPUs). Casters are the map's own terrain tiles: occluders the map has not drawn, such as a
 * ridge off screen, do not occlude.
 *
 * On a 2D globe the second-order term is added back per vertex, as the overlay adds it. A
 * continuous 2D map is drawn once per globe offset; the atlas is the same for all of them, so
 * every copy of the map's terrain is rendered into it, moved by the wrap width into the offset
 * the frame matrices were made at ([offset]).
 */
open class DrawableSensorFrameDepth protected constructor() : Drawable {
    /** World → frame transforms, the first [count] rendered, frame `k` into atlas cell `k`. */
    val frameMatrices = Array(MAX_CELLS) { Matrix4() }
    /** Slant range of each frame, metres: tiles beyond it are not rendered. */
    val ranges = DoubleArray(MAX_CELLS)
    /** Per cell, as [DrawableSensorFrames]' sensorPoints, sensorAxes, bendClips and bendSlack. */
    val sensorPoints = DoubleArray(3 * MAX_CELLS)
    val sensorAxes = FloatArray(9 * MAX_CELLS)
    val bendClips = FloatArray(DrawableSensorFrames.CLIP_FLOATS * MAX_CELLS)
    val bendSlack = DoubleArray(MAX_CELLS)
    /** The globe offset the frame matrices were made at. */
    var offset = Globe.Offset.Center
    /** Cartesian width of one copy of a continuous 2D map, 0 otherwise. */
    var wrapWidth = 0.0
    var count = 0
    var program: SensorFrameDepthProgram? = null
    private val bend = FloatArray(SensorFrameProgram.BEND_FLOATS)
    private var pool: Pool<DrawableSensorFrameDepth>? = null
    private val matrix = Matrix4()

    companion object {
        val KEY = DrawableSensorFrameDepth::class
        /** Counter for measuring, accumulated on the GL thread: draw calls. */
        var statDraws = 0
        private const val CELLS_PER_ROW = SENSOR_FRAME_DEPTH_SIZE / SENSOR_FRAME_DEPTH_CELL
        /** Sensors the atlas holds; frames past this many are drawn without occlusion. */
        const val MAX_CELLS = CELLS_PER_ROW * CELLS_PER_ROW
        // Slope-scaled caster bias, as the sightline and sun-shadow depth passes use
        private const val POLYGON_OFFSET_FACTOR = 1.5f
        private const val POLYGON_OFFSET_UNITS = 4f

        /** Lower-left corner of atlas cell [k] in texture coordinates, x then y. */
        fun cellOrigin(k: Int, out: FloatArray, offset: Int) {
            out[offset] = (k % CELLS_PER_ROW).toFloat() / CELLS_PER_ROW
            out[offset + 1] = (k / CELLS_PER_ROW).toFloat() / CELLS_PER_ROW
        }

        /** Size of one cell in texture coordinates. */
        const val CELL_SCALE = 1f / CELLS_PER_ROW

        @JvmStatic
        fun obtain(pool: Pool<DrawableSensorFrameDepth>): DrawableSensorFrameDepth {
            val instance = pool.acquire() ?: DrawableSensorFrameDepth()
            instance.pool = pool
            return instance
        }
    }

    override fun recycle() {
        bendClips.fill(0f, 0, DrawableSensorFrames.CLIP_FLOATS * count)
        bendSlack.fill(0.0, 0, count)
        count = 0
        offset = Globe.Offset.Center
        wrapWidth = 0.0
        program = null
        pool?.release(this)
        pool = null
    }

    /** Cartesian x of a globe offset's copy of the map, relative to the centre one. */
    private fun shift(offset: Globe.Offset) = when (offset) {
        Globe.Offset.Center -> 0.0
        Globe.Offset.Right -> wrapWidth
        Globe.Offset.Left -> -wrapWidth
    }

    override fun draw(dc: DrawContext) {
        // Depth textures need sized formats; without them the frames are drawn unoccluded
        if (!dc.gl.supportsSizedTextureFormats || count == 0) return
        val program = program ?: return
        if (!program.useProgram(dc)) return
        val framebuffer = dc.sensorFrameDepthFramebuffer
        val previousFramebuffer = dc.currentFramebuffer
        if (!framebuffer.bindFramebuffer(dc)) return
        try {
            dc.gl.viewport(0, 0, SENSOR_FRAME_DEPTH_SIZE, SENSOR_FRAME_DEPTH_SIZE)
            dc.gl.enable(GL_DEPTH_TEST)
            dc.gl.depthFunc(GL_LEQUAL)
            dc.gl.depthMask(true)
            dc.gl.colorMask(false, false, false, false)
            dc.gl.clear(GL_DEPTH_BUFFER_BIT) // cleared to far: nothing in the way
            dc.gl.enable(GL_POLYGON_OFFSET_FILL)
            dc.gl.polygonOffset(POLYGON_OFFSET_FACTOR, POLYGON_OFFSET_UNITS)
            // No back-face culling: terrain winding is unreliable seen from a sensor
            dc.gl.disable(GL_CULL_FACE)
            val cells = SENSOR_FRAME_DEPTH_SIZE / SENSOR_FRAME_DEPTH_CELL
            for (k in 0 until count) {
                dc.gl.viewport(
                    (k % cells) * SENSOR_FRAME_DEPTH_CELL, (k / cells) * SENSOR_FRAME_DEPTH_CELL,
                    SENSOR_FRAME_DEPTH_CELL, SENSOR_FRAME_DEPTH_CELL
                )
                val frame = frameMatrices[k]
                for (idx in 0 until dc.drawableTerrainCount) {
                    val terrain = dc.getDrawableTerrain(idx)
                    val origin = terrain.vertexOrigin
                    // The tile's origin in the copy of the map the frame was made in
                    val ox = origin.x + shift(offset) - shift(terrain.offset)
                    val radius = terrain.boundingSphereRadius
                    if (radius > 0.0 && !DrawableSensorFrames.reaches(frame, ranges[k], ox, origin.y, origin.z, radius + bendSlack[k])) continue
                    if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue
                    // Terrain vertices are tile-origin-relative; compose in double per tile
                    matrix.copy(frame).multiplyByTranslation(ox, origin.y, origin.z)
                    program.loadModelviewProjection(matrix)
                    DrawableSensorFrames.tileBend(
                        sensorAxes, 9 * k, sensorPoints[3 * k], sensorPoints[3 * k + 1], sensorPoints[3 * k + 2],
                        bendClips, DrawableSensorFrames.CLIP_FLOATS * k, ox, origin.y, origin.z, bend, 0,
                    )
                    program.loadFrameBend(bend, 0)
                    terrain.drawTriangles(dc)
                    statDraws++
                }
            }
        } finally {
            dc.gl.enable(GL_CULL_FACE)
            dc.bindFramebuffer(previousFramebuffer)
            dc.gl.viewport(dc.viewport.x, dc.viewport.y, dc.viewport.width, dc.viewport.height)
            dc.gl.colorMask(true, true, true, true)
            dc.gl.disable(GL_POLYGON_OFFSET_FILL)
            dc.gl.polygonOffset(0f, 0f)
        }
    }
}
