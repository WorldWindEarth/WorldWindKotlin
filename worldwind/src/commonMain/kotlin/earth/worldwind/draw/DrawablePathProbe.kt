package earth.worldwind.draw

import earth.worldwind.geom.Matrix4
import earth.worldwind.layer.shadow.ShadowCaster
import earth.worldwind.render.program.ProbeOccupancyProgram
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.*
import kotlin.jvm.JvmStatic

/**
 * Renders every solid occluder inside a probe corridor (see [earth.worldwind.shape.PathProbe]) from
 * straight above with an orthographic camera, reads the image back and hands it over as a grid of
 * top-surface depths. Runs on the GL thread, in the background drawable group, after the frame's
 * drawable queue is complete.
 *
 * Every [SightlineOccluder] takes part: 3D Tiles meshes, filled shapes, meshes and COLLADA models.
 * Terrain does not; callers check it against elevation data. Shapes with
 * [DrawShapeState.isProbeObstacle] off are skipped.
 */
open class DrawablePathProbe protected constructor() : DrawableSightline() {
    /** Camera projection times view, composed in double so ECEF translations cancel. */
    val projectionView = Matrix4()
    var columns = 0
    var rows = 0
    var occupancyProgram: ProbeOccupancyProgram? = null
    /**
     * Receives the grid on the GL thread: `columns * rows` cells, row 0 at the bottom, each the
     * window depth of the highest surface there (0 = the camera's near plane), or -1 where nothing is.
     */
    var onOccupancy: ((FloatArray) -> Unit)? = null
    private var probePool: Pool<DrawablePathProbe>? = null
    private var pixels = ByteArray(0)

    override fun recycle() {
        occupancyProgram = null
        onOccupancy = null
        super.recycle()
        probePool?.release(this)
        probePool = null
    }

    override fun draw(dc: DrawContext) {
        val program = occupancyProgram ?: return
        val callback = onOccupancy ?: return
        if (columns <= 0 || rows <= 0) return
        if (!program.useProgram(dc)) return
        val framebuffer = dc.scratchFramebuffer
        val previousFramebuffer = dc.currentFramebuffer
        if (!framebuffer.bindFramebuffer(dc)) return
        try {
            dc.gl.viewport(0, 0, columns, rows)
            dc.gl.clearColor(0f, 0f, 0f, 0f)
            dc.gl.clear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            // The highest surface in each pixel wins.
            dc.gl.enable(GL_DEPTH_TEST)
            dc.gl.depthFunc(GL_LESS)
            dc.gl.depthMask(true)
            dc.gl.disable(GL_CULL_FACE)
            beginOccluderPass(program, projectionView)
            drawProbeOccluders(dc)
            val size = columns * rows * 4
            if (pixels.size < size) pixels = ByteArray(size)
            dc.gl.readPixels(0, 0, columns, rows, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
        } finally {
            dc.bindFramebuffer(previousFramebuffer)
            dc.gl.viewport(dc.viewport.x, dc.viewport.y, dc.viewport.width, dc.viewport.height)
            dc.gl.depthFunc(GL_LEQUAL)
            dc.gl.enable(GL_CULL_FACE)
        }
        val unpack = { i: Int -> (pixels[i].toInt() and 0xFF) / 255f }
        callback(FloatArray(columns * rows) { p ->
            if (pixels[p * 4 + 3] == 0.toByte()) -1f
            else unpack(p * 4) + unpack(p * 4 + 1) / 255f + unpack(p * 4 + 2) / (255f * 255f)
        })
    }

    /** Like [drawShapesDepth], minus the shapes that opted out of probes. */
    protected open fun drawProbeOccluders(dc: DrawContext) {
        val queue = dc.drawableQueue ?: return
        for (i in 0 until queue.count) {
            val drawable = queue.getDrawable(i)
            if (drawable !is SightlineOccluder || drawable is DrawableSightline) continue
            if (drawable is DrawableShape && !drawable.drawState.isProbeObstacle) continue
            if (drawable is DrawableMesh && !drawable.drawState.isProbeObstacle) continue
            if (drawable is ShadowCaster) {
                val center = drawable.shadowCasterCenter
                if (center != null && !intersectsRange(center, drawable.shadowCasterRadius)) continue
            }
            drawable.drawSightlineDepth(dc, this)
        }
    }

    companion object {
        val PROBE_KEY = DrawablePathProbe::class

        @JvmStatic
        fun obtain(pool: Pool<DrawablePathProbe>): DrawablePathProbe {
            val instance = pool.acquire() ?: DrawablePathProbe()
            instance.probePool = pool
            return instance
        }
    }
}
