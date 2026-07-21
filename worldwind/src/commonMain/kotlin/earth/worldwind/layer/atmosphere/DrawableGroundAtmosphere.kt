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
    /** Eye altitude (m) at/below which the ground atmosphere is off, and at/above which it's full. */
    var fadeNearAltitude = 0.0
    var fadeFarAltitude = 0.0
    /** 1 = sun-path day/night dimming active (time-of-day sun), 0 = none (custom light direction). */
    var dayNightStrength = 1f
    var program: GroundProgram? = null
    var nightTexture: Texture? = null
    protected val mvpMatrix = Matrix4()
    protected val texCoordMatrix = Matrix3()
    protected val fullSphereSector = Sector().setFullSphere()
    private var pool: Pool<DrawableGroundAtmosphere>? = null

    companion object {
        val KEY = DrawableGroundAtmosphere::class

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

        // Fade the ground atmosphere with eye altitude: full from space, off near the surface
        // where the atmospheric path is negligible and the single-scatter model over-reddens
        // the terrain. smoothstep between the two thresholds; identity (1.0) preserves the
        // legacy from-space look.
        val eyeAltitude = dc.eyePoint.magnitude - globeRadius
        val span = fadeFarAltitude - fadeNearAltitude
        val t = if (span > 0.0) ((eyeAltitude - fadeNearAltitude) / span).coerceIn(0.0, 1.0) else 1.0
        program.loadGroundStrength((t * t * (3.0 - 2.0 * t)).toFloat())

        // Disable the residual sun-path dimming when no time-of-day sun is active.
        program.loadDayNightStrength(dayNightStrength)

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
            if (textureBound) {
                texCoordMatrix.copy(nightTexture.coordTransform)
                texCoordMatrix.multiplyByTileTransform(terrain.sector, fullSphereSector)
                program.loadTexCoordMatrix(texCoordMatrix)
            } else {
                // prevent "RENDER WARNING: there is no texture bound to unit 0"
                dc.defaultTexture.bindTexture(dc)
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