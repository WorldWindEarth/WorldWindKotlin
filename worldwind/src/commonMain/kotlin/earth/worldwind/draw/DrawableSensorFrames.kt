package earth.worldwind.draw

import earth.worldwind.geom.Matrix4
import earth.worldwind.globe.Globe
import earth.worldwind.render.Texture
import earth.worldwind.render.program.SensorFrameHatchProgram
import earth.worldwind.render.program.SensorFrameMediaProgram
import earth.worldwind.sensor.ViewshedMask
import earth.worldwind.render.program.SensorFrameProgram
import earth.worldwind.render.program.SensorFrameProgram.Companion.BEND_FLOATS
import earth.worldwind.render.program.SensorFrameProgram.Companion.MAX_FRAMES
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_SAMPLE_BUFFERS
import earth.worldwind.util.kgl.GL_SCISSOR_TEST
import earth.worldwind.util.kgl.GL_TEXTURE0
import earth.worldwind.util.kgl.GL_TEXTURE_2D
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
 * On a 2D globe the frame matrices are the map's projection made linear at each sensor, and the
 * passes add back the second-order term per vertex ([bendClips]); terrain is drawn only at the
 * globe [offset] the matrices were made for, one drawable per offset of a continuous map.
 *
 * Frames with a cell in the depth atlas ([DrawableSensorFrameDepth], rendered earlier in the
 * frame) are occluded: their fill is drawn only where the sensor sees the ground, and their
 * occluded colour where a ridge hides it. The outline is drawn either way.
 *
 * A frame with [media] is drawn once more on its own, after the others, with
 * [SensorFrameMediaProgram]: the picture where the sensor sees the ground, its tint and outline
 * over it. A frame with hidden ground to mark, from its viewshed's mask ([hatchMasks]) or from the
 * depth atlas ([hatchFromDepth]), is drawn once more again with [SensorFrameHatchProgram]:
 * screen-space hatching or a fill there, over the media, in a fixed colour or darkening and
 * lightening the ground under it by its luminance ([hatchContrast]).
 *
 * In the pick pass ([DrawContext.isPickMode]) the frames' outline and fill colours are their pick
 * colours, or transparent where that part is not pickable, and the program draws them solid,
 * unoccluded, with no media and no hatching: where frames overlap the last one wins.
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
    /** Whether a frame's media is cut to the ground the sensor sees, and its soft edge as a fraction of the half width. */
    val mediaClipped = BooleanArray(MAX_TOTAL)
    val mediaSoftEdge = FloatArray(MAX_TOTAL)
    /** A frame's hidden-ground mask and its texture, where it hatches; its premultiplied colour; spacing and width in pixels. */
    val hatchMasks = arrayOfNulls<ViewshedMask>(MAX_TOTAL)
    val hatchTextures = arrayOfNulls<Texture>(MAX_TOTAL)
    val hatchColors = FloatArray(4 * MAX_TOTAL)
    val hatchParams = FloatArray(2 * MAX_TOTAL)
    /**
     * Whether a frame's hidden ground is the depth atlas's rather than its mask's, and how its
     * lines are coloured, one of [SensorFrameHatchProgram]'s CONTRAST values.
     */
    val hatchFromDepth = BooleanArray(MAX_TOTAL)
    val hatchContrast = IntArray(MAX_TOTAL)
    /**
     * The sensor's Cartesian point, and the rows taking a Cartesian offset from it to east, north
     * and up metres (its unit axes on a 3D globe), per frame.
     */
    val sensorPoints = DoubleArray(3 * MAX_TOTAL)
    val sensorAxes = FloatArray(9 * MAX_TOTAL)
    /**
     * Per frame, the change of its coordinates per e², e·n and n² of the linear metres from the
     * sensor, three vec4s, which puts the second-order term back on a 2D globe; zero on a 3D
     * globe. See `SensorLocalFrame` and `Sensor.frameTransform`.
     */
    val bendClips = FloatArray(CLIP_FLOATS * MAX_TOTAL)
    /** Per frame, how far the second-order term can move ground within the range, Cartesian units: added to a tile's sphere when culling. */
    val bendSlack = DoubleArray(MAX_TOTAL)
    /** The globe offset the frames' matrices were made at: only terrain at that offset is drawn. */
    var offset = Globe.Offset.Center
    var count = 0
    var outlineWidth = 2f
    /** Side of the outline's dash checkerboard in screen pixels; 0 for solid. */
    var outlineDash = 0f
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
    private val chunkBends = FloatArray(BEND_FLOATS * MAX_FRAMES)

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

        /** Floats of a frame's [bendClips]. */
        const val CLIP_FLOATS = 12

        /**
         * Writes to [out] at [outOffset] the [BEND_FLOATS] floats of `frameBend` for a frame on a
         * tile whose vertices are relative to ([ox], [oy], [oz]): the east and north rows from
         * [axes] at [axesOffset], each with the tile origin's metres from the sensor at ([sx],
         * [sy], [sz]) as its `w`, then the frame's clip changes at [clipOffset] in [clips]. All
         * zero where the frame has none (a 3D globe).
         */
        internal fun tileBend(
            axes: FloatArray, axesOffset: Int, sx: Double, sy: Double, sz: Double,
            clips: FloatArray, clipOffset: Int, ox: Double, oy: Double, oz: Double, out: FloatArray, outOffset: Int,
        ) {
            if ((0 until CLIP_FLOATS).all { clips[clipOffset + it] == 0f }) {
                out.fill(0f, outOffset, outOffset + BEND_FLOATS)
                return
            }
            val dx = ox - sx; val dy = oy - sy; val dz = oz - sz
            for (row in 0..1) {
                val a = axesOffset + 3 * row
                val o = outOffset + 4 * row
                out[o] = axes[a]; out[o + 1] = axes[a + 1]; out[o + 2] = axes[a + 2]
                out[o + 3] = (axes[a] * dx + axes[a + 1] * dy + axes[a + 2] * dz).toFloat()
            }
            clips.copyInto(out, outOffset + 8, clipOffset, clipOffset + CLIP_FLOATS)
        }

        /** Frames in the draw of a tile's [n] frames that starts at frame [start]: at most [MAX_FRAMES]. */
        internal fun chunkSize(n: Int, start: Int) = minOf(MAX_FRAMES, n - start)

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
        bendClips.fill(0f, 0, CLIP_FLOATS * count)
        bendSlack.fill(0.0, 0, count)
        count = 0
        offset = Globe.Offset.Center
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
        // WebGL2 checks the sampler type against the bound texture at every draw, even when the
        // shader never samples it: an unoccluded draw's sampler2DShadow still needs a
        // compare-mode depth texture, or the draw fails and no frame shows
        val nullDepth = !occlusion && dc.gl.hasShadowSamplers
        if (nullDepth) {
            dc.activeTextureUnit(GL_TEXTURE0 + SensorFrameProgram.DEPTH_UNIT)
            dc.nullShadowDepthTexture.bindTexture(dc)
            dc.activeTextureUnit(GL_TEXTURE0)
        }
        program.loadOutlineWidth(outlineWidth)
        program.loadOutlineDash(outlineDash)
        program.loadPickMode(dc.isPickMode)
        var mediaBound = false
        try {
            drawTiles(dc, program)
            // The pick pass draws only the frames' pick colours; it carries no media or hatching
            if (!dc.isPickMode) {
                mediaBound = drawMedia(dc)
                mediaBound = drawHatch(dc) || mediaBound
            }
        } finally {
            dc.gl.disable(GL_SCISSOR_TEST)
            if (mediaBound) {
                dc.activeTextureUnit(GL_TEXTURE0 + SensorFrameProgram.MEDIA_UNIT)
                dc.defaultTexture.bindTexture(dc)
                dc.activeTextureUnit(GL_TEXTURE0)
            }
            // Park a plain texture on the depth unit: other programs sample it as a sampler2D,
            // and the next depth pass renders into the atlas
            if (occlusion || nullDepth) {
                dc.activeTextureUnit(GL_TEXTURE0 + SensorFrameProgram.DEPTH_UNIT)
                dc.defaultTexture.bindTexture(dc)
                dc.activeTextureUnit(GL_TEXTURE0)
            }
        }
    }

    private fun drawTiles(dc: DrawContext, program: SensorFrameProgram) {
        for (idx in 0 until dc.drawableTerrainCount) {
            val terrain = dc.getDrawableTerrain(idx)
            if (terrain.offset != offset) continue
            val origin = terrain.vertexOrigin
            val radius = terrain.boundingSphereRadius
            val n = framesReaching(origin.x, origin.y, origin.z, radius, reached)
            if (n == 0) continue
            if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue
            statTiles++
            // Terrain vertices are tile-origin-relative; compose in double per tile
            mvp.copy(dc.modelviewProjection).multiplyByTranslation(origin.x, origin.y, origin.z)
            program.loadModelviewProjection(mvp)
            var start = 0
            while (start < n) {
                val size = chunkSize(n, start)
                for (j in 0 until size) {
                    val k = reached[start + j]
                    tileMatrix.copy(frameMatrices[k]).multiplyByTranslation(origin.x, origin.y, origin.z)
                    tileMatrix.transposeToArray(chunkMatrices, 16 * j)
                    outlineColors.copyInto(chunkOutline, 4 * j, 4 * k, 4 * k + 4)
                    fillColors.copyInto(chunkFill, 4 * j, 4 * k, 4 * k + 4)
                    occludedColors.copyInto(chunkOccluded, 4 * j, 4 * k, 4 * k + 4)
                    frameParams.copyInto(chunkParams, 4 * j, 4 * k, 4 * k + 4)
                    depthCells.copyInto(chunkCells, 4 * j, 4 * k, 4 * k + 4)
                    bendOf(k, origin.x, origin.y, origin.z, BEND_FLOATS * j)
                }
                if (!scissorTo(dc, start, size)) { start += size; continue }
                program.loadFrames(size, chunkOutline, chunkFill, chunkOccluded, chunkParams, chunkCells)
                program.loadFrameMatrices(chunkMatrices)
                program.loadFrameBends(chunkBends)
                terrain.drawTriangles(dc)
                statDraws++
                start += size
            }
        }
    }

    private fun bendOf(k: Int, ox: Double, oy: Double, oz: Double, outOffset: Int) = tileBend(
        sensorAxes, 9 * k, sensorPoints[3 * k], sensorPoints[3 * k + 1], sensorPoints[3 * k + 2],
        bendClips, CLIP_FLOATS * k, ox, oy, oz, chunkBends, outOffset,
    )

    /** Whether a terrain tile at this drawable's offset may be reached by frame [k]. */
    private fun tileReaches(k: Int, terrain: DrawableTerrain): Boolean {
        if (terrain.offset != offset) return false
        val origin = terrain.vertexOrigin
        val radius = terrain.boundingSphereRadius
        return !isTileCullingEnabled || radius <= 0.0 ||
            reaches(frameMatrices[k], frameParams[4 * k + 2].toDouble(), origin.x, origin.y, origin.z, radius + bendSlack[k])
    }

    /**
     * Writes to [out] the indices of the frames that reach the tile whose bounding sphere is at
     * ([x], [y], [z]) with [radius], in frame order, and returns how many; a tile without a sphere
     * (radius 0) is reached by all of them.
     */
    internal fun framesReaching(x: Double, y: Double, z: Double, radius: Double, out: IntArray): Int {
        var n = 0
        for (k in 0 until count) {
            if (!isTileCullingEnabled || radius <= 0.0 || reaches(frameMatrices[k], frameParams[4 * k + 2].toDouble(), x, y, z, radius + bendSlack[k])) {
                out[n++] = k
            }
        }
        return n
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
            program.loadOutlineDash(outlineDash)
            program.loadMedia(texture.coordTransform, mediaOpacity[k], mediaClipped[k], mediaSoftEdge[k])
            outlineColors.copyInto(chunkOutline, 0, 4 * k, 4 * k + 4)
            fillColors.copyInto(chunkFill, 0, 4 * k, 4 * k + 4)
            occludedColors.copyInto(chunkOccluded, 0, 4 * k, 4 * k + 4)
            frameParams.copyInto(chunkParams, 0, 4 * k, 4 * k + 4)
            depthCells.copyInto(chunkCells, 0, 4 * k, 4 * k + 4)
            program.loadFrames(1, chunkOutline, chunkFill, chunkOccluded, chunkParams, chunkCells)
            reached[0] = k
            if (!scissorTo(dc, 0, 1)) continue
            for (idx in 0 until dc.drawableTerrainCount) {
                val terrain = dc.getDrawableTerrain(idx)
                if (!tileReaches(k, terrain)) continue
                if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue
                val origin = terrain.vertexOrigin
                mvp.copy(dc.modelviewProjection).multiplyByTranslation(origin.x, origin.y, origin.z)
                program.loadModelviewProjection(mvp)
                tileMatrix.copy(frameMatrices[k]).multiplyByTranslation(origin.x, origin.y, origin.z)
                tileMatrix.transposeToArray(chunkMatrices, 0)
                program.loadFrameMatrices(chunkMatrices)
                bendOf(k, origin.x, origin.y, origin.z, 0)
                program.loadFrameBends(chunkBends)
                terrain.drawTriangles(dc)
                statDraws++
            }
        }
        return bound
    }

    /**
     * Draws each frame with hidden ground to mark on its own over the tiles it reaches: from its
     * mask, bound on [SensorFrameHatchProgram.MASK_UNIT], or from the depth atlas where the frame
     * has a cell in it. A frame coloured by the ground's luminance gets a copy of the framebuffer
     * under its rectangle first; where the framebuffer cannot be copied it is drawn two-tone.
     * True when a mask texture was bound.
     */
    private fun drawHatch(dc: DrawContext): Boolean {
        val program = hatchProgram ?: return false
        var bound = false
        var inUse = false
        var copyable: Boolean? = null
        for (k in 0 until count) {
            val mask = hatchMasks[k]
            val fromDepth = hatchFromDepth[k]
            if (fromDepth) {
                if (depthCells[4 * k + 3] <= 0f) continue
            } else if (mask == null || hatchTextures[k] == null) continue
            if (!inUse) {
                if (!program.useProgram(dc)) return false
                inUse = true
            }
            reached[0] = k
            if (!scissorTo(dc, 0, 1)) continue
            if (mask != null && !fromDepth) {
                dc.activeTextureUnit(GL_TEXTURE0 + SensorFrameHatchProgram.MASK_UNIT)
                val ok = hatchTextures[k]!!.bindTexture(dc)
                dc.activeTextureUnit(GL_TEXTURE0)
                if (!ok) continue
                bound = true
                program.loadGrid(
                    mask.startAzimuth.toFloat(), mask.span.toFloat(), mask.reach.toFloat(),
                    mask.columns, mask.bins, mask.width, mask.height,
                )
            }
            var contrast = hatchContrast[k]
            if (contrast == SensorFrameHatchProgram.CONTRAST_AUTO) {
                // A multisampled framebuffer cannot be copied: two-tone reads on any ground instead
                val canCopy = copyable ?: (dc.gl.getParameteri(GL_SAMPLE_BUFFERS) == 0).also { copyable = it }
                if (!canCopy || !copyScene(dc, program, k)) contrast = SensorFrameHatchProgram.CONTRAST_TWO_TONE
            }
            program.loadMode(fromDepth, contrast)
            program.loadAxes(sensorAxes, 9 * k)
            program.loadDepth(frameParams, depthCells, 4 * k)
            program.loadHatch(hatchColors, 4 * k, hatchParams[2 * k], hatchParams[2 * k + 1])
            val sx = sensorPoints[3 * k]; val sy = sensorPoints[3 * k + 1]; val sz = sensorPoints[3 * k + 2]
            for (idx in 0 until dc.drawableTerrainCount) {
                val terrain = dc.getDrawableTerrain(idx)
                if (!tileReaches(k, terrain)) continue
                if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue
                val origin = terrain.vertexOrigin
                mvp.copy(dc.modelviewProjection).multiplyByTranslation(origin.x, origin.y, origin.z)
                program.loadModelviewProjection(mvp)
                tileMatrix.copy(frameMatrices[k]).multiplyByTranslation(origin.x, origin.y, origin.z)
                program.loadFrameMatrix(tileMatrix)
                bendOf(k, origin.x, origin.y, origin.z, 0)
                program.loadFrameBend(chunkBends, 0)
                program.loadOrigin((origin.x - sx).toFloat(), (origin.y - sy).toFloat(), (origin.z - sz).toFloat())
                terrain.drawTriangles(dc)
                statDraws++
            }
        }
        return bound
    }

    /**
     * Copies what is drawn so far under frame [k]'s screen rectangle, or the whole viewport when
     * it has none, into the scene copy on [SensorFrameHatchProgram.SCENE_UNIT], for the pass to
     * read the ground's luminance from. False when there was nothing to copy.
     */
    private fun copyScene(dc: DrawContext, program: SensorFrameHatchProgram, k: Int): Boolean {
        val viewport = dc.viewport
        if (viewport.width <= 0 || viewport.height <= 0) return false
        var x0 = viewport.x; var y0 = viewport.y
        var x1 = viewport.x + viewport.width; var y1 = viewport.y + viewport.height
        if (isScissorEnabled && frameRects[4 * k + 2] >= 0) {
            x0 = maxOf(x0, frameRects[4 * k]); y0 = maxOf(y0, frameRects[4 * k + 1])
            x1 = minOf(x1, frameRects[4 * k] + frameRects[4 * k + 2]); y1 = minOf(y1, frameRects[4 * k + 1] + frameRects[4 * k + 3])
        }
        if (x0 >= x1 || y0 >= y1) return false
        val texture = dc.sceneCopyTexture(viewport.width, viewport.height)
        dc.activeTextureUnit(GL_TEXTURE0 + SensorFrameHatchProgram.SCENE_UNIT)
        if (!texture.bindTexture(dc)) return false
        dc.gl.copyTexSubImage2D(GL_TEXTURE_2D, 0, x0 - viewport.x, y0 - viewport.y, x0, y0, x1 - x0, y1 - y0)
        program.loadScene(viewport.x, viewport.y, viewport.width, viewport.height)
        return true
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
