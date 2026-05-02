/*
 * Adapted from ebruneton/precomputed_atmospheric_scattering, BSD-3-Clause,
 * Copyright (c) 2017 Eric Bruneton.
 *
 * Renders Bruneton aerial perspective on top of WorldWind's already-drawn terrain. Same
 * two-pass scheme as the legacy [earth.worldwind.layer.atmosphere.DrawableGroundAtmosphere]:
 *   Pass 1: SECONDARY, blend = DST_COLOR × ZERO  → multiplies framebuffer ground texture by
 *           `transmittance × (sun + sky irradiance) / SOLAR_IRRADIANCE`. Carries day/night
 *           terminator and atmospheric attenuation over distance.
 *   Pass 2: PRIMARY (or PRIMARY_TEX_BLEND with night image), blend = ONE × ONE → adds the
 *           tonemapped in-scatter (atmospheric haze color) and optionally a night-emissive
 *           term gated to the dark side.
 *
 * Cascaded shadow plumbing is wired through [BrunetonGroundProgram]'s [ShadowReceiverProgram]
 * implementation; the per-frame state is bound here via [applyShadowReceiverUniforms].
 */
package earth.worldwind.layer.atmosphere.bruneton

import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.Drawable
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.atmosphere.bruneton.programs.BrunetonGroundProgram
import earth.worldwind.layer.shadow.applyShadowReceiverUniforms
import earth.worldwind.render.Texture
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.*

internal class DrawableBrunetonGround : Drawable {

    val sunDirection = Vec3()
    var program: BrunetonGroundProgram? = null
    var model: BrunetonAtmosphereModel? = null
    var nightTexture: Texture? = null
    var exposure: Float = 10f
    var groundExposure: Float = 10f
    var nightEmissive: Float = 1f

    private val mvpMatrix = Matrix4()
    private val texCoordMatrix = Matrix3()
    private val fullSphereSector = Sector().setFullSphere()
    private var pool: Pool<DrawableBrunetonGround>? = null

    companion object {
        val KEY = DrawableBrunetonGround::class

        fun obtain(pool: Pool<DrawableBrunetonGround>): DrawableBrunetonGround {
            val instance = pool.acquire() ?: DrawableBrunetonGround()
            instance.pool = pool
            return instance
        }
    }

    override fun recycle() {
        program = null
        model = null
        nightTexture = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        val program = program ?: return
        val model = model ?: return
        if (!model.isPrecomputed) return // first frame after enable; legacy path has the visuals
        if (!program.useProgram(dc)) return

        val gl = dc.gl

        // Sampler unit layout, matching the program's init bindings:
        //   0       = night image  (legacy convention; bound below per-frame)
        //   1, 2, 3 = shadow cascades 0/1/2 (bound by applyShadowReceiverUniforms)
        //   5       = transmittance LUT
        //   6       = irradiance LUT
        //   7       = scattering LUT
        //   8       = mie scattering LUT
        // Bound LUTs first so applyShadowReceiverUniforms below can run unaffected.
        gl.activeTexture(GL_TEXTURE5)
        gl.bindTexture(GL_TEXTURE_2D, model.transmittance())
        gl.activeTexture(GL_TEXTURE6)
        gl.bindTexture(GL_TEXTURE_2D, model.irradiance())
        gl.activeTexture(GL_TEXTURE7)
        gl.bindTexture(GL_TEXTURE_3D, model.scattering())
        gl.activeTexture(GL_TEXTURE8)
        gl.bindTexture(GL_TEXTURE_3D, model.mieScattering())
        gl.activeTexture(GL_TEXTURE0)

        program.loadEyePoint(dc.eyePoint)
        program.loadSunDirection(sunDirection)
        program.loadExposure(exposure)
        program.loadGroundExposure(groundExposure)
        program.loadNightEmissive(nightEmissive)

        // Bind cascade textures to units 1/2/3 and upload cascade matrices for the
        // SECONDARY pass's [computeRawShadowVisibility] sampler. No-op (uploads `applyShadow=0`)
        // when no [ShadowLayer] is active or the platform doesn't support shadow rendering;
        // in that case the shader's `applyShadow` branch elides the lookup and visibility
        // returns 1.0 (no darkening).
        dc.applyShadowReceiverUniforms(program)

        // Per-tile attribute layout matches the legacy ground program: location 0 = vertex
        // point, location 1 = vertex tex coord. Activate the tex-coord stream once.
        gl.enableVertexAttribArray(1)

        val nightTexture = this.nightTexture
        val nightBound = nightTexture?.bindTexture(dc) == true
        // TODO: re-enable the default-texture fallback bind once the Bruneton ground pipeline
        // is producing the expected output. Disabled while iterating because the unrelated
        // texture binding on unit 0 was confusing visual diffs against the reference.
        // if (!nightBound) dc.defaultTexture.bindTexture(dc) // avoid "no texture" warnings

        for (idx in 0 until dc.drawableTerrainCount) {
            val terrain = dc.getDrawableTerrain(idx)

            if (!terrain.useVertexPointAttrib(dc, 0 /* vertexPoint */) ||
                !terrain.useVertexTexCoordAttrib(dc, 1 /* vertexTexCoord */)
            ) continue

            val terrainOrigin = terrain.vertexOrigin
            program.loadVertexOrigin(terrainOrigin)

            mvpMatrix.copy(dc.modelviewProjection)
            mvpMatrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
            program.loadModelviewProjection(mvpMatrix)

            if (nightBound) {
                texCoordMatrix.copy(nightTexture!!.coordTransform)
                texCoordMatrix.multiplyByTileTransform(terrain.sector, fullSphereSector)
                program.loadTexCoordMatrix(texCoordMatrix)
            } else {
                texCoordMatrix.setToIdentity()
                program.loadTexCoordMatrix(texCoordMatrix)
            }

            // Pass 1: multiplicative attenuation × lighting (carries the terminator).
            program.loadFragMode(BrunetonGroundProgram.FRAGMODE_SECONDARY)
            gl.blendFunc(GL_DST_COLOR, GL_ZERO)
            terrain.drawTriangles(dc)

            // Pass 2: tonemapped in-scatter (haze) blended additively. Matches the
            // reference's linear accumulation `T·ground_radiance + in_scatter` followed by
            // the tonemap+gamma applied per-channel — splitting it into our two-pass scheme
            // approximates that with `(texture × secondary) + tonemap(in_scatter)`.
            program.loadFragMode(
                if (nightBound) BrunetonGroundProgram.FRAGMODE_PRIMARY_TEX_BLEND
                else BrunetonGroundProgram.FRAGMODE_PRIMARY
            )
            gl.blendFunc(GL_ONE, GL_ONE)
            terrain.drawTriangles(dc)
        }

        // Restore default WorldWind GL state: premultiplied-alpha blend, attribute 1 off,
        // 3D texture bindings cleared so subsequent draws don't see them on units 7 / 8.
        gl.blendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        gl.disableVertexAttribArray(1)
        gl.activeTexture(GL_TEXTURE7)
        gl.bindTexture(GL_TEXTURE_3D, KglTexture.NONE)
        gl.activeTexture(GL_TEXTURE8)
        gl.bindTexture(GL_TEXTURE_3D, KglTexture.NONE)
        gl.activeTexture(GL_TEXTURE0)
    }
}
