package earth.worldwind.draw

import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.render.program.TriangleShaderProgram
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_DEPTH_TEST
import earth.worldwind.util.kgl.GL_FLOAT
import earth.worldwind.util.kgl.GL_TRIANGLES
import earth.worldwind.util.kgl.GL_UNSIGNED_INT
import kotlin.jvm.JvmStatic

open class DrawableLines protected constructor(): Drawable {
    /**
     * Leader line vertex array. Initially sized to store two xyz points.
     */
    var vertexPoints = FloatArray(40)
    val mvpMatrix = Matrix4()
    val color = Color()
    var opacity = 1.0f
    var lineWidth = 1f
    var enableDepthTest = true
    var program: TriangleShaderProgram? = null
    private var pool: Pool<DrawableLines>? = null

    companion object {
        @JvmStatic
        fun obtain(pool: Pool<DrawableLines>): DrawableLines {
            val instance = pool.acquire() ?: DrawableLines()
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
        if (!dc.rectangleElementsBuffer.bindBuffer(dc)) return // element buffer failed to bind
        val offset = dc.bindBufferPool(vertexPoints)
        if (offset < 0) return // vertex buffer unspecified or failed to bind

        // Disable texturing.
        program.enableTexture(false)

        // Ensure program is in lines mode
        program.enableLinesMode(true)
        program.enableVertexColorAndWidth(false)

        // Use the leader's color.
        program.loadColor(color)

        // Use the leader's opacity.
        program.loadOpacity(opacity)

        // Use the leader's modelview-projection matrix.
        program.loadModelviewProjection(mvpMatrix)

        // Use render target dimensions
        program.loadScreen(dc.viewport.width.toFloat(), dc.viewport.height.toFloat())

        // Use the leader's line width in screen pixels.
        program.loadLineWidth(lineWidth)

        // Set cutoff to 10 to avoid possible cutoff at small values of miter length
        program.loadMiterLengthCutoff(10f)

        // Disable depth testing if requested.
        if (!enableDepthTest) dc.gl.disable(GL_DEPTH_TEST)

        // Use the leader line as the vertex point attribute.
        dc.gl.enableVertexAttribArray(1 /*pointB*/)
        dc.gl.enableVertexAttribArray(2 /*pointC*/)

        // Use the shape's vertex point attribute and vertex texture coordinate attribute.
        dc.gl.vertexAttribPointer(0 /*pointA*/, 4, GL_FLOAT, false, 20, offset + 0)
        dc.gl.vertexAttribPointer(1 /*pointB*/, 4, GL_FLOAT, false, 20, offset + 40)
        dc.gl.vertexAttribPointer(2 /*pointC*/, 4, GL_FLOAT, false, 20, offset + 80)

        // Draw the leader line.
        dc.gl.drawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0)

        // Restore the default WorldWind OpenGL state.
        if (!enableDepthTest) dc.gl.enable(GL_DEPTH_TEST)

        dc.gl.disableVertexAttribArray(1 /*pointB*/)
        dc.gl.disableVertexAttribArray(2 /*pointC*/)
    }
}