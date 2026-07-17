package earth.worldwind.draw

import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Sector
import earth.worldwind.globe.Globe
import earth.worldwind.layer.shadow.applyShadowReceiverUniforms
import earth.worldwind.render.Color
import earth.worldwind.render.Texture
import earth.worldwind.render.program.TriangleShaderProgram
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
    private val modelMatrix = Matrix4()
    private val filteredShapes = ArrayList<DrawableSurfaceShape>()

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
        val program = drawState.program as? TriangleShaderProgram ?: return null

        // The terrain's sector defines the geographic region in which to draw.
        val terrainSector = terrain.sector

        // Filter shapes that intersect current terrain tile and globe offset
        val scratchList = filteredShapes
        scratchList.clear()
        for (idx in dc.scratchList.indices) {
            val shape = dc.scratchList[idx] as DrawableSurfaceShape
            if (shape.offset == terrain.offset && shape.sector.intersectsOrNextTo(terrainSector)) scratchList.add(shape)
        }
        if (scratchList.isEmpty()) return null // Nothing to draw

        // Whether every intersecting shape's geometry buffer has uploaded (a not-yet-uploaded shape is
        // skipped by the draw loop below, leaving the base layer showing through where it should paint).
        var allBuffersLoaded = true
        for (idx in scratchList.indices) {
            val ds = scratchList[idx].drawState
            if (ds.vertexBuffer?.isLoaded() == false || ds.elementBuffer?.isLoaded() == false) {
                allBuffersLoaded = false; break
            }
        }

        // Per-terrain-tile RTT cache, keyed by the tile (sector + globe offset), NOT by tile+shapes, and
        // bounded by bytes ([DrawContext.surfaceShapeTiles]). A tile reuses its last fully-composited
        // texture while its shapes reassemble — retain-last-good, the draping analog of MapLibre's
        // findLoadedParent — so a pan never flashes the base layer through, and the cache holds one texture
        // per visible tile instead of churning a new 4 MB texture per shape-set change (which filled the
        // old count-bounded cache and OOM'd).
        val useCache = !dc.isPickMode && !isDynamic
        val tileKey = 31 * terrainSector.hashCode() + terrain.offset.ordinal
        var contentHash = 0
        if (useCache) {
            for (idx in scratchList.indices) contentHash = 31 * contentHash + scratchList[idx].textureHash()
            dc.surfaceShapeTiles[tileKey]?.let { cached ->
                if (cached.contentHash == contentHash) return cached.texture // up to date for this tile
                if (!allBuffersLoaded) return cached.texture                 // reassembling → keep last good
            }
        }
        // Only a complete render (all buffers uploaded) yields a storable texture; pick / dynamic / partial
        // renders go through the shared scratch attachment and are not cached.
        val cacheable = useCache && allBuffersLoaded

        // Redraw shapes to texture and put in cache if required
        val framebuffer = dc.scratchFramebuffer
        // Render into MSAA when available (GLES3+/GL3+/WebGL2), then resolve into the
        // single-sample texture below. `null` means single-sample fallback (WebGL1).
        val multisampleFramebuffer = if (dc.isPickMode) null else dc.multisampleFramebuffer
        val colorAttachment = framebuffer.getAttachedTexture(GL_COLOR_ATTACHMENT0)
        val texture = if (!cacheable) colorAttachment
        else Texture(colorAttachment.width, colorAttachment.height, GL_RGBA, GL_UNSIGNED_BYTE, true)
        // Restore the caller's binding after the offscreen pass, not always NONE — pick mode
        // expects the pick FBO to remain bound between drawables.
        val previousFramebuffer = dc.currentFramebuffer
        try {
            // Attach the cache texture as the resolve target before binding the draw FBO.
            if (cacheable) framebuffer.attachTexture(dc, texture, GL_COLOR_ATTACHMENT0)
            val drawFramebuffer = multisampleFramebuffer ?: framebuffer
            if (!drawFramebuffer.bindFramebuffer(dc)) return null // framebuffer failed to bind

            // Clear the framebuffer and disable the depth test.
            dc.gl.viewport(0, 0, colorAttachment.width, colorAttachment.height)
            dc.gl.clear(GL_COLOR_BUFFER_BIT)
            dc.gl.disable(GL_DEPTH_TEST)

            // Use the draw context's pick mode.
            program.enablePickMode(dc.isPickMode)
            // Surface shapes are flat top-down rasterizations — Lambert shading is
            // meaningless here (no real surface normal to drive it; dFdx/dFdy in a
            // texture-space orthographic projection lights backwards). Always-off.
            // Without this, [TriangleShaderProgram.enableLighting]'s "skip-if-unchanged"
            // cache lets a previous lit-shape draw (e.g. [OsmBuildingsLayer]) leave
            // lighting enabled, dimming every subsequent surface line/poly to ~35%
            // intensity — visually reads as "all lines black" in MVT vector layers.
            program.enableLighting(false)
            // The rasterize-to-texture pass uses a texture-space orthographic [mvpMatrix],
            // so `gl_Position.w == 1` and [viewDepth] / [worldPos] aren't meaningful for a
            // shadow lookup. Surface shapes pick up shadow attenuation in the composite pass
            // ([drawTextureToTerrain]) where the terrain's vertex position drives the
            // receiver, not here. Route through the helper so its upload-stamp cache stays in
            // sync with [applyShadowId]; calling [loadShadowDisabled] directly desyncs it.
            dc.applyShadowReceiverUniforms(program, applyShadow = false)

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
                    }
                    dc.gl.drawElements(prim.mode, prim.count, prim.type, prim.offset)
                }
            }
            // Resolve MSAA into the single-sample texture the terrain sampler reads from.
            // No-op on the WebGL1 fallback (we already drew into `framebuffer` directly).
            multisampleFramebuffer?.resolveTo(dc, framebuffer)
            if (cacheable) dc.surfaceShapeTiles.put(tileKey, SurfaceTileTexture(texture, contentHash), texture.byteCount)
        } finally {
            if (cacheable) framebuffer.attachTexture(dc, colorAttachment, GL_COLOR_ATTACHMENT0)
            dc.bindFramebuffer(previousFramebuffer)
            dc.gl.viewport(dc.viewport.x, dc.viewport.y, dc.viewport.width, dc.viewport.height)
            dc.gl.enable(GL_DEPTH_TEST)
        }
        return texture
    }

    protected open fun drawTextureToTerrain(dc: DrawContext, terrain: DrawableTerrain, texture: Texture) {
        val program = drawState.program as? TriangleShaderProgram ?: return
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
            val lightingActive = drawState.enableLighting && !drawState.isLine && !dc.isPickMode
            program.enableLighting(lightingActive)
            if (lightingActive) {
                program.loadLightDirection(dc.lightDirection)
                program.loadUpDirection(dc.upDirection)
            }
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

            // Apply shadow attenuation in the composite-to-terrain pass so surface shapes pick
            // up the 3D-caster shadows the terrain underneath received. modelMatrix is only
            // sampled by the shadow-receiver path; skipped on no-shadow frames.
            dc.applyShadowReceiverUniforms(program)
            val shadowState = dc.shadowState
            if (shadowState != null) {
                modelMatrix.setToTranslation(
                    terrainOrigin.x - dc.eyePoint.x,
                    terrainOrigin.y - dc.eyePoint.y,
                    terrainOrigin.z - dc.eyePoint.z,
                )
                program.loadModelMatrix(modelMatrix)
            }
            // Terrain relief rides this pass (the rasterized geometry is the terrain mesh);
            // no isReady gate - relief is cascade-independent.
            val reliefActive = shadowState != null && !dc.isPickMode && shadowState.terrainLambert > 0f
            program.loadTerrainRelief(if (reliefActive) shadowState.terrainLambert else 0f, shadowState?.lightDirection)
            if (reliefActive && terrain.useVertexNormalAttrib(dc, 4 /*vertexNormal*/)) dc.gl.enableVertexAttribArray(4)

            // Draw the terrain as triangles.
            terrain.drawTriangles(dc)
        } finally {
            // Reset so subsequent shape draws with this shared program stay unshaded.
            program.loadTerrainRelief(0f, null)
            dc.gl.disableVertexAttribArray(4)
            // Unbind color attachment texture to avoid feedback loop
            dc.defaultTexture.bindTexture(dc)
        }
    }
}