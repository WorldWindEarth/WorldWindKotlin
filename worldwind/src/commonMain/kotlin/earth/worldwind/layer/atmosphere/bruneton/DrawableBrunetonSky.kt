/*
 * Adapted from ebruneton/precomputed_atmospheric_scattering, BSD-3-Clause,
 * Copyright (c) 2017 Eric Bruneton.
 *
 * Renders the sky dome via the Bruneton precomputed-atmospheric-scattering runtime path.
 * Same sphere mesh as the legacy [earth.worldwind.layer.atmosphere.DrawableSkyAtmosphere];
 * just swaps the program and binds the three LUT textures from
 * [BrunetonAtmosphereModel].
 */
package earth.worldwind.layer.atmosphere.bruneton

import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.Drawable
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.atmosphere.bruneton.programs.BrunetonSkyProgram
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.*

internal class DrawableBrunetonSky : Drawable {

    var vertexPoints: BufferObject? = null
    var triStripElements: BufferObject? = null
    val sunDirection = Vec3()
    var program: BrunetonSkyProgram? = null
    var model: BrunetonAtmosphereModel? = null
    var exposure: Float = 10f

    private var pool: Pool<DrawableBrunetonSky>? = null

    companion object {
        val KEY = DrawableBrunetonSky::class

        fun obtain(pool: Pool<DrawableBrunetonSky>): DrawableBrunetonSky {
            val instance = pool.acquire() ?: DrawableBrunetonSky()
            instance.pool = pool
            return instance
        }
    }

    override fun recycle() {
        program = null
        model = null
        vertexPoints = null
        triStripElements = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        val program = program ?: return
        val model = model ?: return
        if (!model.isPrecomputed) return // first frame after enable; legacy path will fill in

        if (!program.useProgram(dc)) return
        if (vertexPoints?.bindBuffer(dc) != true) return
        val triStripElements = triStripElements ?: return
        if (!triStripElements.bindBuffer(dc)) return

        val gl = dc.gl

        // Bind the three LUTs to texture units 0/1/2 (matching the program's sampler defaults).
        gl.activeTexture(GL_TEXTURE0)
        gl.bindTexture(GL_TEXTURE_2D, model.transmittance())
        gl.activeTexture(GL_TEXTURE1)
        gl.bindTexture(GL_TEXTURE_3D, model.scattering())
        gl.activeTexture(GL_TEXTURE2)
        gl.bindTexture(GL_TEXTURE_3D, model.mieScattering())
        // Restore active unit so subsequent legacy programs land on TEXTURE0 by default.
        gl.activeTexture(GL_TEXTURE0)

        program.loadEyePoint(dc.eyePoint)
        program.loadSunDirection(sunDirection)
        program.loadExposure(exposure)
        program.loadModelviewProjection(dc.modelviewProjection)

        gl.vertexAttribPointer(0 /* vertexPoint */, 3, GL_FLOAT, false, 0, 0)

        // Draw the inside of the sky without writing depth (same convention as the legacy
        // DrawableSkyAtmosphere). Front-face winding is CW because the sphere mesh's
        // triangle strip is wound for outward normals; we render the inside.
        gl.depthMask(false)
        gl.frontFace(GL_CW)
        gl.drawElements(GL_TRIANGLE_STRIP, triStripElements.byteCount / 2, GL_UNSIGNED_SHORT, 0)

        // Restore default WorldWind GL state.
        gl.depthMask(true)
        gl.frontFace(GL_CCW)

        // Unbind 3D textures so subsequent draws don't see them on units 1/2 by accident.
        gl.activeTexture(GL_TEXTURE1)
        gl.bindTexture(GL_TEXTURE_3D, KglTexture.NONE)
        gl.activeTexture(GL_TEXTURE2)
        gl.bindTexture(GL_TEXTURE_3D, KglTexture.NONE)
        gl.activeTexture(GL_TEXTURE0)
    }
}
