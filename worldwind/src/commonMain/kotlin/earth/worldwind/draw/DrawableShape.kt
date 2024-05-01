package earth.worldwind.draw

import earth.worldwind.geom.Matrix4
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_CULL_FACE
import earth.worldwind.util.kgl.GL_DEPTH_TEST
import earth.worldwind.util.kgl.GL_FLOAT
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
        if (drawState.vertexBuffer?.bindBuffer(dc) != true) return  // vertex buffer unspecified or failed to bind
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

        // Disable triangle back face culling if requested.
        if (!drawState.enableCullFace) dc.gl.disable(GL_CULL_FACE)

        // Disable depth testing if requested.
        if (!drawState.enableDepthTest) dc.gl.disable(GL_DEPTH_TEST)

        // Disable depth writing if requested.
        if (!drawState.enableDepthWrite) dc.gl.depthMask(false)

        // Make multi-texture unit 0 active.
        dc.activeTextureUnit(GL_TEXTURE0)

        // Use the shape's vertex point attribute and vertex texture coordinate attribute.
        dc.gl.enableVertexAttribArray(1 /*vertexTexCoord*/)
        dc.gl.enableVertexAttribArray(2 /*vertexTexCoord*/)
        dc.gl.enableVertexAttribArray(3 /*vertexTexCoord*/)

        if (drawState.isLine) {
            program.enableOneVertexMode(false)
            program.loadScreen(dc.viewport.width.toFloat(), dc.viewport.height.toFloat())
            dc.gl.vertexAttribPointer(0 /*pointA*/, 4, GL_FLOAT, false, 20, 0)
            dc.gl.vertexAttribPointer(1 /*pointB*/, 4, GL_FLOAT, false, 20, 40)
            dc.gl.vertexAttribPointer(2 /*pointC*/, 4, GL_FLOAT, false, 20, 80)
            dc.gl.vertexAttribPointer(3 /*texCoord*/, 1, GL_FLOAT, false, 20, 56)
        } else {
            program.enableOneVertexMode(true)
            dc.gl.vertexAttribPointer(0 /*vertexPoint*/, 3, GL_FLOAT, false, drawState.vertexStride, 0)
            dc.gl.vertexAttribPointer(1 /*vertexPoint*/, 3, GL_FLOAT, false, drawState.vertexStride, 0)
            dc.gl.vertexAttribPointer(2 /*vertexPoint*/, 3, GL_FLOAT, false, drawState.vertexStride, 0)
        }
        // Draw the specified primitives.
        for (idx in 0 until drawState.primCount) {
            val prim = drawState.prims[idx]
            program.loadColor(prim.color)
            program.loadOpacity(prim.opacity)
            if (prim.texture?.bindTexture(dc) == true) {
                program.loadTexCoordMatrix(prim.texCoordMatrix)
                program.enableTexture(true)
            } else {
                program.enableTexture(false)
            }
            if (drawState.isLine) {
                program.loadLineWidth(prim.lineWidth)
            } else {
                dc.gl.vertexAttribPointer(
                    3 /*vertexTexCoord*/,
                    prim.texCoordAttrib.size,
                    GL_FLOAT,
                    false,
                    drawState.vertexStride,
                    prim.texCoordAttrib.offset
                )
                dc.gl.lineWidth(prim.lineWidth)
            }
            dc.gl.drawElements(prim.mode, prim.count, prim.type, prim.offset)
        }

        // Restore the default WorldWind OpenGL state.
        if (!drawState.enableCullFace) dc.gl.enable(GL_CULL_FACE)
        if (!drawState.enableDepthTest) dc.gl.enable(GL_DEPTH_TEST)
        if (!drawState.enableDepthWrite) dc.gl.depthMask(true)
        dc.gl.lineWidth(1f)
        dc.gl.enable(GL_CULL_FACE)
        dc.gl.disableVertexAttribArray(1 /*vertexTexCoord*/)
        dc.gl.disableVertexAttribArray(2 /*vertexTexCoord*/)
        dc.gl.disableVertexAttribArray(3 /*vertexTexCoord*/)
    }
}