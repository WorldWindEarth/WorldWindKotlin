/*
 * Adapted from ebruneton/precomputed_atmospheric_scattering, BSD-3-Clause,
 * Copyright (c) 2017 Eric Bruneton.
 *
 * Precomputation kernel: scattering density — the in-scattered radiance from all directions
 * at point P, combining the previous order's scattering with a ground-bounce term scaled by
 * the previous order's irradiance. Used as the source term integrated by the
 * multiple-scattering kernel for the next iteration order.
 *
 * Spherical integral with 16 zenith samples × 32 azimuth samples (64 over the sphere ×
 * 2 for the wrap-around). One pass per Z-slice; the driver loops layers.
 */
package earth.worldwind.layer.atmosphere.bruneton.programs

import earth.worldwind.draw.DrawContext
import earth.worldwind.layer.atmosphere.bruneton.BrunetonShaders
import earth.worldwind.render.program.AbstractShaderProgram
import earth.worldwind.util.kgl.KglUniformLocation

internal class ScatteringDensityProgram : AbstractShaderProgram() {

    override var programSources = arrayOf(
        BrunetonShaders.FULLSCREEN_TRIANGLE_VERTEX,
        FRAGMENT_SOURCE
    )
    override val attribBindings = emptyArray<String>()

    override fun glslVersion(dc: DrawContext) = dc.gl.glslVersion3

    private var transmittanceTexId = KglUniformLocation.NONE
    private var singleRayleighTexId = KglUniformLocation.NONE
    private var singleMieTexId = KglUniformLocation.NONE
    private var multiScatteringTexId = KglUniformLocation.NONE
    private var irradianceTexId = KglUniformLocation.NONE
    private var layerId = KglUniformLocation.NONE
    private var scatteringOrderId = KglUniformLocation.NONE

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        transmittanceTexId = gl.getUniformLocation(program, "transmittanceTex")
        singleRayleighTexId = gl.getUniformLocation(program, "singleRayleighTex")
        singleMieTexId = gl.getUniformLocation(program, "singleMieTex")
        multiScatteringTexId = gl.getUniformLocation(program, "multiScatteringTex")
        irradianceTexId = gl.getUniformLocation(program, "irradianceTex")
        // Sampler unit assignments — caller binds matching units before drawing.
        gl.uniform1i(transmittanceTexId, 0)
        gl.uniform1i(singleRayleighTexId, 1)
        gl.uniform1i(singleMieTexId, 2)
        gl.uniform1i(multiScatteringTexId, 3)
        gl.uniform1i(irradianceTexId, 4)
        layerId = gl.getUniformLocation(program, "layer")
        scatteringOrderId = gl.getUniformLocation(program, "scatteringOrder")
    }

    fun loadLayer(layer: Int) = gl.uniform1i(layerId, layer)
    fun loadScatteringOrder(order: Int) = gl.uniform1i(scatteringOrderId, order)

    companion object {
        val KEY = ScatteringDensityProgram::class

        private val FRAGMENT_SOURCE = """
            precision highp float;
            precision highp int;
            precision highp sampler2D;
            precision highp sampler3D;

            ${BrunetonShaders.COMMON}

            uniform sampler2D transmittanceTex;
            uniform sampler3D singleRayleighTex;
            uniform sampler3D singleMieTex;
            uniform sampler3D multiScatteringTex;
            uniform sampler2D irradianceTex;
            uniform int layer;
            uniform int scatteringOrder;

            out vec4 fragColor;

            // Match the reference's ComputeScatteringDensity. The integrand at each sample
            // direction ω_i sums:
            //   (a) previous-order in-scattered radiance reaching P from ω_i, evaluated at
            //       (r, ω_i·zenith, μ_s′, nu1) with μ_s′ rotated under ω_i if applicable,
            //   (b) ground-bounce radiance: previous-order irradiance on the ground point
            //       hit by ray (P, ω_i), reflected via Lambertian albedo back along -ω_i.
            // The integrand is then weighted by the local Rayleigh+Mie phase × density.
            vec3 ComputeScatteringDensity(
                float r, float mu, float mu_s, float nu, int scattering_order
            ) {
                vec3 zenith_direction = vec3(0.0, 0.0, 1.0);
                // Pick an arbitrary frame: view ω in xz-plane. Sun-direction y-component is
                // determined by the dot-product constraint nu = ω·ω_s.
                vec3 omega = vec3(sqrt(1.0 - mu * mu), 0.0, mu);
                float sun_dir_x = omega.x == 0.0 ? 0.0 : (nu - mu * mu_s) / omega.x;
                float sun_dir_y = sqrt(max(1.0 - sun_dir_x * sun_dir_x - mu_s * mu_s, 0.0));
                vec3 omega_s = vec3(sun_dir_x, sun_dir_y, mu_s);

                const int SAMPLE_COUNT = 16;
                const float dphi   = PI / float(SAMPLE_COUNT);
                const float dtheta = PI / float(SAMPLE_COUNT);
                vec3 result = vec3(0.0);

                for (int l = 0; l < SAMPLE_COUNT; ++l) {
                    float theta = (float(l) + 0.5) * dtheta;
                    float cos_theta = cos(theta);
                    float sin_theta = sin(theta);
                    bool ray_r_theta_intersects_ground = RayIntersectsGround(r, cos_theta);

                    // Distance to ground / transmittance / albedo for the ground-bounce term.
                    // Computed once per zenith sample, reused inside the azimuth loop.
                    float distance_to_ground = 0.0;
                    vec3 transmittance_to_ground = vec3(0.0);
                    vec3 ground_albedo = vec3(0.0);
                    if (ray_r_theta_intersects_ground) {
                        distance_to_ground = DistanceToBottomAtmosphereBoundary(r, cos_theta);
                        transmittance_to_ground = GetTransmittance(transmittanceTex, r, cos_theta,
                                                                   distance_to_ground, true);
                        ground_albedo = vec3(GROUND_ALBEDO);
                    }

                    for (int m = 0; m < 2 * SAMPLE_COUNT; ++m) {
                        float phi = (float(m) + 0.5) * dphi;
                        vec3 omega_i = vec3(cos(phi) * sin_theta, sin(phi) * sin_theta, cos_theta);
                        float domega_i = dtheta * dphi * sin_theta;

                        float nu1 = dot(omega_s, omega_i);
                        vec3 incident_radiance = GetScatteringByOrder(
                            singleRayleighTex, singleMieTex, multiScatteringTex,
                            r, omega_i.z, mu_s, nu1, ray_r_theta_intersects_ground,
                            scattering_order - 1);

                        // Lambertian reflection from the ground at the ray's hit point.
                        vec3 ground_normal = normalize(zenith_direction * r + omega_i * distance_to_ground);
                        vec3 ground_irradiance = GetIrradiance(irradianceTex, BOTTOM_RADIUS,
                                                               dot(ground_normal, omega_s));
                        incident_radiance += transmittance_to_ground * ground_albedo *
                                             (1.0 / PI) * ground_irradiance;

                        float nu2 = dot(omega, omega_i);
                        float rayleigh_density = GetProfileDensity(RayleighDensityProfile(), r - BOTTOM_RADIUS);
                        float mie_density      = GetProfileDensity(MieDensityProfile(),      r - BOTTOM_RADIUS);
                        result += incident_radiance * (
                            RAYLEIGH_SCATTERING * rayleigh_density * RayleighPhaseFunction(nu2) +
                            vec3(MIE_SCATTERING) * mie_density     * MiePhaseFunction(MIE_PHASE_G, nu2)
                        ) * domega_i;
                    }
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
                fragColor = vec4(ComputeScatteringDensity(r, mu, mu_s, nu, scatteringOrder), 1.0);
            }
        """.trimIndent()
    }
}
