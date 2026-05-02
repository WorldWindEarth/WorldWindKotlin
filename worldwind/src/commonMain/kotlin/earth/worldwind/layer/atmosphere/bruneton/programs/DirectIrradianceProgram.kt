/*
 * Adapted from ebruneton/precomputed_atmospheric_scattering, BSD-3-Clause,
 * Copyright (c) 2017 Eric Bruneton.
 *
 * Precomputation kernel: direct irradiance — the irradiance from the unscattered sun on a
 * horizontal patch at altitude r with sun-zenith cos μ_s. Output sized 64×16 RGBA16F (the
 * `delta_irradiance` LUT in the reference). One pass, no input LUTs except transmittance.
 */
package earth.worldwind.layer.atmosphere.bruneton.programs

import earth.worldwind.draw.DrawContext
import earth.worldwind.layer.atmosphere.bruneton.BrunetonShaders
import earth.worldwind.render.program.AbstractShaderProgram
import earth.worldwind.util.kgl.KglUniformLocation

internal class DirectIrradianceProgram : AbstractShaderProgram() {

    override var programSources = arrayOf(
        BrunetonShaders.FULLSCREEN_TRIANGLE_VERTEX,
        FRAGMENT_SOURCE
    )
    override val attribBindings = emptyArray<String>()

    override fun glslVersion(dc: DrawContext) = dc.gl.glslVersion3

    private var transmittanceTexId = KglUniformLocation.NONE

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        transmittanceTexId = gl.getUniformLocation(program, "transmittanceTex")
        gl.uniform1i(transmittanceTexId, 0) // GL_TEXTURE0
    }

    companion object {
        val KEY = DirectIrradianceProgram::class

        // ComputeDirectIrradiance: SOLAR_IRRADIANCE × T(r, μ_s) × <average cosine factor>,
        // where the cosine factor analytically averages over the sun's angular extent so a
        // sun straddling the horizon contributes a smooth fraction of its full irradiance.
        private val FRAGMENT_SOURCE = """
            precision highp float;
            precision highp int;
            precision highp sampler2D;

            ${BrunetonShaders.COMMON}

            uniform sampler2D transmittanceTex;
            out vec4 fragColor;

            vec3 ComputeDirectIrradiance(float r, float mu_s) {
                float alpha_s = SUN_ANGULAR_RADIUS;
                float average_cosine_factor =
                    mu_s < -alpha_s ? 0.0 :
                    (mu_s > alpha_s ? mu_s :
                                       (mu_s + alpha_s) * (mu_s + alpha_s) / (4.0 * alpha_s));
                return SOLAR_IRRADIANCE *
                       GetTransmittanceToTopAtmosphereBoundary(transmittanceTex, r, mu_s) *
                       average_cosine_factor;
            }

            void main() {
                vec2 size = vec2(float(IRRADIANCE_TEXTURE_WIDTH), float(IRRADIANCE_TEXTURE_HEIGHT));
                vec2 uv = gl_FragCoord.xy / size;
                float r;
                float mu_s;
                GetRMuSFromIrradianceTextureUv(uv, r, mu_s);
                fragColor = vec4(ComputeDirectIrradiance(r, mu_s), 1.0);
            }
        """.trimIndent()
    }
}
