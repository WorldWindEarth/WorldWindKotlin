package earth.worldwind.draw

import earth.worldwind.geom.Matrix4
import earth.worldwind.render.program.SensorFrameProgram
import earth.worldwind.render.program.SensorFrameProgram.Companion.MAX_FRAMES
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_SCISSOR_TEST
import earth.worldwind.util.kgl.GL_TEXTURE0
import kotlin.jvm.JvmStatic
import kotlin.math.sqrt

/**
 * Draws every sensor frame on the terrain in a single pass over the visible terrain tiles with
 * [SensorFrameProgram].
 *
 * Each tile carries only the frames that reach it: its bounding sphere is tested against every
 * frame's volume, a tile no frame reaches is skipped, and the shader evaluates just the frames
 * the tile was given. With sensors kilometres apart a tile has one frame or none, so the pass
 * costs about one redraw of the terrain under the frames, whatever the number of sensors. A
 * tile reached by more than [MAX_FRAMES] frames (sensors close together) is drawn once more for
 * each further [MAX_FRAMES]. Each draw is scissored to the screen rectangle its frames' ground can
 * cover ([frameRects]), so the shader runs only where the frames can be, not over the whole tile.
 *
 * Frames with a cell in the depth atlas ([DrawableSensorFrameDepth], rendered earlier in the
 * frame) are occluded: their fill is drawn only where the sensor sees the ground, and their
 * occluded colour where a ridge hides it. The outline is drawn either way.
 */
open class DrawableSensorFrames protected constructor() : Drawable {
    /** World → frame transforms (see `Sensor.frameTransform`), the first [count] used. */
    val frameMatrices = Array(MAX_TOTAL) { Matrix4() }
    /** Premultiplied RGBA per frame. */
    val outlineColors = FloatArray(4 * MAX_TOTAL)
    val fillColors = FloatArray(4 * MAX_TOTAL)
    /** Premultiplied RGBA per frame for ground inside the frame the sensor cannot see. */
    val occludedColors = FloatArray(4 * MAX_TOTAL)
    /** tan(horizontal FOV / 2), tan(vertical FOV / 2), slant range and depth near plane in metres; per frame. */
    val frameParams = FloatArray(4 * MAX_TOTAL)
    /** Depth atlas cell per frame: origin u, v, size, and 1 when occluded (0 to draw it unoccluded). */
    val depthCells = FloatArray(4 * MAX_TOTAL)
    var count = 0
    var outlineWidth = 2f
    /** Screen rectangle per frame (x, y, width, height, window coordinates), or width -1 for none. */
    val frameRects = IntArray(4 * MAX_TOTAL)

    var program: SensorFrameProgram? = null
    private var pool: Pool<DrawableSensorFrames>? = null
    private val mvp = Matrix4()
    private val tileMatrix = Matrix4()
    private val reached = IntArray(MAX_TOTAL)
    // One upload's worth: up to MAX_FRAMES frames of a tile
    private val chunkMatrices = FloatArray(16 * MAX_FRAMES)
    private val chunkOutline = FloatArray(4 * MAX_FRAMES)
    private val chunkFill = FloatArray(4 * MAX_FRAMES)
    private val chunkOccluded = FloatArray(4 * MAX_FRAMES)
    private val chunkParams = FloatArray(4 * MAX_FRAMES)
    private val chunkCells = FloatArray(4 * MAX_FRAMES)

    companion object {
        val KEY = DrawableSensorFrames::class
        /** Frames one overlay draws; any more are left out. */
        const val MAX_TOTAL = 1024

        /** Counters for measuring, accumulated on the GL thread: terrain tiles drawn and draw calls. */
        var statTiles = 0
        var statDraws = 0

        @JvmStatic
        fun obtain(pool: Pool<DrawableSensorFrames>): DrawableSensorFrames {
            val instance = pool.acquire() ?: DrawableSensorFrames()
            instance.pool = pool
            return instance
        }

        /**
         * Whether a sphere reaches into a frame's volume: the four sides `w ± x ≥ 0`, `w ± y ≥ 0`,
         * ahead of the sensor `w ≥ 0`, and nearer than the range `w ≤ range`, each a plane from the
         * frame matrix's rows.
         */
        internal fun reaches(frame: Matrix4, range: Double, x: Double, y: Double, z: Double, radius: Double): Boolean {
            val m = frame.m
            fun plane(a: Double, b: Double, c: Double, d: Double): Boolean {
                val length = sqrt(a * a + b * b + c * c)
                return a * x + b * y + c * z + d >= -radius * length
            }
            return plane(m[12] - m[0], m[13] - m[1], m[14] - m[2], m[15] - m[3]) &&
                plane(m[12] + m[0], m[13] + m[1], m[14] + m[2], m[15] + m[3]) &&
                plane(m[12] - m[4], m[13] - m[5], m[14] - m[6], m[15] - m[7]) &&
                plane(m[12] + m[4], m[13] + m[5], m[14] + m[6], m[15] + m[7]) &&
                plane(m[12], m[13], m[14], m[15]) &&
                plane(-m[12], -m[13], -m[14], range - m[15])
        }
    }

    override fun recycle() {
        count = 0
        program = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        val program = program ?: return
        if (count == 0 || !program.useProgram(dc)) return
        // Without depth textures there is no atlas: draw every frame unoccluded
        var occlusion = dc.gl.supportsSizedTextureFormats && (0 until count).any { depthCells[4 * it + 3] > 0f }
        if (occlusion) {
            dc.activeTextureUnit(GL_TEXTURE0 + SensorFrameProgram.DEPTH_UNIT)
            occlusion = dc.sensorFrameDepthTexture.bindTexture(dc)
            dc.activeTextureUnit(GL_TEXTURE0)
        }
        if (!occlusion) for (k in 0 until count) depthCells[4 * k + 3] = 0f
        program.loadOutlineWidth(outlineWidth)
        try {
            drawTiles(dc, program)
        } finally {
            dc.gl.disable(GL_SCISSOR_TEST)
            // Park a plain texture on the depth unit: other programs sample it as a sampler2D,
            // and the next depth pass renders into the atlas
            if (occlusion) {
                dc.activeTextureUnit(GL_TEXTURE0 + SensorFrameProgram.DEPTH_UNIT)
                dc.defaultTexture.bindTexture(dc)
                dc.activeTextureUnit(GL_TEXTURE0)
            }
        }
    }

    private fun drawTiles(dc: DrawContext, program: SensorFrameProgram) {
        for (idx in 0 until dc.drawableTerrainCount) {
            val terrain = dc.getDrawableTerrain(idx)
            val origin = terrain.vertexOrigin
            val radius = terrain.boundingSphereRadius
            // The frames this tile reaches
            var n = 0
            for (k in 0 until count) {
                if (radius <= 0.0 || reaches(frameMatrices[k], frameParams[4 * k + 2].toDouble(), origin.x, origin.y, origin.z, radius)) {
                    reached[n++] = k
                }
            }
            if (n == 0) continue
            if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue
            statTiles++
            // Terrain vertices are tile-origin-relative; compose in double per tile
            mvp.copy(dc.modelviewProjection).multiplyByTranslation(origin.x, origin.y, origin.z)
            program.loadModelviewProjection(mvp)
            var start = 0
            while (start < n) {
                val size = minOf(MAX_FRAMES, n - start)
                for (j in 0 until size) {
                    val k = reached[start + j]
                    tileMatrix.copy(frameMatrices[k]).multiplyByTranslation(origin.x, origin.y, origin.z)
                    tileMatrix.transposeToArray(chunkMatrices, 16 * j)
                    outlineColors.copyInto(chunkOutline, 4 * j, 4 * k, 4 * k + 4)
                    fillColors.copyInto(chunkFill, 4 * j, 4 * k, 4 * k + 4)
                    occludedColors.copyInto(chunkOccluded, 4 * j, 4 * k, 4 * k + 4)
                    frameParams.copyInto(chunkParams, 4 * j, 4 * k, 4 * k + 4)
                    depthCells.copyInto(chunkCells, 4 * j, 4 * k, 4 * k + 4)
                }
                if (!scissorTo(dc, start, size)) { start += size; continue }
                program.loadFrames(size, chunkOutline, chunkFill, chunkOccluded, chunkParams, chunkCells)
                program.loadFrameMatrices(chunkMatrices)
                terrain.drawTriangles(dc)
                statDraws++
                start += size
            }
        }
    }

    /**
     * Scissors to the union of the screen rectangles of frames `reached[start until start + size]`.
     * False when none of them covers anything on screen, so the draw can be skipped.
     */
    private fun scissorTo(dc: DrawContext, start: Int, size: Int): Boolean {
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
        for (j in 0 until size) {
            val k = reached[start + j]
            val w = frameRects[4 * k + 2]
            if (w < 0) { // unbounded: the whole viewport
                dc.gl.disable(GL_SCISSOR_TEST)
                return true
            }
            if (w == 0 || frameRects[4 * k + 3] == 0) continue
            x0 = minOf(x0, frameRects[4 * k]); y0 = minOf(y0, frameRects[4 * k + 1])
            x1 = maxOf(x1, frameRects[4 * k] + w); y1 = maxOf(y1, frameRects[4 * k + 1] + frameRects[4 * k + 3])
        }
        if (x0 >= x1 || y0 >= y1) return false
        dc.gl.enable(GL_SCISSOR_TEST)
        dc.gl.scissor(x0, y0, x1 - x0, y1 - y0)
        return true
    }
}
