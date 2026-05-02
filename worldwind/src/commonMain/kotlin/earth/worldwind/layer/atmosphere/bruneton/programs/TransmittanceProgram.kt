/*
 * Adapted from ebruneton/precomputed_atmospheric_scattering, BSD-3-Clause,
 * Copyright (c) 2017 Eric Bruneton.
 *
 * Precomputation kernel: transmittance from any (r, μ) point in the atmosphere to the top
 * boundary along the view ray. One-time pass; output is the 2D transmittance LUT sampled
 * by every later kernel and by the runtime sky / ground programs.
 *
 * Requires GLES 3 / WebGL 2 / GL 3.3 core: uses `out vec4 fragColor`, sized float-format
 * texture rendering (RGBA16F target), and gl_VertexID. [glslVersion] is overridden to emit
 * `#version 300 es` / `#version 330 core` rather than the legacy 1.20 directive.
 */
package earth.worldwind.layer.atmosphere.bruneton.programs

import earth.worldwind.draw.DrawContext
import earth.worldwind.layer.atmosphere.bruneton.BrunetonShaders
import earth.worldwind.render.program.AbstractShaderProgram

internal class TransmittanceProgram : AbstractShaderProgram() {

    override var programSources = arrayOf(
        BrunetonShaders.FULLSCREEN_TRIANGLE_VERTEX,
        FRAGMENT_SOURCE
    )
    override val attribBindings = emptyArray<String>() // gl_VertexID-driven, no attribs

    override fun glslVersion(dc: DrawContext) = dc.gl.glslVersion3

    companion object {
        val KEY = TransmittanceProgram::class

        // Per-fragment: take the texel's gl_FragCoord, convert to (r, μ) via Bruneton's
        // horizon-aware parameterization, integrate Rayleigh + Mie + ozone optical depths
        // along the view ray to the atmosphere top, and write transmittance = exp(-τ).
        // Alpha is unused but written as 1.0 so the texture is well-formed.
        private val FRAGMENT_SOURCE = """
            precision highp float;
            precision highp int;

            ${BrunetonShaders.COMMON}

            out vec4 fragColor;

            // Reference's `ComputeTransmittanceToTopAtmosphereBoundaryTexture`:
            //   τ_total = σ_R · L_R(r,μ)  +  σ_M_ext · L_M(r,μ)  +  σ_O · L_O(r,μ)
            //   T = exp(-τ_total)
            // where L_X is the optical length integrated against profile X's density.
            vec3 ComputeTransmittanceToTop(float r, float mu) {
                vec3 tau =
                    RAYLEIGH_SCATTERING * ComputeOpticalLengthToTopAtmosphereBoundary(RayleighDensityProfile(), r, mu) +
                    vec3(MIE_EXTINCTION) * ComputeOpticalLengthToTopAtmosphereBoundary(MieDensityProfile(), r, mu) +
                    OZONE_ABSORPTION * ComputeOpticalLengthToTopAtmosphereBoundary(OzoneDensityProfile(), r, mu);
                return exp(-tau);
            }

            void main() {
                vec2 uv = gl_FragCoord.xy / vec2(float(TRANSMITTANCE_TEXTURE_WIDTH), float(TRANSMITTANCE_TEXTURE_HEIGHT));
                float r;
                float mu;
                GetRMuFromTransmittanceTextureUv(uv, r, mu);
                fragColor = vec4(ComputeTransmittanceToTop(r, mu), 1.0);
            }
        """.trimIndent()
    }
}
