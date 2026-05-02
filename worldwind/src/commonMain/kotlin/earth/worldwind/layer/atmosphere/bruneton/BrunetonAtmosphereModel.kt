/*
 * Adapted from ebruneton/precomputed_atmospheric_scattering, BSD-3-Clause,
 * Copyright (c) 2017 Eric Bruneton.
 *
 * Owns the precomputed lookup textures for the Bruneton atmospheric scattering model and
 * drives the full reference precomputation pipeline (transmittance → direct irradiance →
 * single Rayleigh + Mie → N orders of multiple scattering). Lifecycle is RenderResource-
 * style: GL handles created lazily on first [precompute], reused across frames, dropped
 * via [release].
 *
 * Persistent LUTs (consumed at runtime):
 *  - [transmittance]    2D RGBA16F  transmittance to the atmosphere top
 *  - [irradiance]       2D RGBA16F  indirect (sky) irradiance on the ground
 *  - [scattering]       3D RGBA16F  single Rayleigh + N-order multi-scattering, phase-divided
 *  - [mieScattering]    3D RGBA16F  single Mie only
 *
 * Per-iteration scratch (released on [release]):
 *  - [deltaIrradiance]            2D, holds direct or current order's indirect
 *  - [deltaRayleigh] / [deltaMie] 3D, single-scattering inputs to scattering-density pass
 *  - [deltaScatteringDensity]     3D, source term for current order's multiple scattering
 *  - [deltaMultipleScattering]    3D, current order's multiple scattering before phase-divide
 */
package earth.worldwind.layer.atmosphere.bruneton

import earth.worldwind.draw.DrawContext
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.IRRADIANCE_TEXTURE_HEIGHT
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.IRRADIANCE_TEXTURE_WIDTH
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.NUM_SCATTERING_ORDERS
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.SCATTERING_TEXTURE_DEPTH
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.SCATTERING_TEXTURE_HEIGHT
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.SCATTERING_TEXTURE_WIDTH
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.TRANSMITTANCE_TEXTURE_HEIGHT
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.TRANSMITTANCE_TEXTURE_WIDTH
import earth.worldwind.layer.atmosphere.bruneton.programs.DirectIrradianceProgram
import earth.worldwind.layer.atmosphere.bruneton.programs.IndirectIrradianceProgram
import earth.worldwind.layer.atmosphere.bruneton.programs.MultipleScatteringProgram
import earth.worldwind.layer.atmosphere.bruneton.programs.ScatteringDensityProgram
import earth.worldwind.layer.atmosphere.bruneton.programs.SingleScatteringProgram
import earth.worldwind.layer.atmosphere.bruneton.programs.TransmittanceProgram
import earth.worldwind.render.RenderContext
import earth.worldwind.render.RenderResource
import earth.worldwind.util.kgl.*

internal class BrunetonAtmosphereModel : RenderResource {

    /** Set once [precompute] has run successfully; subsequent calls are no-ops. */
    var isPrecomputed: Boolean = false
        private set

    /** GL context generation observed at last successful precompute. Re-runs on context loss. */
    private var lastSeenContextVersion: Long = -1L

    // Persistent LUTs.
    private var transmittanceTexture = KglTexture.NONE
    private var irradianceTexture = KglTexture.NONE
    private var scatteringTexture = KglTexture.NONE
    private var mieScatteringTexture = KglTexture.NONE

    // Per-iteration scratch. delta_rayleigh / delta_mie are populated once and pinned;
    // delta_irradiance / delta_scattering_density / delta_multiple_scattering are reused.
    private var deltaIrradiance = KglTexture.NONE
    private var deltaRayleigh = KglTexture.NONE
    private var deltaMie = KglTexture.NONE
    private var deltaScatteringDensity = KglTexture.NONE
    private var deltaMultipleScattering = KglTexture.NONE

    private var fbo = KglFramebuffer.NONE

    // Programs stashed on render thread, used on GL thread.
    private var transmittanceProgram: TransmittanceProgram? = null
    private var directIrradianceProgram: DirectIrradianceProgram? = null
    private var singleScatteringProgram: SingleScatteringProgram? = null
    private var scatteringDensityProgram: ScatteringDensityProgram? = null
    private var indirectIrradianceProgram: IndirectIrradianceProgram? = null
    private var multipleScatteringProgram: MultipleScatteringProgram? = null

    /** Persistent LUT handles consumed by the runtime sky / ground programs. */
    fun transmittance(): KglTexture = transmittanceTexture
    fun irradiance(): KglTexture = irradianceTexture
    fun scattering(): KglTexture = scatteringTexture
    fun mieScattering(): KglTexture = mieScatteringTexture

    /**
     * Stash precomputation programs from [rc]'s shader cache. Called on the render-prep
     * thread before queuing a precompute drawable; the drawable then runs on the GL thread
     * and reuses these references via [precompute].
     */
    fun bindPrograms(rc: RenderContext) {
        transmittanceProgram = rc.getShaderProgram(TransmittanceProgram.KEY) { TransmittanceProgram() }
        directIrradianceProgram = rc.getShaderProgram(DirectIrradianceProgram.KEY) { DirectIrradianceProgram() }
        singleScatteringProgram = rc.getShaderProgram(SingleScatteringProgram.KEY) { SingleScatteringProgram() }
        scatteringDensityProgram = rc.getShaderProgram(ScatteringDensityProgram.KEY) { ScatteringDensityProgram() }
        indirectIrradianceProgram = rc.getShaderProgram(IndirectIrradianceProgram.KEY) { IndirectIrradianceProgram() }
        multipleScatteringProgram = rc.getShaderProgram(MultipleScatteringProgram.KEY) { MultipleScatteringProgram() }
    }

    /**
     * Run all precomputation kernels into the LUT textures. Idempotent: once
     * [isPrecomputed] is set, subsequent calls do nothing unless the GL context was lost.
     * Must be invoked on the GL thread.
     */
    fun precompute(dc: DrawContext) {
        if (isPrecomputed && lastSeenContextVersion == dc.contextVersion) return
        if (!dc.gl.supportsTexture3D) return // ES2 / WebGL1 — caller falls back to legacy

        // Context lost since last precompute → drop stale handles. The OS already
        // invalidated the GL objects; just clear the references.
        if (lastSeenContextVersion != dc.contextVersion) {
            transmittanceTexture = KglTexture.NONE
            irradianceTexture = KglTexture.NONE
            scatteringTexture = KglTexture.NONE
            mieScatteringTexture = KglTexture.NONE
            deltaIrradiance = KglTexture.NONE
            deltaRayleigh = KglTexture.NONE
            deltaMie = KglTexture.NONE
            deltaScatteringDensity = KglTexture.NONE
            deltaMultipleScattering = KglTexture.NONE
            fbo = KglFramebuffer.NONE
            isPrecomputed = false
        }

        ensureTextures(dc)
        ensureFbo(dc)

        // Save/restore GL state so the rest of the frame's draws are unaffected.
        val savedFbo = dc.currentFramebuffer
        val savedVpW = dc.viewport.width
        val savedVpH = dc.viewport.height

        runFullPipeline(dc)

        // Restore caller's FBO + viewport. Detach all attachments so the FBO is "blank"
        // for the next precompute (paranoia: GL doesn't strictly require this, but stale
        // attachments to deleted textures have caused driver bugs in the past).
        dc.gl.framebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, KglTexture.NONE, 0)
        dc.bindFramebuffer(savedFbo)
        dc.gl.viewport(0, 0, savedVpW, savedVpH)
        dc.gl.depthMask(true)
        dc.gl.disable(GL_BLEND)

        // Free the per-iteration scratch textures (~6 MB total — three 3D RGBA16F + one 2D
        // RGBA16F). The runtime sky / ground programs only ever sample the four persistent
        // LUTs (transmittance, irradiance, scattering, mie_scattering), so the deltas are
        // pure precompute scratch and are released here as soon as the pipeline is done.
        // The FBO is also dropped — re-allocated on context loss alongside the deltas.
        releaseScratch(dc)

        isPrecomputed = true
        lastSeenContextVersion = dc.contextVersion
    }

    private fun releaseScratch(dc: DrawContext) {
        val gl = dc.gl
        if (deltaIrradiance.isValid()) gl.deleteTexture(deltaIrradiance)
        if (deltaRayleigh.isValid()) gl.deleteTexture(deltaRayleigh)
        if (deltaMie.isValid()) gl.deleteTexture(deltaMie)
        if (deltaScatteringDensity.isValid()) gl.deleteTexture(deltaScatteringDensity)
        if (deltaMultipleScattering.isValid()) gl.deleteTexture(deltaMultipleScattering)
        if (fbo.isValid()) gl.deleteFramebuffer(fbo)
        deltaIrradiance = KglTexture.NONE
        deltaRayleigh = KglTexture.NONE
        deltaMie = KglTexture.NONE
        deltaScatteringDensity = KglTexture.NONE
        deltaMultipleScattering = KglTexture.NONE
        fbo = KglFramebuffer.NONE
    }

    override fun release(dc: DrawContext) {
        val gl = dc.gl
        for (tex in arrayOf(
            transmittanceTexture, irradianceTexture, scatteringTexture, mieScatteringTexture,
            deltaIrradiance, deltaRayleigh, deltaMie, deltaScatteringDensity, deltaMultipleScattering
        )) if (tex.isValid()) gl.deleteTexture(tex)
        if (fbo.isValid()) gl.deleteFramebuffer(fbo)
        transmittanceTexture = KglTexture.NONE
        irradianceTexture = KglTexture.NONE
        scatteringTexture = KglTexture.NONE
        mieScatteringTexture = KglTexture.NONE
        deltaIrradiance = KglTexture.NONE
        deltaRayleigh = KglTexture.NONE
        deltaMie = KglTexture.NONE
        deltaScatteringDensity = KglTexture.NONE
        deltaMultipleScattering = KglTexture.NONE
        fbo = KglFramebuffer.NONE
        isPrecomputed = false
    }

    // ------------------------------------------------------------------------------------
    // Texture / FBO allocation
    // ------------------------------------------------------------------------------------

    private fun ensureTextures(dc: DrawContext) {
        val gl = dc.gl
        if (!transmittanceTexture.isValid())
            transmittanceTexture = create2DRgba16f(dc, TRANSMITTANCE_TEXTURE_WIDTH, TRANSMITTANCE_TEXTURE_HEIGHT)
        if (!irradianceTexture.isValid())
            irradianceTexture = create2DRgba16f(dc, IRRADIANCE_TEXTURE_WIDTH, IRRADIANCE_TEXTURE_HEIGHT)
        if (!scatteringTexture.isValid())
            scatteringTexture = create3DRgba16f(dc, SCATTERING_TEXTURE_WIDTH, SCATTERING_TEXTURE_HEIGHT, SCATTERING_TEXTURE_DEPTH)
        if (!mieScatteringTexture.isValid())
            mieScatteringTexture = create3DRgba16f(dc, SCATTERING_TEXTURE_WIDTH, SCATTERING_TEXTURE_HEIGHT, SCATTERING_TEXTURE_DEPTH)
        if (!deltaIrradiance.isValid())
            deltaIrradiance = create2DRgba16f(dc, IRRADIANCE_TEXTURE_WIDTH, IRRADIANCE_TEXTURE_HEIGHT)
        if (!deltaRayleigh.isValid())
            deltaRayleigh = create3DRgba16f(dc, SCATTERING_TEXTURE_WIDTH, SCATTERING_TEXTURE_HEIGHT, SCATTERING_TEXTURE_DEPTH)
        if (!deltaMie.isValid())
            deltaMie = create3DRgba16f(dc, SCATTERING_TEXTURE_WIDTH, SCATTERING_TEXTURE_HEIGHT, SCATTERING_TEXTURE_DEPTH)
        if (!deltaScatteringDensity.isValid())
            deltaScatteringDensity = create3DRgba16f(dc, SCATTERING_TEXTURE_WIDTH, SCATTERING_TEXTURE_HEIGHT, SCATTERING_TEXTURE_DEPTH)
        if (!deltaMultipleScattering.isValid())
            deltaMultipleScattering = create3DRgba16f(dc, SCATTERING_TEXTURE_WIDTH, SCATTERING_TEXTURE_HEIGHT, SCATTERING_TEXTURE_DEPTH)
        gl.bindTexture(GL_TEXTURE_2D, KglTexture.NONE)
        gl.bindTexture(GL_TEXTURE_3D, KglTexture.NONE)
    }

    private fun ensureFbo(dc: DrawContext) {
        if (!fbo.isValid()) fbo = dc.gl.createFramebuffer()
    }

    private fun create2DRgba16f(dc: DrawContext, w: Int, h: Int): KglTexture {
        val gl = dc.gl
        val tex = gl.createTexture()
        gl.bindTexture(GL_TEXTURE_2D, tex)
        // RGBA16F is the portable color-renderable float format on ES3 / WebGL2 / GL3+.
        // RGB16F is NOT guaranteed renderable; alpha is unused but cheap.
        gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_HALF_FLOAT, null as ByteArray?)
        gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        return tex
    }

    private fun create3DRgba16f(dc: DrawContext, w: Int, h: Int, d: Int): KglTexture {
        val gl = dc.gl
        val tex = gl.createTexture()
        gl.bindTexture(GL_TEXTURE_3D, tex)
        gl.texImage3D(GL_TEXTURE_3D, 0, GL_RGBA16F, w, h, d, 0, GL_RGBA, GL_HALF_FLOAT, null as ByteArray?)
        gl.texParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        gl.texParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        gl.texParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        gl.texParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        gl.texParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE)
        return tex
    }

    // ------------------------------------------------------------------------------------
    // Driver — full reference Model::Init pipeline
    // ------------------------------------------------------------------------------------

    private fun runFullPipeline(dc: DrawContext) {
        val gl = dc.gl

        // Common GL state for all precompute passes.
        dc.bindFramebuffer(fbo)
        gl.disable(GL_DEPTH_TEST)
        gl.depthMask(false)
        gl.colorMask(true, true, true, true)
        gl.disable(GL_BLEND)

        // 1. Transmittance LUT.
        runTransmittance(dc)

        // 2. Direct irradiance → delta_irradiance, with irradiance accumulator zero-cleared.
        runDirectIrradiance(dc)
        clear2D(dc, irradianceTexture, IRRADIANCE_TEXTURE_WIDTH, IRRADIANCE_TEXTURE_HEIGHT)

        // 3. Single Rayleigh: write delta_rayleigh AND scattering_texture (both = same value).
        //    Single Mie: write delta_mie AND mie_scattering_texture.
        runSingleScattering(dc, SingleScatteringProgram.MODE_RAYLEIGH, deltaRayleigh, additive = false)
        runSingleScattering(dc, SingleScatteringProgram.MODE_RAYLEIGH, scatteringTexture, additive = false)
        runSingleScattering(dc, SingleScatteringProgram.MODE_MIE, deltaMie, additive = false)
        runSingleScattering(dc, SingleScatteringProgram.MODE_MIE, mieScatteringTexture, additive = false)

        // 4. N-1 multi-bounce iterations, accumulating into irradiance + scattering.
        for (order in 2..NUM_SCATTERING_ORDERS) {
            runScatteringDensity(dc, order)

            // Indirect irradiance: write delta_irradiance, then accumulate into irradiance.
            runIndirectIrradiance(dc, deltaIrradiance, order - 1, additive = false)
            runIndirectIrradiance(dc, irradianceTexture, order - 1, additive = true)

            // Multiple scattering: raw value to delta, phase-divided value to scattering accumulator.
            runMultipleScattering(
                dc, deltaMultipleScattering, MultipleScatteringProgram.MODE_RAW, additive = false
            )
            runMultipleScattering(
                dc, scatteringTexture, MultipleScatteringProgram.MODE_PHASE_DIVIDED, additive = true
            )
        }
    }

    // ------------------------------------------------------------------------------------
    // Per-kernel passes
    // ------------------------------------------------------------------------------------

    private fun runTransmittance(dc: DrawContext) {
        val program = transmittanceProgram ?: return
        if (!program.useProgram(dc)) return
        attach2D(dc, transmittanceTexture)
        dc.gl.viewport(0, 0, TRANSMITTANCE_TEXTURE_WIDTH, TRANSMITTANCE_TEXTURE_HEIGHT)
        dc.gl.disable(GL_BLEND)
        dc.gl.drawArrays(GL_TRIANGLES, 0, 3)
    }

    private fun runDirectIrradiance(dc: DrawContext) {
        val program = directIrradianceProgram ?: return
        if (!program.useProgram(dc)) return
        bindSampler(dc, GL_TEXTURE0, GL_TEXTURE_2D, transmittanceTexture)
        attach2D(dc, deltaIrradiance)
        dc.gl.viewport(0, 0, IRRADIANCE_TEXTURE_WIDTH, IRRADIANCE_TEXTURE_HEIGHT)
        dc.gl.disable(GL_BLEND)
        dc.gl.drawArrays(GL_TRIANGLES, 0, 3)
    }

    private fun runSingleScattering(dc: DrawContext, mode: Int, target: KglTexture, additive: Boolean) {
        val program = singleScatteringProgram ?: return
        if (!program.useProgram(dc)) return
        bindSampler(dc, GL_TEXTURE0, GL_TEXTURE_2D, transmittanceTexture)
        program.loadMode(mode)
        dc.gl.viewport(0, 0, SCATTERING_TEXTURE_WIDTH, SCATTERING_TEXTURE_HEIGHT)
        setBlend(dc, additive)
        for (z in 0 until SCATTERING_TEXTURE_DEPTH) {
            program.loadLayer(z)
            attach3DLayer(dc, target, z)
            dc.gl.drawArrays(GL_TRIANGLES, 0, 3)
        }
    }

    private fun runScatteringDensity(dc: DrawContext, order: Int) {
        val program = scatteringDensityProgram ?: return
        if (!program.useProgram(dc)) return
        bindSampler(dc, GL_TEXTURE0, GL_TEXTURE_2D, transmittanceTexture)
        bindSampler(dc, GL_TEXTURE1, GL_TEXTURE_3D, deltaRayleigh)
        bindSampler(dc, GL_TEXTURE2, GL_TEXTURE_3D, deltaMie)
        bindSampler(dc, GL_TEXTURE3, GL_TEXTURE_3D, deltaMultipleScattering)
        bindSampler(dc, GL_TEXTURE4, GL_TEXTURE_2D, deltaIrradiance)
        program.loadScatteringOrder(order)
        dc.gl.viewport(0, 0, SCATTERING_TEXTURE_WIDTH, SCATTERING_TEXTURE_HEIGHT)
        setBlend(dc, false)
        for (z in 0 until SCATTERING_TEXTURE_DEPTH) {
            program.loadLayer(z)
            attach3DLayer(dc, deltaScatteringDensity, z)
            dc.gl.drawArrays(GL_TRIANGLES, 0, 3)
        }
    }

    private fun runIndirectIrradiance(dc: DrawContext, target: KglTexture, order: Int, additive: Boolean) {
        val program = indirectIrradianceProgram ?: return
        if (!program.useProgram(dc)) return
        bindSampler(dc, GL_TEXTURE0, GL_TEXTURE_3D, deltaRayleigh)
        bindSampler(dc, GL_TEXTURE1, GL_TEXTURE_3D, deltaMie)
        bindSampler(dc, GL_TEXTURE2, GL_TEXTURE_3D, deltaMultipleScattering)
        program.loadScatteringOrder(order)
        attach2D(dc, target)
        dc.gl.viewport(0, 0, IRRADIANCE_TEXTURE_WIDTH, IRRADIANCE_TEXTURE_HEIGHT)
        setBlend(dc, additive)
        dc.gl.drawArrays(GL_TRIANGLES, 0, 3)
    }

    private fun runMultipleScattering(dc: DrawContext, target: KglTexture, mode: Int, additive: Boolean) {
        val program = multipleScatteringProgram ?: return
        if (!program.useProgram(dc)) return
        bindSampler(dc, GL_TEXTURE0, GL_TEXTURE_2D, transmittanceTexture)
        bindSampler(dc, GL_TEXTURE1, GL_TEXTURE_3D, deltaScatteringDensity)
        program.loadMode(mode)
        dc.gl.viewport(0, 0, SCATTERING_TEXTURE_WIDTH, SCATTERING_TEXTURE_HEIGHT)
        setBlend(dc, additive)
        for (z in 0 until SCATTERING_TEXTURE_DEPTH) {
            program.loadLayer(z)
            attach3DLayer(dc, target, z)
            dc.gl.drawArrays(GL_TRIANGLES, 0, 3)
        }
    }

    // ------------------------------------------------------------------------------------
    // GL state helpers
    // ------------------------------------------------------------------------------------

    private fun attach2D(dc: DrawContext, tex: KglTexture) {
        dc.gl.framebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0)
    }

    private fun attach3DLayer(dc: DrawContext, tex: KglTexture, layer: Int) {
        dc.gl.framebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, tex, 0, layer)
    }

    private fun bindSampler(dc: DrawContext, unit: Int, target: Int, tex: KglTexture) {
        dc.gl.activeTexture(unit)
        dc.gl.bindTexture(target, tex)
    }

    private fun setBlend(dc: DrawContext, additive: Boolean) {
        if (additive) {
            dc.gl.enable(GL_BLEND)
            dc.gl.blendFunc(GL_ONE, GL_ONE)
        } else {
            dc.gl.disable(GL_BLEND)
        }
    }

    private fun clear2D(dc: DrawContext, tex: KglTexture, w: Int, h: Int) {
        attach2D(dc, tex)
        dc.gl.viewport(0, 0, w, h)
        dc.gl.clearColor(0f, 0f, 0f, 0f)
        dc.gl.clear(GL_COLOR_BUFFER_BIT)
    }

    companion object {
        /** Cache key for [RenderContext.renderResourceCache]; one model per WorldWindow. */
        val KEY = BrunetonAtmosphereModel::class
    }
}
