package earth.worldwind.draw

import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Sector
import earth.worldwind.globe.Globe
import earth.worldwind.render.Color
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.*
import kotlin.jvm.JvmStatic

open class DrawableSurfaceShape protected constructor(): Drawable {
    var offset = Globe.Offset.Center
    val sector = Sector()
    val drawState = DrawShapeState()
    private var pool: Pool<DrawableSurfaceShape>? = null
    private val mvpMatrix = Matrix4()
    private val textureMvpMatrix = Matrix4()
    private val identityMatrix3 = Matrix3()
    private val color = Color()
    private var opacity = 1.0f

    companion object {
        @JvmStatic
        fun obtain(pool: Pool<DrawableSurfaceShape>): DrawableSurfaceShape {
            val instance = pool.acquire() ?: DrawableSurfaceShape()
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
        val program = drawState.program ?: return // program unspecified
        if (!program.useProgram(dc)) return // program failed to build

        // Make multi-texture unit 0 active.
        dc.activeTextureUnit(GL_TEXTURE0)

        // Accumulate shapes in the draw context's scratch list.
        // TODO accumulate in a geospatial quadtree
        val scratchList = dc.scratchList
        try {
            // Add this shape.
            scratchList.add(this)

            // Add all shapes that are contiguous in the drawable queue.
            while (true) {
                val next = dc.peekDrawable() ?: break
                if (next !is DrawableSurfaceShape) break // check if the drawable at the front of the queue can be batched
                dc.pollDrawable() // take it off the queue
                scratchList.add(next)
            }

            // Draw the accumulated shapes on each drawable terrain.
            for (idx in 0 until dc.drawableTerrainCount) {
                // Get the drawable terrain associated with the draw context.
                val terrain = dc.getDrawableTerrain(idx)
                // Draw the accumulated surface shapes to a texture representing the terrain's sector.
                if (drawShapesToTexture(dc, terrain) > 0) {
                    // Draw the texture containing the rasterized shapes onto the terrain geometry.
                    drawTextureToTerrain(dc, terrain)
                }
            }
        } finally {
            // Clear the accumulated shapes.
            scratchList.clear()
        }
    }

    protected open fun drawShapesToTexture(dc: DrawContext, terrain: DrawableTerrain): Int {
        // Shapes have been accumulated in the draw context's scratch list.
        val scratchList = dc.scratchList.toTypedArray()

        // The terrain's sector defines the geographic region in which to draw.
        val terrainSector = terrain.sector

        // Keep track of the number of shapes drawn into the texture.
        var shapeCount = 0
        val program = drawState.program ?: return 0
        try {
            val framebuffer = dc.scratchFramebuffer
            if (!framebuffer.bindFramebuffer(dc)) return 0 // framebuffer failed to bind

            // Clear the framebuffer and disable the depth test.
            val colorAttachment = framebuffer.getAttachedTexture(GL_COLOR_ATTACHMENT0)
            dc.gl.viewport(0, 0, colorAttachment.width, colorAttachment.height)
            dc.gl.clear(GL_COLOR_BUFFER_BIT)
            dc.gl.disable(GL_DEPTH_TEST)

            // Use the draw context's pick mode.
            program.enablePickMode(dc.isPickMode)

            // Compute the tile common matrix that transforms geographic coordinates to texture fragments appropriate
            // for the terrain sector.
            // TODO capture this in a method on Matrix4
            textureMvpMatrix.setToIdentity()
            textureMvpMatrix.multiplyByTranslation(-1.0, -1.0, 0.0)
            textureMvpMatrix.multiplyByScale(
                2.0 / terrainSector.deltaLongitude.inDegrees,
                2.0 / terrainSector.deltaLatitude.inDegrees,
                0.0
            )
            textureMvpMatrix.multiplyByTranslation(
                -terrainSector.minLongitude.inDegrees,
                -terrainSector.minLatitude.inDegrees,
                0.0
            )
            program.loadClipDistance((textureMvpMatrix.m[11] / (textureMvpMatrix.m[10] - 1.0)).toFloat() / 2.0f) // set value here, but matrix is orthographic and shader clipping won't work as vertices projected orthographically always have .w == 1
            program.loadScreen(colorAttachment.width.toFloat(), colorAttachment.height.toFloat())
            for (element in scratchList) {
                // Get the shape.
                val shape = element as DrawableSurfaceShape
                if (shape.offset != terrain.offset || !shape.sector.intersectsOrNextTo(terrainSector)) continue
                if (!shape.drawState.vertexState.bind(dc)) continue
                if (shape.drawState.elementBuffer?.bindBuffer(dc) != true) continue  // element buffer unspecified or failed to bind

                program.enableLinesMode(shape.drawState.isLine)
                program.enableVertexColorAndWidth(shape.drawState.isStatic)
                // Transform local shape coordinates to texture fragments appropriate for the terrain sector.
                mvpMatrix.copy(textureMvpMatrix)
                mvpMatrix.multiplyByTranslation(
                    shape.drawState.vertexOrigin.x,
                    shape.drawState.vertexOrigin.y,
                    shape.drawState.vertexOrigin.z
                )
                program.loadModelviewProjection(mvpMatrix)
                program.loadPickIdOffset(shape.drawState.pickIdOffset)

                // Draw the specified primitives to the framebuffer texture.
                for (primIdx in 0 until shape.drawState.primCount) {
                    val prim = shape.drawState.prims[primIdx]
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
                        // prevent "RENDER WARNING: there is no texture bound to unit 0"
                        dc.defaultTexture.bindTexture(dc)
                    }
                    if (prim.mode == GL_LINES || prim.mode == GL_LINE_STRIP || prim.mode == GL_LINE_LOOP) dc.gl.lineWidth(
                        prim.lineWidth
                    )
                    dc.gl.drawElements(prim.mode, prim.count, prim.type, prim.offset)
                }

                // Accumulate the number of shapes drawn into the texture.
                shapeCount++

                shape.drawState.vertexState.unbind(dc)
            }
        } finally {
            // Restore the default WorldWind OpenGL state.
            dc.bindFramebuffer(KglFramebuffer.NONE)
            dc.gl.viewport(dc.viewport.x, dc.viewport.y, dc.viewport.width, dc.viewport.height)
            dc.gl.enable(GL_DEPTH_TEST)
            dc.gl.lineWidth(1f)
        }
        return shapeCount
    }

    protected open fun drawTextureToTerrain(dc: DrawContext, terrain: DrawableTerrain) {
        val program = drawState.program ?: return
        try {
            dc.gl.enableVertexAttribArray(3 /*vertexTexCoord*/)
            if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) return // terrain vertex attribute failed to bind
            if (!terrain.useVertexTexCoordAttrib(dc, 3 /*vertexTexCoord*/)) return // terrain vertex attribute failed to bind

            val colorAttachment = dc.scratchFramebuffer.getAttachedTexture(GL_COLOR_ATTACHMENT0)
            if (!colorAttachment.bindTexture(dc)) return  // framebuffer texture failed to bind

            // Configure the program to draw texture fragments unmodified and aligned with the terrain.
            // TODO consolidate pickMode and enableTexture into a single textureMode
            // TODO it's confusing that pickMode must be disabled during surface shape render-to-texture
            program.enableLinesMode(false)
            program.enableVertexColorAndWidth(false)
            program.enablePickMode(false)
            program.enableTexture(true)
            program.loadTexCoordMatrix(identityMatrix3)
            program.loadColor(color)
            program.loadOpacity(opacity)
            program.loadPickIdOffset(0)
            program.loadScreen(dc.viewport.width.toFloat(), dc.viewport.height.toFloat())

            // Use the draw context's modelview projection matrix, transformed to terrain local coordinates.
            val terrainOrigin = terrain.vertexOrigin
            mvpMatrix.copy(dc.modelviewProjection)
            mvpMatrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
            program.loadModelviewProjection(mvpMatrix)
            program.loadClipDistance(0.0f)

            // Draw the terrain as triangles.
            terrain.drawTriangles(dc)
        } finally {
            dc.gl.disableVertexAttribArray(3 /*vertexTexCoord*/)
            // Unbind color attachment texture to avoid feedback loop
            dc.defaultTexture.bindTexture(dc)
//            dc.bindTexture(KglTexture.NONE)
        }
    }
}