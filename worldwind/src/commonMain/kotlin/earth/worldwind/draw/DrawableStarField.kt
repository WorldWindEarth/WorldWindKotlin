package earth.worldwind.draw

import earth.worldwind.geom.Matrix4
import earth.worldwind.layer.starfield.StarFieldProgram
import earth.worldwind.render.Texture
import earth.worldwind.render.buffer.GLBufferObject
import earth.worldwind.util.Logger
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_ALIASED_POINT_SIZE_RANGE
import earth.worldwind.util.kgl.GL_FLOAT
import earth.worldwind.util.kgl.GL_POINTS
import kotlin.jvm.JvmStatic

open class DrawableStarField protected constructor(): Drawable {
    val matrix = Matrix4()
    var julianDate = 0.0
    var minMagnitude = 0.0f
    var maxMagnitude = 0.0f
    var numStars = 0
    var isShowSun = false
    var sunSize = 0.0f
    var sunTexture: Texture? = null
    var starsPositionsBuffer: GLBufferObject? = null
    var sunPositionsBuffer: GLBufferObject? = null
    var program: StarFieldProgram? = null
    private var pool: Pool<DrawableStarField>? = null

    companion object {
        var maxGlPointSize = 0f

        @JvmStatic
        fun obtain(pool: Pool<DrawableStarField>): DrawableStarField {
            val instance = pool.acquire() ?: DrawableStarField()
            instance.pool = pool
            return instance
        }
    }

    override fun recycle() {
        starsPositionsBuffer = null
        sunPositionsBuffer = null
        sunTexture = null
        program = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        val program = program ?: return // program unspecified
        if (!program.useProgram(dc)) return // program failed to build
        try {
            dc.gl.depthMask(false)
            drawStars(dc, program, starsPositionsBuffer)
            if (isShowSun) drawSun(dc, program, sunPositionsBuffer, sunTexture)
        } finally {
            dc.gl.depthMask(true)
        }
    }

    protected open fun drawStars(dc: DrawContext, program: StarFieldProgram, buffer: GLBufferObject?) {
        if (buffer?.bindBuffer(dc) != true) return
        dc.gl.vertexAttribPointer(0, 4, GL_FLOAT, false, 0, 0)
        // This subtraction does not work properly on the GPU due to precision loss. It must be done on the CPU.
        program.loadModelviewProjection(matrix)
        program.loadNumDays((julianDate - 2451545.0).toFloat())
        program.loadMagnitudeRange(minMagnitude, maxMagnitude)
        program.loadTextureEnabled(false)
        dc.gl.drawArrays(GL_POINTS, 0, numStars)
    }

    protected open fun drawSun(
        dc: DrawContext, program: StarFieldProgram, sunBuffer: GLBufferObject?, sunTexture: Texture?
    ) {
        if (maxGlPointSize == 0f) maxGlPointSize = dc.gl.getParameterfv(GL_ALIASED_POINT_SIZE_RANGE)[1]

        if (sunSize > maxGlPointSize) {
            Logger.log(Logger.WARN, "StarFieldLayer - sunSize is to big, max size allowed is: $maxGlPointSize")
        }

        if (sunBuffer?.bindBuffer(dc) != true) return
        if (sunTexture?.bindTexture(dc) != true) return
        dc.gl.vertexAttribPointer(0, 4, GL_FLOAT, false, 0, 0)
        program.loadTextureEnabled(true)
        dc.gl.drawArrays(GL_POINTS, 0, 1)
    }
}