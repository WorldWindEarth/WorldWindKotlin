package earth.worldwind.draw

import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Sector
import earth.worldwind.globe.Globe
import earth.worldwind.render.Color
import earth.worldwind.render.Texture
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.*
import kotlin.jvm.JvmStatic

open class DrawableSurfaceShape protected constructor(): Drawable {
    var offset = Globe.Offset.Center
    val sector = Sector()
    val drawState = DrawShapeState()
    var version = 0
    var isDynamic = false
    private var hash = 0
    private var pool: Pool<DrawableSurfaceShape>? = null
    private val mvpMatrix = Matrix4()
    private val textureMvpMatrix = Matrix4()

    companion object {
        val KEY = DrawableSurfaceShape::class
        private val identityMatrix3 = Matrix3()
        private val color = Color()
        private var opacity = 1.0f

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
        hash = 0
    }

    override fun draw(dc: DrawContext) {
        val program = drawState.program ?: return // program unspecified
        if (!program.useProgram(dc)) return // program failed to build

        // Make multi-texture unit 0 active.
        dc.activeTextureUnit(GL_TEXTURE0)

        // Set up to use vertex tex coord attributes.
        dc.gl.enableVertexAttribArray(1 /*vertexTexCoord*/)
        dc.gl.enableVertexAttribArray(2 /*vertexTexCoord*/)
        dc.gl.enableVertexAttribArray(3 /*vertexTexCoord*/)

        // Accumulate shapes in the draw context's scratch list.
        // TODO accumulate in a geospatial quadtree
        val scratchList = dc.scratchList
        try {
            // Add this shape.
            scratchList.add(this)

            // Add all shapes that are contiguous in the drawable queue.
            while (true) {
                val next = dc.peekDrawable() ?: break
                // check if the drawable at the front of the queue can be batched
                if (next !is DrawableSurfaceShape || next.isDynamic != isDynamic) break
                dc.pollDrawable() // take it off the queue
                scratchList.add(next)
            }

            // Draw the accumulated shapes on each drawable terrain.
            for (idx in 0 until dc.drawableTerrainCount) {
                // Get the drawable terrain associated with the draw context.
                val terrain = dc.getDrawableTerrain(idx)
                // Draw the accumulated surface shapes to a texture representing the terrain's sector.
                drawShapesToTexture(dc, terrain)?.let { texture ->
                    // Draw the texture containing the rasterized shapes onto the terrain geometry.
                    drawTextureToTerrain(dc, terrain, texture)
                }
            }
        } finally {
            // Clear the accumulated shapes.
            scratchList.clear()
            // Restore the default WorldWind OpenGL state.
            dc.gl.disableVertexAttribArray(1 /*vertexTexCoord*/)
            dc.gl.disableVertexAttribArray(2 /*vertexTexCoord*/)
            dc.gl.disableVertexAttribArray(3 /*vertexTexCoord*/)
        }
    }

    protected open fun textureHash(): Int {
        if (hash == 0) {
            hash = 31 * version + drawState.isLine.hashCode()
            for (i in 0 until drawState.primCount) {
                val prim = drawState.prims[i]
                hash = 31 * hash + prim.color.hashCode()
                hash = 31 * hash + prim.opacity.hashCode()
                hash = 31 * hash + prim.lineWidth.hashCode()
                hash = 31 * hash + prim.texture.hashCode()
                hash = 31 * hash + prim.textureLod
            }
        }
        return hash
    }

    protected open fun drawShapesToTexture(dc: DrawContext, terrain: DrawableTerrain): Texture? {
        val program = drawState.program ?: return null

        // The terrain's sector defines the geographic region in which to draw.
        val terrainSector = terrain.sector

        // Filter shapes that intersect current terrain tile and globe offset
        val scratchList = mutableListOf<DrawableSurfaceShape>()
        for (idx in dc.scratchList.indices) {
            val shape = dc.scratchList[idx] as DrawableSurfaceShape
            if (shape.offset == terrain.offset && shape.sector.intersectsOrNextTo(terrainSector)) scratchList.add(shape)
        }
        if (scratchList.isEmpty()) return null // Nothing to draw

        var hash = 0
        val useCache = !dc.isPickMode && !isDynamic // Use single color attachment in pick mode and for dynamic shapes
        if (useCache) {
            // Calculate a texture cache key for this terrain tile and shapes batch
            hash = terrainSector.hashCode()
            for (idx in scratchList.indices) hash = 31 * hash + scratchList[idx].textureHash()

            // Use cached texture
            val cachedTexture = dc.texturesCache[hash]
            if (cachedTexture != null) return cachedTexture
        }

        // Redraw shapes to texture and put in cache if required
        val framebuffer = dc.scratchFramebuffer
        val colorAttachment = framebuffer.getAttachedTexture(GL_COLOR_ATTACHMENT0)
        val texture = if (!useCache) colorAttachment
        else Texture(colorAttachment.width, colorAttachment.height, GL_RGBA, GL_UNSIGNED_BYTE, true)
        try {
            if (!framebuffer.bindFramebuffer(dc)) return null // framebuffer failed to bind
            if (useCache) framebuffer.attachTexture(dc, texture, GL_COLOR_ATTACHMENT0)

            // Clear the framebuffer and disable the depth test.
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
            for (idx in scratchList.indices) {
                val shape = scratchList[idx]
                if (shape.drawState.vertexBuffer?.bindBuffer(dc) != true) continue  // vertex buffer unspecified or failed to bind
                if (shape.drawState.elementBuffer?.bindBuffer(dc) != true) continue  // element buffer unspecified or failed to bind

                // Transform local shape coordinates to texture fragments appropriate for the terrain sector.
                mvpMatrix.copy(textureMvpMatrix)
                mvpMatrix.multiplyByTranslation(
                    shape.drawState.vertexOrigin.x,
                    shape.drawState.vertexOrigin.y,
                    shape.drawState.vertexOrigin.z
                )
                program.loadModelviewProjection(mvpMatrix)
                program.enableOneVertexMode(!shape.drawState.isLine)
                if (shape.drawState.isLine) {
                    dc.gl.vertexAttribPointer(0 /*pointA*/, 4, GL_FLOAT, false, 20, 0)
                    dc.gl.vertexAttribPointer(1 /*pointB*/, 4, GL_FLOAT, false, 20, 80)
                    dc.gl.vertexAttribPointer(2 /*pointC*/, 4, GL_FLOAT, false, 20, 160)
                    dc.gl.vertexAttribPointer(3 /*vertexTexCoord*/, 1, GL_FLOAT, false, 20, 96)
                } else {
                    // Use the shape's vertex point attribute.
                    dc.gl.vertexAttribPointer(0 /*vertexPoint*/, 3, GL_FLOAT, false, shape.drawState.vertexStride, 0)
                    dc.gl.vertexAttribPointer(1 /*vertexPoint*/, 3, GL_FLOAT, false, shape.drawState.vertexStride, 0)
                    dc.gl.vertexAttribPointer(2 /*vertexPoint*/, 3, GL_FLOAT, false, shape.drawState.vertexStride, 0)
                }
                // Draw the specified primitives to the framebuffer texture.
                for (primIdx in 0 until shape.drawState.primCount) {
                    val prim = shape.drawState.prims[primIdx]
                    program.loadColor(prim.color)
                    program.loadOpacity(prim.opacity)
                    if (prim.texture?.bindTexture(dc) == true) {
                        program.loadTexCoordMatrix(prim.texCoordMatrix)
                        program.enableTexture(true)
                    } else {
                        program.enableTexture(false)
                        // prevent "RENDER WARNING: there is no texture bound to unit 0"
                        dc.defaultTexture.bindTexture(dc)
                    }
                    if (shape.drawState.isLine) {
                        program.loadLineWidth(prim.lineWidth)
                    } else {
                        dc.gl.vertexAttribPointer(
                            3 /*vertexTexCoord*/,
                            prim.texCoordAttrib.size,
                            GL_FLOAT,
                            false,
                            shape.drawState.vertexStride,
                            prim.texCoordAttrib.offset
                        )
                        dc.gl.lineWidth(prim.lineWidth)
                    }
                    dc.gl.drawElements(prim.mode, prim.count, prim.type, prim.offset)
                }
            }
            if (useCache) dc.texturesCache.put(hash, texture, 1)
        } finally {
            if (useCache) framebuffer.attachTexture(dc, colorAttachment, GL_COLOR_ATTACHMENT0)
            // Restore the default WorldWind OpenGL state.
            dc.bindFramebuffer(KglFramebuffer.NONE)
            dc.gl.viewport(dc.viewport.x, dc.viewport.y, dc.viewport.width, dc.viewport.height)
            dc.gl.enable(GL_DEPTH_TEST)
            dc.gl.lineWidth(1f)
        }
        return texture
    }

    protected open fun drawTextureToTerrain(dc: DrawContext, terrain: DrawableTerrain, texture: Texture) {
        val program = drawState.program ?: return
        try {
            if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) return // terrain vertex attribute failed to bind
            if (!terrain.useVertexPointAttrib(dc, 1 /*vertexPoint*/)) return // terrain vertex attribute failed to bind
            if (!terrain.useVertexPointAttrib(dc, 2 /*vertexPoint*/)) return // terrain vertex attribute failed to bind
            if (!terrain.useVertexTexCoordAttrib(dc, 3 /*vertexTexCoord*/)) return // terrain vertex attribute failed to bind
            if (!texture.bindTexture(dc)) return // texture failed to bind

            // Configure the program to draw texture fragments unmodified and aligned with the terrain.
            // TODO consolidate pickMode and enableTexture into a single textureMode
            // TODO it's confusing that pickMode must be disabled during surface shape render-to-texture
            program.enableOneVertexMode(true)
            program.enablePickMode(false)
            program.enableTexture(true)
            program.loadTexCoordMatrix(identityMatrix3)
            program.loadColor(color)
            program.loadOpacity(opacity)
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
            // Unbind color attachment texture to avoid feedback loop
            dc.defaultTexture.bindTexture(dc)
        }
    }
}