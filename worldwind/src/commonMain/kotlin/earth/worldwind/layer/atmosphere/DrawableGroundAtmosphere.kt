package earth.worldwind.layer.atmosphere

import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.Drawable
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.atmosphere.AbstractAtmosphereProgram.FragMode.*
import earth.worldwind.render.Texture
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.*
import kotlin.jvm.JvmStatic

open class DrawableGroundAtmosphere : Drawable {
    val lightDirection = Vec3()
    var globeRadius = 0.0
    var atmosphereAltitude = 0.0
    var program: GroundProgram? = null
    var nightTexture: Texture? = null
    protected val mvpMatrix = Matrix4()
    protected val texCoordMatrix = Matrix3()
    protected val fullSphereSector = Sector().setFullSphere()
    private var pool: Pool<DrawableGroundAtmosphere>? = null

    companion object {
        @JvmStatic
        fun obtain(pool: Pool<DrawableGroundAtmosphere>): DrawableGroundAtmosphere {
            val instance = pool.acquire() ?: DrawableGroundAtmosphere()  // get an instance from the pool
            instance.pool = pool
            return instance
        }
    }

    override fun recycle() {
        program = null
        nightTexture = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        val program = program ?: return  // program unspecified
        if (!program.useProgram(dc)) return  // program failed to build

        // Use the render context's globe radius and atmosphere altitude.
        program.loadAtmosphereParams(globeRadius, atmosphereAltitude)

        // Use the draw context's eye point.
        program.loadEyePoint(dc.eyePoint)

        // Use this layer's light direction.
        program.loadLightDirection(lightDirection)

        // Set up to use the shared tile tex coord attributes.
        dc.gl.enableVertexAttribArray(1)

        // Attempt to bind the night side texture to multi-texture unit 0.
        dc.activeTextureUnit(GL_TEXTURE0)
        val nightTexture = nightTexture
        val textureBound = nightTexture?.bindTexture(dc) == true
        for (idx in 0 until dc.drawableTerrainCount) {
            // Get the drawable terrain associated with the draw context.
            val terrain = dc.getDrawableTerrain(idx)

            // Use the terrain's vertex point attribute and vertex tex coord attribute.
            if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/) ||
                !terrain.useVertexTexCoordAttrib(dc, 1 /*vertexTexCoord*/)
            ) continue  // vertex buffer failed to bind

            // Use the vertex origin for the terrain.
            val terrainOrigin = terrain.vertexOrigin
            program.loadVertexOrigin(terrainOrigin)

            // Use the draw context's modelview projection matrix, transformed to terrain local coordinates.
            mvpMatrix.copy(dc.modelviewProjection)
            mvpMatrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
            program.loadModelviewProjection(mvpMatrix)

            // Use a tex coord matrix that registers the night texture correctly on each terrain.
            if (textureBound && nightTexture != null) {
                texCoordMatrix.copy(nightTexture.coordTransform)
                texCoordMatrix.multiplyByTileTransform(terrain.sector, fullSphereSector)
                program.loadTexCoordMatrix(texCoordMatrix)
            }

            // Draw the terrain as triangles, multiplying the current fragment color by the program's secondary color.
            program.loadFragMode(SECONDARY)
            dc.gl.blendFunc(GL_DST_COLOR, GL_ZERO)
            terrain.drawTriangles(dc)

            // Draw the terrain as triangles, adding the current fragment color to the program's primary color.
            program.loadFragMode(if (textureBound) PRIMARY_TEX_BLEND else PRIMARY)
            dc.gl.blendFunc(GL_ONE, GL_ONE)
            terrain.drawTriangles(dc)
        }

        // Restore the default WorldWind OpenGL state.
        dc.gl.blendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        dc.gl.disableVertexAttribArray(1)
    }
}