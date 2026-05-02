/*
 * Adapted from ebruneton/precomputed_atmospheric_scattering, BSD-3-Clause,
 * Copyright (c) 2017 Eric Bruneton.
 *
 * Precomputation kernel: indirect irradiance — irradiance on a horizontal patch from the
 * previous-order scattering, integrated over the upper hemisphere. Output sized 64×16
 * RGBA16F (the `delta_irradiance` LUT in the reference). Run once per scattering order;
 * the main `irradiance_texture` accumulator is updated separately with additive blend.
 */
package earth.worldwind.layer.atmosphere.bruneton.programs

import earth.worldwind.draw.DrawContext
import earth.worldwind.layer.atmosphere.bruneton.BrunetonShaders
import earth.worldwind.render.program.AbstractShaderProgram
import earth.worldwind.util.kgl.KglUniformLocation

internal class IndirectIrradianceProgram : AbstractShaderProgram() {

    override var programSources = arrayOf(
        BrunetonShaders.FULLSCREEN_TRIANGLE_VERTEX,
        FRAGMENT_SOURCE
    )
    override val attribBindings = emptyArray<String>()

    override fun glslVersion(dc: DrawContext) = dc.gl.glslVersion3

    private var singleRayleighTexId = KglUniformLocation.NONE
    private var singleMieTexId = KglUniformLocation.NONE
    private var multiScatteringTexId = KglUniformLocation.NONE
    private var scatteringOrderId = KglUniformLocation.NONE

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        singleRayleighTexId = gl.getUniformLocation(program, "singleRayleighTex")
        singleMieTexId = gl.getUniformLocation(program, "singleMieTex")
        multiScatteringTexId = gl.getUniformLocation(program, "multiScatteringTex")
        gl.uniform1i(singleRayleighTexId, 0)
        gl.uniform1i(singleMieTexId, 1)
        gl.uniform1i(multiScatteringTexId, 2)
        scatteringOrderId = gl.getUniformLocation(program, "scatteringOrder")
    }

    fun loadScatteringOrder(order: Int) = gl.uniform1i(scatteringOrderId, order)

    companion object {
        val KEY = IndirectIrradianceProgram::class

        // ComputeIndirectIrradiance: integrate previous-order in-scattered radiance × cos(θ)
        // over the upper hemisphere. 32×8 sample grid; spherical-coords domega = sin θ dθ dφ.
        private val FRAGMENT_SOURCE = """
            precision highp float;
            precision highp int;
            precision highp sampler2D;
            precision highp sampler3D;

            ${BrunetonShaders.COMMON}

            uniform sampler3D singleRayleighTex;
            uniform sampler3D singleMieTex;
            uniform sampler3D multiScatteringTex;
            uniform int scatteringOrder;

            out vec4 fragColor;

            vec3 ComputeIndirectIrradiance(float r, float mu_s, int order) {
                const int SAMPLE_COUNT = 32;
                const float dphi   = PI / float(SAMPLE_COUNT);
                const float dtheta = PI / float(SAMPLE_COUNT);

                vec3 result = vec3(0.0);
                vec3 omega_s = vec3(sqrt(1.0 - mu_s * mu_s), 0.0, mu_s);
                for (int j = 0; j < SAMPLE_COUNT / 2; ++j) {
                    float theta = (float(j) + 0.5) * dtheta;
                    for (int i = 0; i < 2 * SAMPLE_COUNT; ++i) {
                        float phi = (float(i) + 0.5) * dphi;
                        vec3 omega = vec3(cos(phi) * sin(theta), sin(phi) * sin(theta), cos(theta));
                        float domega = dtheta * dphi * sin(theta);
                        float nu = dot(omega, omega_s);
                        result += GetScatteringByOrder(
                            singleRayleighTex, singleMieTex, multiScatteringTex,
                            r, omega.z, mu_s, nu, false /* not below horizon at ground patch */, order
                        ) * omega.z * domega;
                    }
                }
                return result;
            }

            void main() {
                vec2 size = vec2(float(IRRADIANCE_TEXTURE_WIDTH), float(IRRADIANCE_TEXTURE_HEIGHT));
                float r;
                float mu_s;
                GetRMuSFromIrradianceTextureUv(gl_FragCoord.xy / size, r, mu_s);
                fragColor = vec4(ComputeIndirectIrradiance(r, mu_s, scatteringOrder), 1.0);
            }
        """.trimIndent()
    }
}
