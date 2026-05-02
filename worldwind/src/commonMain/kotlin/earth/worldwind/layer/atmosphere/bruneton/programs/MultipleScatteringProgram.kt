/*
 * Adapted from ebruneton/precomputed_atmospheric_scattering, BSD-3-Clause,
 * Copyright (c) 2017 Eric Bruneton.
 *
 * Precomputation kernel: multiple scattering for a single iteration order. Integrates the
 * scattering-density LUT (output of [ScatteringDensityProgram]) along the view ray, weighted
 * by transmittance from the camera point. Two output modes:
 *  - [MODE_RAW]    — raw line integral value, written to `delta_multiple_scattering` (used
 *                    as input by the next iteration's scattering-density kernel).
 *  - [MODE_PHASE_DIVIDED] — value divided by Rayleigh phase, accumulated into the persistent
 *                    `scattering_texture`. This is the Bruneton compaction trick that lets
 *                    one 3D LUT accumulate scattering across all orders: the runtime shader
 *                    re-multiplies by the local nu's Rayleigh phase to recover radiance.
 */
package earth.worldwind.layer.atmosphere.bruneton.programs

import earth.worldwind.draw.DrawContext
import earth.worldwind.layer.atmosphere.bruneton.BrunetonShaders
import earth.worldwind.render.program.AbstractShaderProgram
import earth.worldwind.util.kgl.KglUniformLocation

internal class MultipleScatteringProgram : AbstractShaderProgram() {

    override var programSources = arrayOf(
        BrunetonShaders.FULLSCREEN_TRIANGLE_VERTEX,
        FRAGMENT_SOURCE
    )
    override val attribBindings = emptyArray<String>()

    override fun glslVersion(dc: DrawContext) = dc.gl.glslVersion3

    private var transmittanceTexId = KglUniformLocation.NONE
    private var scatteringDensityTexId = KglUniformLocation.NONE
    private var layerId = KglUniformLocation.NONE
    private var kernelModeId = KglUniformLocation.NONE

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        transmittanceTexId = gl.getUniformLocation(program, "transmittanceTex")
        scatteringDensityTexId = gl.getUniformLocation(program, "scatteringDensityTex")
        gl.uniform1i(transmittanceTexId, 0)
        gl.uniform1i(scatteringDensityTexId, 1)
        layerId = gl.getUniformLocation(program, "layer")
        kernelModeId = gl.getUniformLocation(program, "kernelMode")
    }

    fun loadLayer(layer: Int) = gl.uniform1i(layerId, layer)
    fun loadMode(mode: Int) = gl.uniform1i(kernelModeId, mode)

    companion object {
        val KEY = MultipleScatteringProgram::class
        const val MODE_RAW = 0
        const val MODE_PHASE_DIVIDED = 1

        // ComputeMultipleScattering: line integral over [0, d_to_boundary] of
        //   transmittance(P→sample) × scattering_density(sample) ds
        // 50-sample trapezoidal rule. Result divided by RayleighPhase(nu) — see header.
        private val FRAGMENT_SOURCE = """
            precision highp float;
            precision highp int;
            precision highp sampler2D;
            precision highp sampler3D;

            ${BrunetonShaders.COMMON}

            uniform sampler2D transmittanceTex;
            uniform sampler3D scatteringDensityTex;
            uniform int layer;
            uniform int kernelMode;     // 0 = raw output, 1 = phase-divided output

            out vec4 fragColor;

            vec3 ComputeMultipleScattering(
                float r, float mu, float mu_s, float nu, bool intersects_ground
            ) {
                const int SAMPLE_COUNT = 50;
                float dx = DistanceToNearestAtmosphereBoundary(r, mu, intersects_ground) /
                           float(SAMPLE_COUNT);
                vec3 result = vec3(0.0);
                for (int i = 0; i <= SAMPLE_COUNT; ++i) {
                    float d_i = float(i) * dx;
                    float r_i = ClampRadius(sqrt(d_i * d_i + 2.0 * r * mu * d_i + r * r));
                    float mu_i = ClampCosine((r * mu + d_i) / r_i);
                    float mu_s_i = ClampCosine((r * mu_s + d_i * nu) / r_i);
                    vec3 rayleigh_mie =
                        GetScattering(scatteringDensityTex, r_i, mu_i, mu_s_i, nu, intersects_ground) *
                        GetTransmittance(transmittanceTex, r, mu, d_i, intersects_ground) * dx;
                    float w = (i == 0 || i == SAMPLE_COUNT) ? 0.5 : 1.0;
                    result += rayleigh_mie * w;
                }
                return result;
            }

            void main() {
                float r;
                float mu;
                float mu_s;
                float nu;
                bool intersects_ground;
                vec3 frag = vec3(gl_FragCoord.xy, float(layer) + 0.5);
                GetRMuMuSNuFromScatteringTextureFragCoord(frag, r, mu, mu_s, nu, intersects_ground);
                vec3 multi = ComputeMultipleScattering(r, mu, mu_s, nu, intersects_ground);
                // Mode 0: raw value → delta_multiple_scattering (input for next density kernel).
                // Mode 1: divided by Rayleigh phase → scattering_texture accumulator. The
                // runtime shader re-multiplies by phase to recover radiance. RayleighPhase(nu)
                // is strictly positive; no zero check needed.
                vec3 result = (kernelMode == 0) ? multi : (multi / RayleighPhaseFunction(nu));
                fragColor = vec4(result, 1.0);
            }
        """.trimIndent()
    }
}
