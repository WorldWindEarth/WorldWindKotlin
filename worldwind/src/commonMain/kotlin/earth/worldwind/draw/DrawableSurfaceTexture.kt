package earth.worldwind.draw

import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Sector
import earth.worldwind.globe.Globe
import earth.worldwind.layer.shadow.applyShadowReceiverUniforms
import earth.worldwind.render.Color
import earth.worldwind.render.Texture
import earth.worldwind.render.program.SurfaceTextureProgram
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_TEXTURE0
import kotlin.jvm.JvmStatic

open class DrawableSurfaceTexture protected constructor(): Drawable {
    var offset = Globe.Offset.Center
    val textureSector = Sector()
    val terrainSector = Sector()
    val color = Color()
    var opacity = 1.0f
    val texCoordMatrix = Matrix3()
    var texture: Texture? = null
    var program: SurfaceTextureProgram? = null
    private var pool: Pool<DrawableSurfaceTexture>? = null

    companion object {
        val KEY = DrawableSurfaceTexture::class

        @JvmStatic
        fun obtain(pool: Pool<DrawableSurfaceTexture>): DrawableSurfaceTexture {
            val instance = pool.acquire() ?: DrawableSurfaceTexture()
            instance.pool = pool
            return instance
        }
    }

    /**
     * [texCoordMatrix] must be axis-aligned affine (scale + translation, e.g. a vertical flip);
     * the composite pack ignores rotation and shear terms.
     */
    fun set(
        program: SurfaceTextureProgram?, textureSector: Sector, opacity: Float, texture: Texture,
        texCoordMatrix: Matrix3, offset: Globe.Offset, terrainSector: Sector = textureSector
    ) = apply {
        this.offset = offset
        this.textureSector.copy(textureSector)
        this.terrainSector.copy(terrainSector)
        this.color.set(1f, 1f, 1f, 1f)
        this.opacity = opacity
        this.texCoordMatrix.copy(texCoordMatrix)
        this.texture = texture
        this.program = program
    }

    override fun recycle() {
        texture = null
        program = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        val program = program ?: return // program unspecified
        if (!program.useProgram(dc)) return // program failed to build

        // Accumulate surface textures in the draw context's scratch list.
        // TODO accumulate in a geospatial quadtree
        val scratchList = dc.scratchList
        try {
            // Add this surface texture.
            scratchList.add(this)

            // Add all surface textures that are contiguous in the drawable queue.
            while (true) {
                val next = dc.peekDrawable() ?: break
                if (!canBatchWith(next)) break // check if the drawable at the front of the queue can be batched
                dc.pollDrawable() // take it off the queue
                scratchList.add(next)
            }

            // Draw the accumulated surface textures.
            drawSurfaceTextures(dc)
        } finally {
            // Clear the accumulated surface textures.
            scratchList.clear()
        }
    }

    protected open fun drawSurfaceTextures(dc: DrawContext) {
        val program = program ?: return // program unspecified

        // Use the draw context's pick mode.
        program.enablePickMode(dc.isPickMode)

        // Bind the cascade shadow maps once per draw - they're per-frame state, not per-tile.
        // [applyShadowReceiverUniforms] no-ops in pick mode and on platforms that can't
        // run the depth-texture cascades, leaving [DrawableSurfaceTexture] free to render
        // terrain tiles in both cases without further branching here.
        dc.applyShadowReceiverUniforms(program)

        // Relief is cascade-independent: no isReady gate, so platforms where the depth
        // pipeline can't run (applyShadow stays 0) still get the hillshade.
        val shadowState = dc.shadowState
        val reliefActive = shadowState != null && !dc.isPickMode && shadowState.terrainLambert > 0f
        program.loadTerrainRelief(if (reliefActive) shadowState.terrainLambert else 0f, shadowState?.lightDirection)

        // Set up to use vertex tex coord attributes.
        dc.gl.enableVertexAttribArray(1)

        // Pick mode draws one texture per pass so each drawable keeps its own pick color;
        // normal mode composites a full batch of textures in a single geometry pass.
        val batchSize = if (dc.isPickMode) 1 else SurfaceTextureProgram.TEXTURE_UNITS.size

        // Surface textures have been accumulated in the draw context's scratch list.
        val scratchList = dc.scratchList
        for (idx in 0 until dc.drawableTerrainCount) {
            // Get the drawable terrain associated with the draw context.
            val terrain = dc.getDrawableTerrain(idx)

            // Get the terrain's attributes, and keep a flag to ensure we apply the terrain's attributes at most once.
            val terrainSector = terrain.sector
            val terrainOrigin = terrain.vertexOrigin
            var usingTerrainAttrs = false
            var count = 0
            for (texIdx in scratchList.indices) {
                // Get the surface texture and its sector.
                val texture = scratchList[texIdx] as DrawableSurfaceTexture
                if (texture.offset != terrain.offset || !texture.terrainSector.intersects(terrainSector)) continue

                // Use the terrain's vertex point attribute and vertex tex coord attribute.
                if (!usingTerrainAttrs) {
                    if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/) ||
                        !terrain.useVertexTexCoordAttrib(dc, 1 /*vertexTexCoord*/)
                    ) break // terrain vertex attribute failed to bind; skip this terrain tile
                    // Suppress subsequent tile state application until the next terrain.
                    usingTerrainAttrs = true
                    // Smooth mesh normal for terrain relief; the fragment shader degrades to
                    // flat shading if the attrib stays disabled (degenerate interpolant).
                    if (reliefActive) {
                        if (terrain.useVertexNormalAttrib(dc, 2 /*vertexNormal*/)) dc.gl.enableVertexAttribArray(2)
                        else dc.gl.disableVertexAttribArray(2)
                    }
                    // Use the draw context's modelview projection matrix, transformed to terrain local coordinates.
                    program.mvpMatrix.copy(dc.modelviewProjection)
                    program.mvpMatrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
                    program.loadModelviewProjection()
                    // Tile-local -> camera-relative translation for the shadow-receiver pass,
                    // differenced in double so the float32 uniform stays precise (a raw ECEF
                    // origin quantizes to ~0.5 m - see ShadowReceiverGlsl). Skipped on
                    // no-shadow frames since the fragment shader doesn't read worldPos there.
                    if (dc.shadowState != null) {
                        program.loadVertexOrigin(
                            (terrainOrigin.x - dc.eyePoint.x).toFloat(),
                            (terrainOrigin.y - dc.eyePoint.y).toFloat(),
                            (terrainOrigin.z - dc.eyePoint.z).toFloat(),
                        )
                    }
                }

                // Bind the texture to the batch's next imagery unit.
                dc.activeTextureUnit(GL_TEXTURE0 + SurfaceTextureProgram.TEXTURE_UNITS[count])
                if (!texture.bindTexture(dc)) continue // texture failed to bind

                // Pack the batch slot's transform, coverage rectangle and opacity.
                program.setBatchTexture(count, texture.texCoordMatrix, terrainSector, texture.textureSector, texture.opacity)

                // Pick mode keeps the per-drawable pick color.
                if (dc.isPickMode) program.loadColor(texture.color)

                if (++count == batchSize) {
                    // Batch full: composite it over the terrain tile in one geometry pass.
                    program.loadBatchTextures(count)
                    terrain.drawTriangles(dc)
                    count = 0
                }
            }
            if (count > 0) {
                // Composite the partial batch.
                program.loadBatchTextures(count)
                terrain.drawTriangles(dc)
            }
        }

        // Restore the default WorldWind OpenGL state.
        dc.activeTextureUnit(GL_TEXTURE0)
        dc.gl.disableVertexAttribArray(1)
        if (reliefActive) dc.gl.disableVertexAttribArray(2)
    }

    protected open fun canBatchWith(that: Drawable) = that is DrawableSurfaceTexture && program === that.program

    private fun bindTexture(dc: DrawContext) = texture?.bindTexture(dc) == true
}
