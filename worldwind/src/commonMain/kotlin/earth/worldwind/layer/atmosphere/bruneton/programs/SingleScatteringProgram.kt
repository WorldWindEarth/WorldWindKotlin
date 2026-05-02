/*
 * Adapted from ebruneton/precomputed_atmospheric_scattering, BSD-3-Clause,
 * Copyright (c) 2017 Eric Bruneton.
 *
 * Precomputation kernel: single scattering. One pass per Z-slice of the 3D LUT; the driver
 * loops `framebufferTextureLayer(layer)` + drawArrays. The shader writes EITHER Rayleigh OR
 * Mie depending on a `kernelMode` uniform (0 = Rayleigh, 1 = Mie) — split into two passes
 * rather than using MRT, which avoids needing `glDrawBuffers` in the Kgl surface. The
 * 50-sample integration cost is dominant either way.
 */
package earth.worldwind.layer.atmosphere.bruneton.programs

import earth.worldwind.draw.DrawContext
import earth.worldwind.layer.atmosphere.bruneton.BrunetonShaders
import earth.worldwind.render.program.AbstractShaderProgram
import earth.worldwind.util.kgl.KglUniformLocation

internal class SingleScatteringProgram : AbstractShaderProgram() {

    override var programSources = arrayOf(
        BrunetonShaders.FULLSCREEN_TRIANGLE_VERTEX,
        FRAGMENT_SOURCE
    )
    override val attribBindings = emptyArray<String>()

    override fun glslVersion(dc: DrawContext) = dc.gl.glslVersion3

    private var transmittanceTexId = KglUniformLocation.NONE
    private var layerId = KglUniformLocation.NONE
    private var kernelModeId = KglUniformLocation.NONE

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        transmittanceTexId = gl.getUniformLocation(program, "transmittanceTex")
        gl.uniform1i(transmittanceTexId, 0)
        layerId = gl.getUniformLocation(program, "layer")
        kernelModeId = gl.getUniformLocation(program, "kernelMode")
    }

    fun loadLayer(layer: Int) = gl.uniform1i(layerId, layer)
    fun loadMode(mode: Int) = gl.uniform1i(kernelModeId, mode)

    companion object {
        val KEY = SingleScatteringProgram::class
        const val MODE_RAYLEIGH = 0
        const val MODE_MIE = 1

        private val FRAGMENT_SOURCE = """
            precision highp float;
            precision highp int;
            precision highp sampler2D;

            ${BrunetonShaders.COMMON}

            uniform sampler2D transmittanceTex;
            uniform int layer;          // current Z-slice index
            uniform int kernelMode;     // 0 = Rayleigh output, 1 = Mie output
            out vec4 fragColor;

            void main() {
                float r;
                float mu;
                float mu_s;
                float nu;
                bool intersects_ground;
                vec3 frag = vec3(gl_FragCoord.xy, float(layer) + 0.5);
                GetRMuMuSNuFromScatteringTextureFragCoord(frag, r, mu, mu_s, nu, intersects_ground);

                vec3 rayleigh, mie;
                ComputeSingleScattering(transmittanceTex, r, mu, mu_s, nu, intersects_ground,
                                        rayleigh, mie);
                vec3 result = (kernelMode == 0) ? rayleigh : mie;
                fragColor = vec4(result, 1.0);
            }
        """.trimIndent()
    }
}
