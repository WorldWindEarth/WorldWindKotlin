package earth.worldwind.draw

import earth.worldwind.geom.Matrix4
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_CULL_FACE
import earth.worldwind.util.kgl.GL_DEPTH_TEST
import earth.worldwind.util.kgl.GL_LINES
import earth.worldwind.util.kgl.GL_LINE_LOOP
import earth.worldwind.util.kgl.GL_LINE_STRIP
import earth.worldwind.util.kgl.GL_TEXTURE0
import kotlin.jvm.JvmStatic

open class DrawableShape protected constructor(): Drawable {
    val drawState = DrawShapeState()
    private var pool: Pool<DrawableShape>? = null
    private val mvpMatrix = Matrix4()

    companion object {
        @JvmStatic
        fun obtain(pool: Pool<DrawableShape>): DrawableShape {
            val instance = pool.acquire() ?: DrawableShape()
            instance.pool = pool
            return instance
        }
    }

    override fun recycle() {
        drawState.reset()
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        // TODO shape batching
        val program = drawState.program ?: return // program unspecified
        if (!program.useProgram(dc)) return // program failed to build
        if (!drawState.vertexState.bind(dc)) return
        if (drawState.elementBuffer?.bindBuffer(dc) != true) return  // element buffer unspecified or failed to bind

        // Use the draw context's pick mode.
        program.enablePickMode(dc.isPickMode)

        // Use the draw context's modelview projection matrix, transformed to shape local coordinates.
        if (drawState.depthOffset != 0.0) {
            mvpMatrix.copy(dc.projection).offsetProjectionDepth(drawState.depthOffset)
            mvpMatrix.multiplyByMatrix(dc.modelview)
        } else {
            mvpMatrix.copy(dc.modelviewProjection)
        }
        mvpMatrix.multiplyByTranslation(
            drawState.vertexOrigin.x,
            drawState.vertexOrigin.y,
            drawState.vertexOrigin.z
        )
        program.loadModelviewProjection(mvpMatrix)
        program.loadPickIdOffset(drawState.pickIdOffset)

        // Disable triangle back face culling if requested.
        if (!drawState.enableCullFace) dc.gl.disable(GL_CULL_FACE)

        // Disable depth testing if requested.
        if (!drawState.enableDepthTest) dc.gl.disable(GL_DEPTH_TEST)

        // Disable depth writing if requested.
        if (!drawState.enableDepthWrite) dc.gl.depthMask(false)

        // Make multi-texture unit 0 active.
        dc.activeTextureUnit(GL_TEXTURE0)

        program.enableLinesMode(drawState.isLine)
        program.enableVertexColorAndWidth(drawState.isStatic)
        if (drawState.isLine) program.loadScreen(
            dc.viewport.width.toFloat(),
            dc.viewport.height.toFloat()
        )

        // Draw the specified primitives.
        for (idx in 0 until drawState.primCount) {
            val prim = drawState.prims[idx]
            program.loadOpacity(prim.opacity)
            if (!drawState.isStatic) {
                program.loadColor(prim.color)
                program.loadLineWidth(prim.lineWidth)
            }
            if (prim.texture?.bindTexture(dc) == true) {
                program.loadTexCoordMatrix(prim.texCoordMatrix)
                program.enableTexture(true)
            } else {
                program.enableTexture(false)
            }
            if (prim.mode == GL_LINES || prim.mode == GL_LINE_STRIP || prim.mode == GL_LINE_LOOP) dc.gl.lineWidth(
                prim.lineWidth
            )
            dc.gl.drawElements(prim.mode, prim.count, prim.type, prim.offset)
        }

        // Restore the default WorldWind OpenGL state.
        if (!drawState.enableCullFace) dc.gl.enable(GL_CULL_FACE)
        if (!drawState.enableDepthTest) dc.gl.enable(GL_DEPTH_TEST)
        if (!drawState.enableDepthWrite) dc.gl.depthMask(true)
        dc.gl.lineWidth(1f)
        dc.gl.enable(GL_CULL_FACE)
        drawState.vertexState.unbind(dc)
    }
}