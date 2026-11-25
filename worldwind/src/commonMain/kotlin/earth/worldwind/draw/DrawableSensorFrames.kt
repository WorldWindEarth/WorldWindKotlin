package earth.worldwind.draw

import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Texture
import earth.worldwind.render.program.SensorFrameHatchProgram
import earth.worldwind.render.program.SensorFrameMediaProgram
import earth.worldwind.sensor.ViewshedMask
import earth.worldwind.render.program.SensorFrameProgram
import earth.worldwind.render.program.SensorFrameProgram.Companion.MAX_FRAMES
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_SCISSOR_TEST
import earth.worldwind.util.kgl.GL_TEXTURE0
import earth.worldwind.util.kgl.GL_TEXTURE_EXTERNAL_OES
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
 *
 * A frame with [media] is drawn once more on its own, after the others, with
 * [SensorFrameMediaProgram]: the picture where the sensor sees the ground, its tint and outline
 * over it. A frame with a hidden-ground mask ([hatchMasks]) is drawn once more again with
 * [SensorFrameHatchProgram]: screen-space hatching where the viewshed says the ground is hidden.
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
    /** A frame's media texture and its opacity, where it has one. */
    val media = arrayOfNulls<Texture>(MAX_TOTAL)
    val mediaOpacity = FloatArray(MAX_TOTAL)
    /** A frame's hidden-ground mask and its texture, where it hatches; its premultiplied colour; spacing and width in pixels. */
    val hatchMasks = arrayOfNulls<ViewshedMask>(MAX_TOTAL)
    val hatchTextures = arrayOfNulls<Texture>(MAX_TOTAL)
    val hatchColors = FloatArray(4 * MAX_TOTAL)
    val hatchParams = FloatArray(2 * MAX_TOTAL)
    /** The sensor's Cartesian point, and its east, north and up axes, for the hatch pass. */
    val sensorPoints = DoubleArray(3 * MAX_TOTAL)
    val sensorAxes = FloatArray(9 * MAX_TOTAL)
    var count = 0
    var outlineWidth = 2f
    /** Screen rectangle per frame (x, y, width, height, window coordinates), or width -1 for none. */
    val frameRects = IntArray(4 * MAX_TOTAL)

    var program: SensorFrameProgram? = null
    /** For the frames with media: a plain texture, and an external one. */
    var mediaProgram: SensorFrameMediaProgram? = null
    var mediaProgramOes: SensorFrameMediaProgram? = null
    var hatchProgram: SensorFrameHatchProgram? = null
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

        /** Diagnostics: whether tiles no frame reaches are skipped, and whether draws are scissored. */
        var isTileCullingEnabled = true
        var isScissorEnabled = true

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
        media.fill(null, 0, count)
        hatchMasks.fill(null, 0, count)
        hatchTextures.fill(null, 0, count)
        count = 0
        program = null
        mediaProgram = null
        mediaProgramOes = null
        hatchProgram = null
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
        var mediaBound = false
        try {
            drawTiles(dc, program)
            mediaBound = drawMedia(dc)
            mediaBound = drawHatch(dc) || mediaBound
        } finally {
            dc.gl.disable(GL_SCISSOR_TEST)
            if (mediaBound) {
                dc.activeTextureUnit(GL_TEXTURE0 + SensorFrameProgram.MEDIA_UNIT)
                dc.defaultTexture.bindTexture(dc)
                dc.activeTextureUnit(GL_TEXTURE0)
            }
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
                if (!isTileCullingEnabled || radius <= 0.0 || reaches(frameMatrices[k], frameParams[4 * k + 2].toDouble(), origin.x, origin.y, origin.z, radius)) {
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
     * Draws each frame with media on its own over the tiles it reaches, the media bound on
     * [SensorFrameProgram.MEDIA_UNIT]. True when a media texture was bound.
     */
    private fun drawMedia(dc: DrawContext): Boolean {
        var bound = false
        for (k in 0 until count) {
            val texture = media[k] ?: continue
            val program = (if (texture.target == GL_TEXTURE_EXTERNAL_OES) mediaProgramOes else mediaProgram) ?: continue
            if (!program.useProgram(dc)) continue
            dc.activeTextureUnit(GL_TEXTURE0 + SensorFrameProgram.MEDIA_UNIT)
            val ok = texture.bindTexture(dc)
            dc.activeTextureUnit(GL_TEXTURE0)
            if (!ok) continue
            bound = true
            program.loadOutlineWidth(outlineWidth)
            program.loadMedia(texture.coordTransform, mediaOpacity[k])
            outlineColors.copyInto(chunkOutline, 0, 4 * k, 4 * k + 4)
            fillColors.copyInto(chunkFill, 0, 4 * k, 4 * k + 4)
            occludedColors.copyInto(chunkOccluded, 0, 4 * k, 4 * k + 4)
            frameParams.copyInto(chunkParams, 0, 4 * k, 4 * k + 4)
            depthCells.copyInto(chunkCells, 0, 4 * k, 4 * k + 4)
            program.loadFrames(1, chunkOutline, chunkFill, chunkOccluded, chunkParams, chunkCells)
            reached[0] = k
            if (!scissorTo(dc, 0, 1)) continue
            val range = frameParams[4 * k + 2].toDouble()
            for (idx in 0 until dc.drawableTerrainCount) {
                val terrain = dc.getDrawableTerrain(idx)
                val origin = terrain.vertexOrigin
                val radius = terrain.boundingSphereRadius
                if (isTileCullingEnabled && radius > 0.0 && !reaches(frameMatrices[k], range, origin.x, origin.y, origin.z, radius)) continue
                if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue
                mvp.copy(dc.modelviewProjection).multiplyByTranslation(origin.x, origin.y, origin.z)
                program.loadModelviewProjection(mvp)
                tileMatrix.copy(frameMatrices[k]).multiplyByTranslation(origin.x, origin.y, origin.z)
                tileMatrix.transposeToArray(chunkMatrices, 0)
                program.loadFrameMatrices(chunkMatrices)
                terrain.drawTriangles(dc)
                statDraws++
            }
        }
        return bound
    }

    /**
     * Draws each frame with a hidden-ground mask on its own over the tiles it reaches, the mask
     * bound on [SensorFrameHatchProgram.MASK_UNIT]. True when a mask texture was bound.
     */
    private fun drawHatch(dc: DrawContext): Boolean {
        val program = hatchProgram ?: return false
        var bound = false
        var inUse = false
        for (k in 0 until count) {
            val mask = hatchMasks[k] ?: continue
            val texture = hatchTextures[k] ?: continue
            if (!inUse) {
                if (!program.useProgram(dc)) return false
                inUse = true
            }
            dc.activeTextureUnit(GL_TEXTURE0 + SensorFrameHatchProgram.MASK_UNIT)
            val ok = texture.bindTexture(dc)
            dc.activeTextureUnit(GL_TEXTURE0)
            if (!ok) continue
            bound = true
            program.loadAxes(sensorAxes, 9 * k)
            program.loadGrid(
                mask.startAzimuth.toFloat(), mask.span.toFloat(), mask.reach.toFloat(),
                mask.columns, mask.bins, mask.width, mask.height,
            )
            program.loadHatch(hatchColors, 4 * k, hatchParams[2 * k], hatchParams[2 * k + 1])
            reached[0] = k
            if (!scissorTo(dc, 0, 1)) continue
            val range = frameParams[4 * k + 2].toDouble()
            val sx = sensorPoints[3 * k]; val sy = sensorPoints[3 * k + 1]; val sz = sensorPoints[3 * k + 2]
            for (idx in 0 until dc.drawableTerrainCount) {
                val terrain = dc.getDrawableTerrain(idx)
                val origin = terrain.vertexOrigin
                val radius = terrain.boundingSphereRadius
                if (isTileCullingEnabled && radius > 0.0 && !reaches(frameMatrices[k], range, origin.x, origin.y, origin.z, radius)) continue
                if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue
                mvp.copy(dc.modelviewProjection).multiplyByTranslation(origin.x, origin.y, origin.z)
                program.loadModelviewProjection(mvp)
                program.loadOrigin((origin.x - sx).toFloat(), (origin.y - sy).toFloat(), (origin.z - sz).toFloat())
                terrain.drawTriangles(dc)
                statDraws++
            }
        }
        return bound
    }

    /**
     * Scissors to the union of the screen rectangles of frames `reached[start until start + size]`.
     * False when none of them covers anything on screen, so the draw can be skipped.
     */
    private fun scissorTo(dc: DrawContext, start: Int, size: Int): Boolean {
        if (!isScissorEnabled) {
            dc.gl.disable(GL_SCISSOR_TEST)
            return true
        }
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
