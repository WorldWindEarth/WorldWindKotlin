package earth.worldwind.draw

import earth.worldwind.render.program.SensorEdgeProgram
import earth.worldwind.render.program.SensorFrameProgram
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_DEPTH_TEST
import earth.worldwind.util.kgl.GL_FLOAT
import earth.worldwind.util.kgl.GL_SCISSOR_TEST
import earth.worldwind.util.kgl.GL_TEXTURE0
import earth.worldwind.util.kgl.GL_TRIANGLE_STRIP
import kotlin.jvm.JvmStatic

/**
 * One full-screen pass per map that draws the boundary of each sensor's seen ground: where
 * that sensor's channel of the mask [DrawableSensorMask] wrote crosses one half, in the
 * sensor's colour, [edgeWidth] screen pixels wide. In the SCREEN group, over the terrain and
 * under the symbols; skipped by the overlay when no frame is on screen.
 */
open class DrawableSensorEdges protected constructor() : Drawable {
    /** Premultiplied RGBA per frame, as the mask's channels are numbered. */
    val edgeColors = FloatArray(4 * DrawableSensorMask.MAX_FRAMES)
    var count = 0
    var edgeWidth = 2f
    /** The screen rectangle the frames can cover, in viewport pixels; a negative width for the whole viewport. */
    val rect = IntArray(4)
    var program: SensorEdgeProgram? = null
    private var pool: Pool<DrawableSensorEdges>? = null

    companion object {
        val KEY = DrawableSensorEdges::class

        @JvmStatic
        fun obtain(pool: Pool<DrawableSensorEdges>): DrawableSensorEdges {
            val instance = pool.acquire() ?: DrawableSensorEdges()
            instance.pool = pool
            return instance
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
        val targets = dc.sensorMaskTargets
        if (count == 0 || targets == 0 || !dc.gl.supportsSizedTextureFormats) return
        var bound = 0
        try {
            for (j in 0 until targets) {
                val texture = dc.sensorMaskTexture(j) ?: break
                dc.activeTextureUnit(GL_TEXTURE0 + j)
                if (!texture.bindTexture(dc)) break
                bound++
            }
            dc.activeTextureUnit(GL_TEXTURE0)
            if (bound == 0 || !program.useProgram(dc)) return
            val texture = dc.sensorMaskTexture(0) ?: return
            // Half-texel inset, as the Gaussian composite: the grow-only texture can exceed the pass
            program.loadTexScale(
                (dc.sensorMaskPassWidth - 0.5f) / texture.width, (dc.sensorMaskPassHeight - 0.5f) / texture.height,
            )
            program.loadTexelSize(1f / texture.width, 1f / texture.height)
            program.loadEdges(minOf(count, bound * SensorFrameProgram.MAX_FRAMES), bound, edgeColors, edgeWidth)
            if (!dc.unitSquareBuffer.bindBuffer(dc)) return
            dc.gl.vertexAttribPointer(0 /*vertexPoint*/, 2, GL_FLOAT, false, 0, 0)
            dc.gl.disable(GL_DEPTH_TEST)
            // Only where the frames can be, with a margin for the averaging and the line
            if (rect[2] >= 0) {
                dc.gl.enable(GL_SCISSOR_TEST)
                val margin = edgeWidth.toInt() + 2
                dc.gl.scissor(rect[0] - margin, rect[1] - margin, rect[2] + 2 * margin, rect[3] + 2 * margin)
            }
            try {
                dc.gl.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
            } finally {
                dc.gl.enable(GL_DEPTH_TEST)
                dc.gl.disable(GL_SCISSOR_TEST)
            }
        } finally {
            // Park a plain texture on the units used, as other programs expect
            for (j in 1 until bound) {
                dc.activeTextureUnit(GL_TEXTURE0 + j)
                dc.defaultTexture.bindTexture(dc)
            }
            dc.activeTextureUnit(GL_TEXTURE0)
        }
    }
}
