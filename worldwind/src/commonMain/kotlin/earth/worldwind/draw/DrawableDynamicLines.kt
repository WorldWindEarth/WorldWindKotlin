package earth.worldwind.draw

import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.render.program.BasicShaderProgram
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_DEPTH_TEST
import earth.worldwind.util.kgl.GL_FLOAT
import earth.worldwind.util.kgl.GL_LINES
import kotlin.jvm.JvmStatic

open class DrawableDynamicLines protected constructor(): Drawable {
    /**
     * Leader line vertex array. Initially sized to store two xyz points.
     */
    var vertexPoints = FloatArray(6)
    val mvpMatrix = Matrix4()
    val color = Color()
    var opacity = 1.0f
    var lineWidth = 1f
    var enableDepthTest = true
    var program: BasicShaderProgram? = null
    private var pool: Pool<DrawableDynamicLines>? = null

    companion object {
        @JvmStatic
        fun obtain(pool: Pool<DrawableDynamicLines>): DrawableDynamicLines {
            val instance = pool.acquire() ?: DrawableDynamicLines()
            instance.pool = pool
            return instance
        }
    }

    override fun recycle() {
        program = null
        pool?.release(this)
        pool = null
    }

    /**
     * Performs the actual rendering of the Placemark.
     *
     * @param dc The current draw context.
     */
    override fun draw(dc: DrawContext) {
        val program = program ?: return // program unspecified
        if (!program.useProgram(dc)) return // program failed to build
        val offset = dc.bindBufferPool(vertexPoints)
        if (offset < 0) return // vertex buffer unspecified or failed to bind

        // Disable texturing.
        program.enableTexture(false)

        // Use the leader's color.
        program.loadColor(color)

        // Use the leader's opacity.
        program.loadOpacity(opacity)

        // Use the leader's modelview-projection matrix.
        program.loadModelviewProjection(mvpMatrix)

        // Disable depth testing if requested.
        if (!enableDepthTest) dc.gl.disable(GL_DEPTH_TEST)

        // Apply the leader's line width in screen pixels.
        dc.gl.lineWidth(lineWidth)

        // Use the leader line as the vertex point attribute.
        dc.gl.vertexAttribPointer(0 /*vertexPoint*/, 3, GL_FLOAT, false, 0, offset)

        // Draw the leader line.
        dc.gl.drawArrays(GL_LINES, 0 /*first*/, 2 /*count*/)

        // Restore the default WorldWind OpenGL state.
        if (!enableDepthTest) dc.gl.enable(GL_DEPTH_TEST)

        dc.gl.lineWidth(1f)
    }
}