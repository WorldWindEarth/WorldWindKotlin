package earth.worldwind.draw

import earth.worldwind.geom.Matrix4
import earth.worldwind.render.program.SceneDepthProgram
import earth.worldwind.render.program.SensorFrameProgram
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_COLOR_BUFFER_BIT
import earth.worldwind.util.kgl.GL_DEPTH_BUFFER_BIT
import earth.worldwind.util.kgl.GL_DEPTH_TEST
import earth.worldwind.util.kgl.GL_LEQUAL
import earth.worldwind.util.kgl.GL_SCISSOR_TEST
import earth.worldwind.util.kgl.GL_TEXTURE0
import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Writes each sensor's seen ground to a screen-space mask, one channel per sensor and one
 * target per four ([DrawContext.ensureSensorMaskFramebuffer]), for [DrawableSensorEdges] to
 * draw the boundary of. Drawn in the BACKGROUND group after [DrawableSensorFrameDepth], whose
 * atlas it reads: the terrain's depth from the eye goes into the targets' shared depth first,
 * so each screen texel takes the ground the eye sees there, and the frames' tiles are then
 * drawn with [SensorFrameProgram] in mask mode, which writes the frame's inside times the
 * average of nine depth tests, a filtered visibility. At [scale] below one the mask is
 * written at a fraction of the viewport, read back bilinearly by the edge pass.
 */
open class DrawableSensorMask protected constructor() : Drawable {
    val frameMatrices = Array(MAX_FRAMES) { Matrix4() }
    /** As [DrawableSensorFrames.frameParams], [DrawableSensorFrames.depthCells] and [DrawableSensorFrames.frameRects]. */
    val frameParams = FloatArray(4 * MAX_FRAMES)
    val depthCells = FloatArray(4 * MAX_FRAMES)
    val frameRects = IntArray(4 * MAX_FRAMES)
    var count = 0
    var scale = 1f
    var program: SensorFrameProgram? = null
    var depthProgram: SceneDepthProgram? = null
    private var pool: Pool<DrawableSensorMask>? = null
    private val matrix = Matrix4()
    private val chunkMatrices = FloatArray(16 * SensorFrameProgram.MAX_FRAMES)
    private val chunkZero = FloatArray(4 * SensorFrameProgram.MAX_FRAMES)
    private val chunkParams = FloatArray(4 * SensorFrameProgram.MAX_FRAMES)
    private val chunkCells = FloatArray(4 * SensorFrameProgram.MAX_FRAMES)

    companion object {
        val KEY = DrawableSensorMask::class
        /** Frames with a mask: four per target. */
        const val MAX_FRAMES = SensorFrameProgram.MAX_FRAMES * DrawContext.SENSOR_MASK_TARGETS
        var statDraws = 0

        @JvmStatic
        fun obtain(pool: Pool<DrawableSensorMask>): DrawableSensorMask {
            val instance = pool.acquire() ?: DrawableSensorMask()
            instance.pool = pool
            return instance
        }
    }

    override fun recycle() {
        count = 0
        program = null
        depthProgram = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        val program = program ?: return
        val depthProgram = depthProgram ?: return
        if (count == 0 || !dc.gl.supportsSizedTextureFormats) return
        val width = max(1, (dc.viewport.width * scale).roundToInt())
        val height = max(1, (dc.viewport.height * scale).roundToInt())
        val targets = (count + SensorFrameProgram.MAX_FRAMES - 1) / SensorFrameProgram.MAX_FRAMES
        for (j in 0 until targets) if (dc.ensureSensorMaskFramebuffer(j, width, height) == null) return
        dc.sensorMaskPassWidth = width
        dc.sensorMaskPassHeight = height
        dc.sensorMaskTargets = targets
        val previousFramebuffer = dc.currentFramebuffer
        var atlasBound = false
        try {
            // The terrain's depth from the eye, once, into the depth every target shares
            if (!dc.ensureSensorMaskFramebuffer(0, width, height)!!.bindFramebuffer(dc)) return
            dc.gl.viewport(0, 0, width, height)
            dc.gl.disable(GL_SCISSOR_TEST)
            dc.gl.clearColor(0f, 0f, 0f, 0f)
            dc.gl.clear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            dc.gl.enable(GL_DEPTH_TEST)
            dc.gl.depthFunc(GL_LEQUAL)
            dc.gl.depthMask(true)
            if (!depthProgram.useProgram(dc)) return
            dc.gl.colorMask(false, false, false, false)
            for (idx in 0 until dc.drawableTerrainCount) {
                val terrain = dc.getDrawableTerrain(idx)
                val origin = terrain.vertexOrigin
                if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue
                matrix.copy(dc.modelviewProjection).multiplyByTranslation(origin.x, origin.y, origin.z)
                depthProgram.loadModelviewProjection(matrix)
                terrain.drawTriangles(dc)
            }
            dc.gl.colorMask(true, true, true, true)
            dc.gl.depthMask(false)
            // The masks: the frames' tiles, the same depth, one target per four frames
            if (!program.useProgram(dc)) return
            if ((0 until count).any { depthCells[4 * it + 3] > 0f }) {
                dc.activeTextureUnit(GL_TEXTURE0 + SensorFrameProgram.DEPTH_UNIT)
                atlasBound = dc.sensorFrameDepthTexture.bindTexture(dc)
                dc.activeTextureUnit(GL_TEXTURE0)
            }
            if (!atlasBound) for (k in 0 until count) depthCells[4 * k + 3] = 0f
            program.loadMaskMode(true)
            for (j in 0 until targets) {
                if (j > 0) {
                    if (!dc.ensureSensorMaskFramebuffer(j, width, height)!!.bindFramebuffer(dc)) return
                    dc.gl.disable(GL_SCISSOR_TEST)
                    dc.gl.clear(GL_COLOR_BUFFER_BIT)
                }
                drawTarget(dc, program, j)
            }
        } finally {
            program.loadMaskMode(false)
            if (atlasBound) {
                dc.activeTextureUnit(GL_TEXTURE0 + SensorFrameProgram.DEPTH_UNIT)
                dc.defaultTexture.bindTexture(dc)
                dc.activeTextureUnit(GL_TEXTURE0)
            }
            dc.gl.disable(GL_SCISSOR_TEST)
            dc.gl.depthMask(true)
            dc.gl.colorMask(true, true, true, true)
            dc.bindFramebuffer(previousFramebuffer)
            dc.gl.viewport(dc.viewport.x, dc.viewport.y, dc.viewport.width, dc.viewport.height)
        }
    }

    /** Target [j] takes frames `4j until 4j+4`, each in its own channel, whatever tiles each reaches. */
    private fun drawTarget(dc: DrawContext, program: SensorFrameProgram, j: Int) {
        val first = j * SensorFrameProgram.MAX_FRAMES
        val slots = minOf(SensorFrameProgram.MAX_FRAMES, count - first)
        // The union of the frames' screen rectangles, scaled to the pass
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
        var unbounded = false
        for (s in 0 until slots) {
            val k = first + s
            val w = frameRects[4 * k + 2]
            if (w < 0) unbounded = true
            if (w <= 0 || frameRects[4 * k + 3] == 0) continue
            x0 = minOf(x0, frameRects[4 * k]); y0 = minOf(y0, frameRects[4 * k + 1])
            x1 = maxOf(x1, frameRects[4 * k] + w); y1 = maxOf(y1, frameRects[4 * k + 1] + frameRects[4 * k + 3])
        }
        if (unbounded || !DrawableSensorFrames.isScissorEnabled) {
            dc.gl.disable(GL_SCISSOR_TEST)
        } else {
            if (x0 >= x1 || y0 >= y1) return
            dc.gl.enable(GL_SCISSOR_TEST)
            dc.gl.scissor(
                (x0 * scale).toInt(), (y0 * scale).toInt(),
                ((x1 - x0) * scale).roundToInt() + 1, ((y1 - y0) * scale).roundToInt() + 1,
            )
        }
        for (s in 0 until slots) {
            val k = first + s
            frameParams.copyInto(chunkParams, 4 * s, 4 * k, 4 * k + 4)
            depthCells.copyInto(chunkCells, 4 * s, 4 * k, 4 * k + 4)
        }
        for (idx in 0 until dc.drawableTerrainCount) {
            val terrain = dc.getDrawableTerrain(idx)
            val origin = terrain.vertexOrigin
            val radius = terrain.boundingSphereRadius
            var reached = !DrawableSensorFrames.isTileCullingEnabled || radius <= 0.0
            for (s in 0 until slots) {
                if (reached) break
                val k = first + s
                reached = DrawableSensorFrames.reaches(frameMatrices[k], frameParams[4 * k + 2].toDouble(), origin.x, origin.y, origin.z, radius)
            }
            if (!reached) continue
            if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue
            matrix.copy(dc.modelviewProjection).multiplyByTranslation(origin.x, origin.y, origin.z)
            program.loadModelviewProjection(matrix)
            for (s in 0 until slots) {
                matrix.copy(frameMatrices[first + s]).multiplyByTranslation(origin.x, origin.y, origin.z)
                matrix.transposeToArray(chunkMatrices, 16 * s)
            }
            program.loadFrames(slots, chunkZero, chunkZero, chunkZero, chunkParams, chunkCells)
            program.loadFrameMatrices(chunkMatrices)
            terrain.drawTriangles(dc)
            statDraws++
        }
    }
}
