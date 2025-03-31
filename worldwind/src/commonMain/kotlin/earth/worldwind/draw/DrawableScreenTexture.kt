package earth.worldwind.draw

import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.render.Texture
import earth.worldwind.render.program.BasicShaderProgram
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_DEPTH_TEST
import earth.worldwind.util.kgl.GL_FLOAT
import earth.worldwind.util.kgl.GL_TEXTURE0
import earth.worldwind.util.kgl.GL_TRIANGLE_STRIP
import kotlin.jvm.JvmStatic

open class DrawableScreenTexture protected constructor(): Drawable {
    val unitSquareTransform = Matrix4()
    val color = Color()
    var opacity = 1.0f
    var enableDepthTest = true
    var program: BasicShaderProgram? = null
    var texture: Texture? = null
    private var pool: Pool<DrawableScreenTexture>? = null
    private val mvpMatrix = Matrix4()

    companion object {
        val KEY = DrawableScreenTexture::class

        @JvmStatic
        fun obtain(pool: Pool<DrawableScreenTexture>): DrawableScreenTexture {
            val instance = pool.acquire() ?: DrawableScreenTexture()
            instance.pool = pool
            return instance
        }
    }

    override fun recycle() {
        program = null
        texture = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        val program = program ?: return // program unspecified
        if (!program.useProgram(dc)) return // program failed to build
        if (!dc.unitSquareBuffer.bindBuffer(dc)) return // vertex buffer failed to bind

        // Use the draw context's pick mode and use the drawable's color.
        program.enablePickMode(dc.isPickMode)

        // Make multi-texture unit 0 active.
        dc.activeTextureUnit(GL_TEXTURE0)

        // Disable writing to the depth buffer.
        dc.gl.depthMask(false)

        // Use a unit square as the vertex point and vertex tex coord attributes.
        dc.gl.enableVertexAttribArray(1 /*vertexTexCoord*/) // only vertexPoint is enabled by default
        dc.gl.vertexAttribPointer(0 /*vertexPoint*/, 2, GL_FLOAT, false, 0, 0)
        dc.gl.vertexAttribPointer(1 /*vertexTexCoord*/, 2, GL_FLOAT, false, 0, 0)

        // Draw this DrawableScreenTextures.
        doDraw(dc, this)

        // Draw all DrawableScreenTextures adjacent in the queue that share the same GLSL program.
        while (true) {
            val next = dc.peekDrawable() ?: break
            if (!canBatchWith(next)) break // check if the drawable at the front of the queue can be batched
            val drawable = dc.pollDrawable() as DrawableScreenTexture // take it off the queue
            doDraw(dc, drawable)
        }

        // Restore the default WorldWind OpenGL state.
        dc.gl.depthMask(true)
        dc.gl.disableVertexAttribArray(1 /*vertexTexCoord*/) // only vertexPoint is enabled by default
    }

    protected open fun doDraw(dc: DrawContext, drawable: DrawableScreenTexture) {
        val program = drawable.program ?: return

        // Use the drawable's color.
        program.loadColor(drawable.color)
        program.loadOpacity(drawable.opacity)

        // Attempt to bind the drawable's texture, configuring the shader program appropriately if there is no texture
        // or if the texture failed to bind.
        val texture = drawable.texture
        if (texture?.bindTexture(dc) == true) {
            program.enableTexture(true)
            program.loadTexCoordMatrix(texture.coordTransform)
        } else {
            program.enableTexture(false)
            // prevent "RENDER WARNING: there is no texture bound to unit 0"
            dc.defaultTexture.bindTexture(dc)
        }

        // Use a modelview-projection matrix that transforms the unit square to screen coordinates.
        drawable.mvpMatrix.setToMultiply(dc.screenProjection, drawable.unitSquareTransform)
        program.loadModelviewProjection(drawable.mvpMatrix)

        // Disable depth testing if requested.
        if (!drawable.enableDepthTest) dc.gl.disable(GL_DEPTH_TEST)

        // Draw the unit square as triangles.
        dc.gl.drawArrays(GL_TRIANGLE_STRIP, 0, 4)

        // Restore the default WorldWind OpenGL state.
        if (!drawable.enableDepthTest) dc.gl.enable(GL_DEPTH_TEST)
    }

    protected open fun canBatchWith(that: Drawable) = that is DrawableScreenTexture && program === that.program
}