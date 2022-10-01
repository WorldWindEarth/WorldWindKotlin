package earth.worldwind.draw

import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Sector
import earth.worldwind.render.Color
import earth.worldwind.render.Texture
import earth.worldwind.render.program.SurfaceTextureProgram
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_TEXTURE0
import kotlin.jvm.JvmStatic

open class DrawableSurfaceTexture protected constructor(): Drawable {
    val sector = Sector()
    val color = Color()
    val texCoordMatrix = Matrix3()
    var texture: Texture? = null
    var program: SurfaceTextureProgram? = null
    private var pool: Pool<DrawableSurfaceTexture>? = null

    companion object {
        @JvmStatic
        fun obtain(pool: Pool<DrawableSurfaceTexture>): DrawableSurfaceTexture {
            val instance = pool.acquire() ?: DrawableSurfaceTexture()
            instance.pool = pool
            return instance
        }
    }

    fun set(
        program: SurfaceTextureProgram?, sector: Sector?, texture: Texture?, texCoordMatrix: Matrix3?
    ) = apply {
        if (sector != null) this.sector.copy(sector) else this.sector.setEmpty()
        this.color.set(1f, 1f, 1f, 1f)
        if (texCoordMatrix != null) this.texCoordMatrix.copy(texCoordMatrix) else this.texCoordMatrix.setToIdentity()
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

        // Enable the program to display surface textures from multi-texture unit 0.
        program.enableTexture(true)
        dc.activeTextureUnit(GL_TEXTURE0)

        // Set up to use vertex tex coord attributes.
        dc.gl.enableVertexAttribArray(1)

        // Surface textures have been accumulated in the draw context's scratch list.
        val scratchList = dc.scratchList
        for (idx in 0 until dc.drawableTerrainCount) {
            // Get the drawable terrain associated with the draw context.
            val terrain = dc.getDrawableTerrain(idx)

            // Get the terrain's attributes, and keep a flag to ensure we apply the terrain's attributes at most once.
            val terrainSector = terrain.sector
            val terrainOrigin = terrain.vertexOrigin
            var usingTerrainAttrs = false
            for (jIdx in 0 until scratchList.size) {
                // Get the surface texture and its sector.
                val texture = scratchList[jIdx] as DrawableSurfaceTexture
                val textureSector = texture.sector
                if (!textureSector.intersects(terrainSector)) continue  // texture does not intersect the terrain
                if (!texture.bindTexture(dc)) continue  // texture failed to bind

                // Use the terrain's vertex point attribute and vertex tex coord attribute.
                if (!usingTerrainAttrs &&
                    terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/) &&
                    terrain.useVertexTexCoordAttrib(dc, 1 /*vertexTexCoord*/)
                ) {
                    // Suppress subsequent tile state application until the next terrain.
                    usingTerrainAttrs = true
                    // Use the draw context's modelview projection matrix, transformed to terrain local coordinates.
                    program.mvpMatrix.copy(dc.modelviewProjection)
                    program.mvpMatrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
                    program.loadModelviewProjection()
                }
                if (!usingTerrainAttrs) continue  // terrain vertex attribute failed to bind

                // Use tex coord matrices that register the surface texture correctly and mask terrain fragments that
                // fall outside the surface texture's sector.
                program.texCoordMatrix[0].copy(texture.texCoordMatrix)
                program.texCoordMatrix[0].multiplyByTileTransform(terrainSector, textureSector)
                program.texCoordMatrix[1].setToTileTransform(terrainSector, textureSector)
                program.loadTexCoordMatrix()

                // Use the surface texture's RGBA color.
                program.loadColor(texture.color)

                // Draw the terrain as triangles.
                terrain.drawTriangles(dc)
            }
        }

        // Restore the default WorldWind OpenGL state.
        dc.gl.disableVertexAttribArray(1)
    }

    protected open fun canBatchWith(that: Drawable) = that is DrawableSurfaceTexture && program === that.program

    private fun bindTexture(dc: DrawContext) = texture?.bindTexture(dc) == true
}