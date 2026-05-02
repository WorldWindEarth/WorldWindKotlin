/*
 * Adapted from ebruneton/precomputed_atmospheric_scattering, BSD-3-Clause,
 * Copyright (c) 2017 Eric Bruneton.
 *
 * Shared GLSL — physical constants, density profiles, ray / sphere geometry, parameterization
 * helpers — that every Bruneton precomputation and runtime kernel splices into its source.
 * Mirrors the reference repo's `definitions.glsl` + `functions.glsl` split into a single
 * Kotlin string so we don't have to plumb #include support through `AbstractShaderProgram`.
 *
 * Constants are interpolated from [BrunetonAtmosphereConstants] so a single edit there
 * propagates to every kernel without re-writing the shader strings.
 */
package earth.worldwind.layer.atmosphere.bruneton

import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.BOTTOM_RADIUS
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.GROUND_ALBEDO
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.IRRADIANCE_TEXTURE_HEIGHT
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.IRRADIANCE_TEXTURE_WIDTH
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.MIE_EXTINCTION
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.MIE_PHASE_FUNCTION_G
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.MIE_SCALE_HEIGHT
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.MIE_SCATTERING
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.MU_S_MIN
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.OZONE_ABSORPTION_B
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.OZONE_ABSORPTION_G
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.OZONE_ABSORPTION_R
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.OZONE_BOTTOM_LAYER_WIDTH
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.OZONE_LAYER_TOP_HEIGHT
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.RAYLEIGH_SCALE_HEIGHT
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.RAYLEIGH_SCATTERING_B
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.RAYLEIGH_SCATTERING_G
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.RAYLEIGH_SCATTERING_R
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.SCATTERING_TEXTURE_MU_SIZE
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.SCATTERING_TEXTURE_MU_S_SIZE
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.SCATTERING_TEXTURE_NU_SIZE
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.SCATTERING_TEXTURE_R_SIZE
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.SOLAR_IRRADIANCE_B
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.SOLAR_IRRADIANCE_G
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.SOLAR_IRRADIANCE_R
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.SUN_ANGULAR_RADIUS
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.TOP_RADIUS
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.TRANSMITTANCE_TEXTURE_HEIGHT
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereConstants.TRANSMITTANCE_TEXTURE_WIDTH
import kotlin.math.pow

internal object BrunetonShaders {

    /**
     * Fullscreen-triangle vertex shader (matches [earth.worldwind.render.program.ViewshedKernelShaderProgram]).
     * No vertex buffer required — the three positions come from `gl_VertexID` ∈ {0,1,2}.
     */
    val FULLSCREEN_TRIANGLE_VERTEX = """
        precision highp float;
        const vec2 VERTS[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
        void main() { gl_Position = vec4(VERTS[gl_VertexID], 0.0, 1.0); }
    """.trimIndent()

    /**
     * Common GLSL header: physical constants, density profile, ray-sphere geometry, and the
     * (r, μ) ↔ UV parameter mapping for the transmittance LUT. Subsequent phases will append
     * the irradiance, scattering, and multiple-scattering parameterizations / lookups here.
     */
    val COMMON = """
        // ---- Atmosphere physical constants (compiled into shader) -------------------------
        const float BOTTOM_RADIUS = ${f(BOTTOM_RADIUS)};
        const float TOP_RADIUS    = ${f(TOP_RADIUS)};

        const float RAYLEIGH_SCALE_HEIGHT = ${f(RAYLEIGH_SCALE_HEIGHT)};
        const vec3  RAYLEIGH_SCATTERING   = vec3(${f(RAYLEIGH_SCATTERING_R)}, ${f(RAYLEIGH_SCATTERING_G)}, ${f(RAYLEIGH_SCATTERING_B)});

        const float MIE_SCALE_HEIGHT = ${f(MIE_SCALE_HEIGHT)};
        const float MIE_SCATTERING   = ${f(MIE_SCATTERING)};
        const float MIE_EXTINCTION   = ${f(MIE_EXTINCTION)};
        const float MIE_PHASE_G      = ${f(MIE_PHASE_FUNCTION_G)};

        const float OZONE_BOTTOM_LAYER_WIDTH = ${f(OZONE_BOTTOM_LAYER_WIDTH)};
        const float OZONE_LAYER_TOP_HEIGHT   = ${f(OZONE_LAYER_TOP_HEIGHT)};
        const vec3  OZONE_ABSORPTION         = vec3(${f(OZONE_ABSORPTION_R)}, ${f(OZONE_ABSORPTION_G)}, ${f(OZONE_ABSORPTION_B)});

        const float SUN_ANGULAR_RADIUS = ${f(SUN_ANGULAR_RADIUS)};
        const vec3  SOLAR_IRRADIANCE   = vec3(${f(SOLAR_IRRADIANCE_R)}, ${f(SOLAR_IRRADIANCE_G)}, ${f(SOLAR_IRRADIANCE_B)});

        const float MU_S_MIN = ${f(MU_S_MIN)};
        const float GROUND_ALBEDO = ${f(GROUND_ALBEDO)};

        const int TRANSMITTANCE_TEXTURE_WIDTH  = $TRANSMITTANCE_TEXTURE_WIDTH;
        const int TRANSMITTANCE_TEXTURE_HEIGHT = $TRANSMITTANCE_TEXTURE_HEIGHT;
        const int IRRADIANCE_TEXTURE_WIDTH     = $IRRADIANCE_TEXTURE_WIDTH;
        const int IRRADIANCE_TEXTURE_HEIGHT    = $IRRADIANCE_TEXTURE_HEIGHT;
        const int SCATTERING_TEXTURE_R_SIZE    = $SCATTERING_TEXTURE_R_SIZE;
        const int SCATTERING_TEXTURE_MU_SIZE   = $SCATTERING_TEXTURE_MU_SIZE;
        const int SCATTERING_TEXTURE_MU_S_SIZE = $SCATTERING_TEXTURE_MU_S_SIZE;
        const int SCATTERING_TEXTURE_NU_SIZE   = $SCATTERING_TEXTURE_NU_SIZE;

        // ---- Numeric helpers --------------------------------------------------------------
        float ClampCosine(float mu)         { return clamp(mu, -1.0, 1.0); }
        float ClampDistance(float d)        { return max(d, 0.0); }
        float ClampRadius(float r)          { return clamp(r, BOTTOM_RADIUS, TOP_RADIUS); }
        float SafeSqrt(float a)             { return sqrt(max(a, 0.0)); }

        // ---- Density profile (single layer for Rayleigh / Mie, tent for ozone) ------------
        // A density profile is two layers; layer 0 covers altitudes [0, layer0.width),
        // layer 1 covers [layer0.width, ∞). Each layer's density at altitude h is
        //   layer.exp_term * exp(layer.exp_scale * h) + layer.linear_term * h + layer.constant_term
        // clamped to [0, 1].
        struct DensityLayer {
            float width;
            float exp_term;
            float exp_scale;
            float linear_term;
            float constant_term;
        };
        struct DensityProfile { DensityLayer layers[2]; };

        float GetLayerDensity(DensityLayer layer, float altitude) {
            float density = layer.exp_term * exp(layer.exp_scale * altitude) +
                            layer.linear_term * altitude + layer.constant_term;
            return clamp(density, 0.0, 1.0);
        }

        float GetProfileDensity(DensityProfile profile, float altitude) {
            return altitude < profile.layers[0].width
                ? GetLayerDensity(profile.layers[0], altitude)
                : GetLayerDensity(profile.layers[1], altitude);
        }

        // The three Earth profiles, hard-coded from the reference. Layer-0.width = 0 means
        // "no transition" (Rayleigh / Mie are single-layer exponentials, so layer 1 is the
        // active one for all altitudes). Ozone is genuinely two-layer.
        DensityProfile RayleighDensityProfile() {
            DensityLayer empty = DensityLayer(0.0, 0.0, 0.0, 0.0, 0.0);
            DensityLayer layer = DensityLayer(0.0, 1.0, -1.0 / RAYLEIGH_SCALE_HEIGHT, 0.0, 0.0);
            return DensityProfile(DensityLayer[2](empty, layer));
        }
        DensityProfile MieDensityProfile() {
            DensityLayer empty = DensityLayer(0.0, 0.0, 0.0, 0.0, 0.0);
            DensityLayer layer = DensityLayer(0.0, 1.0, -1.0 / MIE_SCALE_HEIGHT, 0.0, 0.0);
            return DensityProfile(DensityLayer[2](empty, layer));
        }
        DensityProfile OzoneDensityProfile() {
            float w0 = OZONE_BOTTOM_LAYER_WIDTH;            // 25 km
            float top = OZONE_LAYER_TOP_HEIGHT;             // 40 km
            float halfWidth = top - w0;                     // 15 km
            // Layer 0: ramp up   (h / 15 km)
            DensityLayer l0 = DensityLayer(w0, 0.0, 0.0, 1.0 / halfWidth, 0.0);
            // Layer 1: ramp down (-h / 15 km + 40/15)
            DensityLayer l1 = DensityLayer(0.0, 0.0, 0.0, -1.0 / halfWidth, top / halfWidth);
            return DensityProfile(DensityLayer[2](l0, l1));
        }

        // ---- Ray geometry -----------------------------------------------------------------
        // r: distance from Earth's centre to the camera (≥ BOTTOM_RADIUS).
        // mu: cosine of the angle between the view direction and the up direction at the camera.
        // mu_s: cosine of the angle between the sun direction and the up direction.
        // nu: cosine of the angle between the view direction and the sun direction.

        float DistanceToTopAtmosphereBoundary(float r, float mu) {
            float discriminant = r * r * (mu * mu - 1.0) + TOP_RADIUS * TOP_RADIUS;
            return ClampDistance(-r * mu + SafeSqrt(discriminant));
        }
        float DistanceToBottomAtmosphereBoundary(float r, float mu) {
            float discriminant = r * r * (mu * mu - 1.0) + BOTTOM_RADIUS * BOTTOM_RADIUS;
            return ClampDistance(-r * mu - SafeSqrt(discriminant));
        }
        bool RayIntersectsGround(float r, float mu) {
            return mu < 0.0 && r * r * (mu * mu - 1.0) + BOTTOM_RADIUS * BOTTOM_RADIUS >= 0.0;
        }

        // ---- Optical depth integration -----------------------------------------------------
        // Trapezoidal rule over 500 samples — overkill for runtime, fine for one-time precomp.
        float ComputeOpticalLengthToTopAtmosphereBoundary(DensityProfile profile, float r, float mu) {
            const int SAMPLE_COUNT = 500;
            float dx = DistanceToTopAtmosphereBoundary(r, mu) / float(SAMPLE_COUNT);
            float result = 0.0;
            for (int i = 0; i <= SAMPLE_COUNT; ++i) {
                float d_i = float(i) * dx;
                float r_i = sqrt(d_i * d_i + 2.0 * r * mu * d_i + r * r);
                float y_i = GetProfileDensity(profile, r_i - BOTTOM_RADIUS);
                float weight_i = (i == 0 || i == SAMPLE_COUNT) ? 0.5 : 1.0;
                result += y_i * weight_i * dx;
            }
            return result;
        }

        // ---- (r, μ) ↔ transmittance LUT UV ------------------------------------------------
        // Bruneton's parameterization with horizon awareness: more samples near the horizon,
        // where the rapid optical-depth variation needs higher resolution.
        float GetTextureCoordFromUnitRange(float x, int texture_size) {
            return 0.5 / float(texture_size) + x * (1.0 - 1.0 / float(texture_size));
        }
        float GetUnitRangeFromTextureCoord(float u, int texture_size) {
            return (u - 0.5 / float(texture_size)) / (1.0 - 1.0 / float(texture_size));
        }
        vec2 GetTransmittanceTextureUvFromRMu(float r, float mu) {
            float H = sqrt(TOP_RADIUS * TOP_RADIUS - BOTTOM_RADIUS * BOTTOM_RADIUS);
            float rho = SafeSqrt(r * r - BOTTOM_RADIUS * BOTTOM_RADIUS);
            float d = DistanceToTopAtmosphereBoundary(r, mu);
            float d_min = TOP_RADIUS - r;
            float d_max = rho + H;
            float x_mu = (d - d_min) / (d_max - d_min);
            float x_r  = rho / H;
            return vec2(
                GetTextureCoordFromUnitRange(x_mu, TRANSMITTANCE_TEXTURE_WIDTH),
                GetTextureCoordFromUnitRange(x_r,  TRANSMITTANCE_TEXTURE_HEIGHT)
            );
        }
        void GetRMuFromTransmittanceTextureUv(vec2 uv, out float r, out float mu) {
            float x_mu = GetUnitRangeFromTextureCoord(uv.x, TRANSMITTANCE_TEXTURE_WIDTH);
            float x_r  = GetUnitRangeFromTextureCoord(uv.y, TRANSMITTANCE_TEXTURE_HEIGHT);
            float H = sqrt(TOP_RADIUS * TOP_RADIUS - BOTTOM_RADIUS * BOTTOM_RADIUS);
            float rho = H * x_r;
            r = sqrt(rho * rho + BOTTOM_RADIUS * BOTTOM_RADIUS);
            float d_min = TOP_RADIUS - r;
            float d_max = rho + H;
            float d = d_min + x_mu * (d_max - d_min);
            mu = d == 0.0 ? 1.0 : (H * H - rho * rho - d * d) / (2.0 * r * d);
            mu = ClampCosine(mu);
        }

        // ---- Transmittance LUT lookups ----------------------------------------------------
        // Sampled in every kernel that needs T(P, top) or T(P, Q) along a ray.

        const float PI = 3.14159265358979323846;

        vec3 GetTransmittanceToTopAtmosphereBoundary(sampler2D tx_T, float r, float mu) {
            vec2 uv = GetTransmittanceTextureUvFromRMu(r, mu);
            return texture(tx_T, uv).rgb;
        }

        // T(P,Q) along a ray of length d, where the ray either reaches the atmosphere top
        // (intersects_ground=false) or the ground (intersects_ground=true). Uses the
        // T(P,Q) = T(P, top) / T(Q, top) factorization, so the LUT only stores T-to-top.
        vec3 GetTransmittance(sampler2D tx_T, float r, float mu, float d, bool intersects_ground) {
            float r_d = ClampRadius(sqrt(d * d + 2.0 * r * mu * d + r * r));
            float mu_d = ClampCosine((r * mu + d) / r_d);
            if (intersects_ground) {
                return min(
                    GetTransmittanceToTopAtmosphereBoundary(tx_T, r_d, -mu_d) /
                    GetTransmittanceToTopAtmosphereBoundary(tx_T, r,   -mu),
                    vec3(1.0)
                );
            } else {
                return min(
                    GetTransmittanceToTopAtmosphereBoundary(tx_T, r,   mu) /
                    GetTransmittanceToTopAtmosphereBoundary(tx_T, r_d, mu_d),
                    vec3(1.0)
                );
            }
        }

        // T(P, sun) — additionally smoothstep'd against the sun disk's apparent angular size,
        // so the precomputed LUT captures the soft horizon-cutoff visibility of the sun.
        vec3 GetTransmittanceToSun(sampler2D tx_T, float r, float mu_s) {
            float sin_theta_h = BOTTOM_RADIUS / r;
            float cos_theta_h = -sqrt(max(1.0 - sin_theta_h * sin_theta_h, 0.0));
            return GetTransmittanceToTopAtmosphereBoundary(tx_T, r, mu_s) *
                   smoothstep(-sin_theta_h * SUN_ANGULAR_RADIUS, sin_theta_h * SUN_ANGULAR_RADIUS,
                              mu_s - cos_theta_h);
        }

        // ---- Phase functions --------------------------------------------------------------
        // Both normalized so ∫ phase(nu) dΩ = 1 over the sphere.

        float RayleighPhaseFunction(float nu) {
            float k = 3.0 / (16.0 * PI);
            return k * (1.0 + nu * nu);
        }
        float MiePhaseFunction(float g, float nu) {
            float k = 3.0 / (8.0 * PI) * (1.0 - g * g) / (2.0 + g * g);
            return k * (1.0 + nu * nu) / pow(1.0 + g * g - 2.0 * g * nu, 1.5);
        }

        // ---- Single-scattering integration (along view ray) -------------------------------
        // Used by single-scattering kernel AND by indirect-irradiance kernel (which integrates
        // single-scattering radiance × phase × cos(theta) over the upper hemisphere).

        float DistanceToNearestAtmosphereBoundary(float r, float mu, bool intersects_ground) {
            return intersects_ground
                ? DistanceToBottomAtmosphereBoundary(r, mu)
                : DistanceToTopAtmosphereBoundary(r, mu);
        }

        void ComputeSingleScatteringIntegrand(
            sampler2D tx_T, float r, float mu, float mu_s, float nu, float d,
            bool intersects_ground, out vec3 rayleigh, out vec3 mie
        ) {
            float r_d = ClampRadius(sqrt(d * d + 2.0 * r * mu * d + r * r));
            float mu_s_d = ClampCosine((r * mu_s + d * nu) / r_d);
            vec3 transmittance = GetTransmittance(tx_T, r, mu, d, intersects_ground) *
                                 GetTransmittanceToSun(tx_T, r_d, mu_s_d);
            rayleigh = transmittance * GetProfileDensity(RayleighDensityProfile(), r_d - BOTTOM_RADIUS);
            mie      = transmittance * GetProfileDensity(MieDensityProfile(),      r_d - BOTTOM_RADIUS);
        }

        void ComputeSingleScattering(
            sampler2D tx_T, float r, float mu, float mu_s, float nu, bool intersects_ground,
            out vec3 rayleigh, out vec3 mie
        ) {
            const int SAMPLE_COUNT = 50;
            float dx = DistanceToNearestAtmosphereBoundary(r, mu, intersects_ground) / float(SAMPLE_COUNT);
            vec3 rayleigh_sum = vec3(0.0);
            vec3 mie_sum      = vec3(0.0);
            for (int i = 0; i <= SAMPLE_COUNT; ++i) {
                float d_i = float(i) * dx;
                vec3 r_i, m_i;
                ComputeSingleScatteringIntegrand(tx_T, r, mu, mu_s, nu, d_i, intersects_ground, r_i, m_i);
                float w = (i == 0 || i == SAMPLE_COUNT) ? 0.5 : 1.0;
                rayleigh_sum += r_i * w;
                mie_sum      += m_i * w;
            }
            rayleigh = rayleigh_sum * dx * SOLAR_IRRADIANCE * RAYLEIGH_SCATTERING;
            mie      = mie_sum      * dx * SOLAR_IRRADIANCE * MIE_SCATTERING;
        }

        // ---- Scattering 4D ↔ 3D LUT parameterization (r, μ, μ_s, ν) -----------------------
        // The LUT is laid out as W = mu_s × nu (the two angular params combined into one
        // texture x), H = mu, D = r. Bruneton's mapping uses non-linear stretching so the
        // horizon and sun-zenith regions get more samples.

        vec4 GetScatteringTextureUvwzFromRMuMuSNu(
            float r, float mu, float mu_s, float nu, bool intersects_ground
        ) {
            float H = sqrt(TOP_RADIUS * TOP_RADIUS - BOTTOM_RADIUS * BOTTOM_RADIUS);
            float rho = SafeSqrt(r * r - BOTTOM_RADIUS * BOTTOM_RADIUS);
            float u_r = GetTextureCoordFromUnitRange(rho / H, SCATTERING_TEXTURE_R_SIZE);
            float r_mu = r * mu;
            float discriminant = r_mu * r_mu - r * r + BOTTOM_RADIUS * BOTTOM_RADIUS;
            float u_mu;
            if (intersects_ground) {
                float d = -r_mu - SafeSqrt(discriminant);
                float d_min = r - BOTTOM_RADIUS;
                float d_max = rho;
                u_mu = 0.5 - 0.5 * GetTextureCoordFromUnitRange(
                    d_max == d_min ? 0.0 : (d - d_min) / (d_max - d_min),
                    SCATTERING_TEXTURE_MU_SIZE / 2);
            } else {
                float d = -r_mu + SafeSqrt(discriminant + H * H);
                float d_min = TOP_RADIUS - r;
                float d_max = rho + H;
                u_mu = 0.5 + 0.5 * GetTextureCoordFromUnitRange(
                    (d - d_min) / (d_max - d_min), SCATTERING_TEXTURE_MU_SIZE / 2);
            }
            float d = DistanceToTopAtmosphereBoundary(BOTTOM_RADIUS, mu_s);
            float d_min = TOP_RADIUS - BOTTOM_RADIUS;
            float d_max = H;
            float a = (d - d_min) / (d_max - d_min);
            float D = DistanceToTopAtmosphereBoundary(BOTTOM_RADIUS, MU_S_MIN);
            float A = (D - d_min) / (d_max - d_min);
            float u_mu_s = GetTextureCoordFromUnitRange(
                max(1.0 - a / A, 0.0) / (1.0 + a), SCATTERING_TEXTURE_MU_S_SIZE);
            float u_nu = (nu + 1.0) / 2.0;
            return vec4(u_nu, u_mu_s, u_mu, u_r);
        }

        void GetRMuMuSNuFromScatteringTextureUvwz(
            vec4 uvwz, out float r, out float mu, out float mu_s, out float nu,
            out bool intersects_ground
        ) {
            float H = sqrt(TOP_RADIUS * TOP_RADIUS - BOTTOM_RADIUS * BOTTOM_RADIUS);
            float rho = H * GetUnitRangeFromTextureCoord(uvwz.w, SCATTERING_TEXTURE_R_SIZE);
            r = sqrt(rho * rho + BOTTOM_RADIUS * BOTTOM_RADIUS);
            if (uvwz.z < 0.5) {
                float d_min = r - BOTTOM_RADIUS;
                float d_max = rho;
                float d = d_min + (d_max - d_min) * GetUnitRangeFromTextureCoord(
                    1.0 - 2.0 * uvwz.z, SCATTERING_TEXTURE_MU_SIZE / 2);
                mu = d == 0.0 ? -1.0 : ClampCosine(-(rho * rho + d * d) / (2.0 * r * d));
                intersects_ground = true;
            } else {
                float d_min = TOP_RADIUS - r;
                float d_max = rho + H;
                float d = d_min + (d_max - d_min) * GetUnitRangeFromTextureCoord(
                    2.0 * uvwz.z - 1.0, SCATTERING_TEXTURE_MU_SIZE / 2);
                mu = d == 0.0 ? 1.0 : ClampCosine((H * H - rho * rho - d * d) / (2.0 * r * d));
                intersects_ground = false;
            }
            float x_mu_s = GetUnitRangeFromTextureCoord(uvwz.y, SCATTERING_TEXTURE_MU_S_SIZE);
            float d_min2 = TOP_RADIUS - BOTTOM_RADIUS;
            float d_max2 = H;
            float D = DistanceToTopAtmosphereBoundary(BOTTOM_RADIUS, MU_S_MIN);
            float A = (D - d_min2) / (d_max2 - d_min2);
            float a = (A - x_mu_s * A) / (1.0 + x_mu_s * A);
            float d = d_min2 + min(a, A) * (d_max2 - d_min2);
            mu_s = d == 0.0 ? 1.0 : ClampCosine((H * H - d * d) / (2.0 * BOTTOM_RADIUS * d));
            nu = ClampCosine(uvwz.x * 2.0 - 1.0);
        }

        // Reconstruct (r, μ, μ_s, ν) from a 3D-texture fragment coordinate. nu is then
        // clamped against (mu, mu_s) consistency: cos(ν) must lie in [cos(θ_v + θ_s),
        // cos(θ_v - θ_s)] where θ_v = acos(mu), θ_s = acos(mu_s).
        void GetRMuMuSNuFromScatteringTextureFragCoord(
            vec3 frag_coord,
            out float r, out float mu, out float mu_s, out float nu, out bool intersects_ground
        ) {
            const vec4 SCATTERING_TEXTURE_SIZE = vec4(
                float(SCATTERING_TEXTURE_NU_SIZE - 1),
                float(SCATTERING_TEXTURE_MU_S_SIZE),
                float(SCATTERING_TEXTURE_MU_SIZE),
                float(SCATTERING_TEXTURE_R_SIZE)
            );
            float frag_coord_nu  = floor(frag_coord.x / float(SCATTERING_TEXTURE_MU_S_SIZE));
            float frag_coord_mus = mod(frag_coord.x,   float(SCATTERING_TEXTURE_MU_S_SIZE));
            vec4 uvwz = vec4(frag_coord_nu, frag_coord_mus, frag_coord.y, frag_coord.z) /
                        SCATTERING_TEXTURE_SIZE;
            GetRMuMuSNuFromScatteringTextureUvwz(uvwz, r, mu, mu_s, nu, intersects_ground);
            nu = clamp(nu,
                       mu * mu_s - sqrt((1.0 - mu * mu) * (1.0 - mu_s * mu_s)),
                       mu * mu_s + sqrt((1.0 - mu * mu) * (1.0 - mu_s * mu_s)));
        }

        // 4D bilerp implemented as two 3D texture fetches blended along the nu axis (the
        // axis that's compressed into the texture-x coordinate). Reference uses this for
        // both single-scattering and multi-scattering lookups.
        vec3 GetScattering(
            sampler3D tex, float r, float mu, float mu_s, float nu, bool intersects_ground
        ) {
            vec4 uvwz = GetScatteringTextureUvwzFromRMuMuSNu(r, mu, mu_s, nu, intersects_ground);
            float tex_coord_x = uvwz.x * float(SCATTERING_TEXTURE_NU_SIZE - 1);
            float tex_x = floor(tex_coord_x);
            float lerp = tex_coord_x - tex_x;
            vec3 uvw0 = vec3((tex_x       + uvwz.y) / float(SCATTERING_TEXTURE_NU_SIZE), uvwz.z, uvwz.w);
            vec3 uvw1 = vec3((tex_x + 1.0 + uvwz.y) / float(SCATTERING_TEXTURE_NU_SIZE), uvwz.z, uvwz.w);
            return texture(tex, uvw0).rgb * (1.0 - lerp) + texture(tex, uvw1).rgb * lerp;
        }

        // Order-aware scattering lookup used by ScatteringDensity and runtime sampling: at
        // order 1, returns single scattering Rayleigh × phase + Mie × phase reconstructed
        // from the two LUTs. At order ≥ 2, returns the multi-scattering LUT directly (which
        // already has the phase function divided out, so the caller multiplies by Rayleigh
        // phase as a prior — see ComputeMultipleScattering).
        vec3 GetScatteringByOrder(
            sampler3D tex_rayleigh, sampler3D tex_mie, sampler3D tex_multiple,
            float r, float mu, float mu_s, float nu, bool intersects_ground, int order
        ) {
            if (order == 1) {
                vec3 rayleigh = GetScattering(tex_rayleigh, r, mu, mu_s, nu, intersects_ground);
                vec3 mie      = GetScattering(tex_mie,      r, mu, mu_s, nu, intersects_ground);
                return rayleigh * RayleighPhaseFunction(nu) + mie * MiePhaseFunction(MIE_PHASE_G, nu);
            } else {
                return GetScattering(tex_multiple, r, mu, mu_s, nu, intersects_ground);
            }
        }

        // ---- (r, μ_s) ↔ irradiance LUT UV -------------------------------------------------

        vec2 GetIrradianceTextureUvFromRMuS(float r, float mu_s) {
            float x_r    = (r - BOTTOM_RADIUS) / (TOP_RADIUS - BOTTOM_RADIUS);
            float x_mu_s = mu_s * 0.5 + 0.5;
            return vec2(GetTextureCoordFromUnitRange(x_mu_s, IRRADIANCE_TEXTURE_WIDTH),
                        GetTextureCoordFromUnitRange(x_r,    IRRADIANCE_TEXTURE_HEIGHT));
        }
        void GetRMuSFromIrradianceTextureUv(vec2 uv, out float r, out float mu_s) {
            float x_mu_s = GetUnitRangeFromTextureCoord(uv.x, IRRADIANCE_TEXTURE_WIDTH);
            float x_r    = GetUnitRangeFromTextureCoord(uv.y, IRRADIANCE_TEXTURE_HEIGHT);
            r    = BOTTOM_RADIUS + x_r * (TOP_RADIUS - BOTTOM_RADIUS);
            mu_s = ClampCosine(2.0 * x_mu_s - 1.0);
        }
        vec3 GetIrradiance(sampler2D tex_irr, float r, float mu_s) {
            return texture(tex_irr, GetIrradianceTextureUvFromRMuS(r, mu_s)).rgb;
        }

        // ---- Runtime sky / ground sampling ------------------------------------------------
        // Splits the 4D scattering LUT into Rayleigh+multi (in `scattering_texture`,
        // phase-divided) and pure single Mie (in `mie_scattering_texture`); the caller
        // multiplies by the local nu's Rayleigh / Mie phase function to recover radiance.

        vec3 GetCombinedScattering(
            sampler3D scattering_texture,
            sampler3D mie_scattering_texture,
            float r, float mu, float mu_s, float nu, bool intersects_ground,
            out vec3 single_mie_scattering
        ) {
            vec4 uvwz = GetScatteringTextureUvwzFromRMuMuSNu(r, mu, mu_s, nu, intersects_ground);
            float tex_coord_x = uvwz.x * float(SCATTERING_TEXTURE_NU_SIZE - 1);
            float tex_x = floor(tex_coord_x);
            float lerp = tex_coord_x - tex_x;
            vec3 uvw0 = vec3((tex_x       + uvwz.y) / float(SCATTERING_TEXTURE_NU_SIZE), uvwz.z, uvwz.w);
            vec3 uvw1 = vec3((tex_x + 1.0 + uvwz.y) / float(SCATTERING_TEXTURE_NU_SIZE), uvwz.z, uvwz.w);
            vec3 scattering =
                texture(scattering_texture, uvw0).rgb * (1.0 - lerp) +
                texture(scattering_texture, uvw1).rgb * lerp;
            single_mie_scattering =
                texture(mie_scattering_texture, uvw0).rgb * (1.0 - lerp) +
                texture(mie_scattering_texture, uvw1).rgb * lerp;
            return scattering;
        }

        // Sky radiance from `camera` looking in direction `view_ray`, plus the transmittance
        // along that ray to the atmosphere top (returned via the [transmittance] out param).
        // The `shadow_length` parameter (matching the reference signature) lets the caller
        // omit in-scatter contribution from a shadow shaft of that length near the camera —
        // used in the reference for cloud-shadow / volumetric-shaft rendering. WorldWind's
        // sky/ground passes always pass 0, but the parameter is here so the API is faithful.
        vec3 GetSkyRadiance(
            sampler2D transmittance_texture,
            sampler3D scattering_texture,
            sampler3D mie_scattering_texture,
            vec3 camera, vec3 view_ray, float shadow_length, vec3 sun_direction,
            out vec3 transmittance
        ) {
            // If the camera is in space, intersect the view ray with the atmosphere top and
            // shift `camera` to the entry point. If the ray misses the atmosphere entirely,
            // sky radiance is zero (and transmittance is the unattenuated 1.0).
            float r = length(camera);
            float rmu = dot(camera, view_ray);
            float distance_to_top_atmosphere = -rmu - sqrt(rmu * rmu - r * r + TOP_RADIUS * TOP_RADIUS);
            if (distance_to_top_atmosphere > 0.0) {
                camera = camera + view_ray * distance_to_top_atmosphere;
                r = TOP_RADIUS;
                rmu += distance_to_top_atmosphere;
            } else if (r > TOP_RADIUS) {
                transmittance = vec3(1.0);
                return vec3(0.0);
            }
            float mu = rmu / r;
            float mu_s = dot(camera, sun_direction) / r;
            float nu = dot(view_ray, sun_direction);
            bool ray_r_mu_intersects_ground = RayIntersectsGround(r, mu);

            transmittance = ray_r_mu_intersects_ground
                ? vec3(0.0)
                : GetTransmittanceToTopAtmosphereBoundary(transmittance_texture, r, mu);

            vec3 single_mie_scattering;
            vec3 scattering;
            if (shadow_length == 0.0) {
                scattering = GetCombinedScattering(
                    scattering_texture, mie_scattering_texture,
                    r, mu, mu_s, nu, ray_r_mu_intersects_ground, single_mie_scattering);
            } else {
                // Walk past the shadow segment — sample scattering as if the ray started
                // at (camera + view_ray · shadow_length), then attenuate by the shadow-
                // segment transmittance so the contribution from inside the shadow drops.
                float d = shadow_length;
                float r_p = ClampRadius(sqrt(d * d + 2.0 * r * mu * d + r * r));
                float mu_p = (r * mu + d) / r_p;
                float mu_s_p = (r * mu_s + d * nu) / r_p;
                scattering = GetCombinedScattering(
                    scattering_texture, mie_scattering_texture,
                    r_p, mu_p, mu_s_p, nu, ray_r_mu_intersects_ground, single_mie_scattering);
                vec3 shadow_transmittance = GetTransmittance(
                    transmittance_texture, r, mu, shadow_length, ray_r_mu_intersects_ground);
                scattering = scattering * shadow_transmittance;
                single_mie_scattering = single_mie_scattering * shadow_transmittance;
            }

            return scattering * RayleighPhaseFunction(nu) +
                   single_mie_scattering * MiePhaseFunction(MIE_PHASE_G, nu);
        }

        // Sky radiance from camera to a specific atmosphere / terrain `point` (used by the
        // ground shader for aerial perspective). Subtracts scattering beyond `point`. The
        // `shadow_length` parameter has the same role as in [GetSkyRadiance].
        vec3 GetSkyRadianceToPoint(
            sampler2D transmittance_texture,
            sampler3D scattering_texture,
            sampler3D mie_scattering_texture,
            vec3 camera, vec3 point, float shadow_length, vec3 sun_direction,
            out vec3 transmittance
        ) {
            vec3 view_ray = normalize(point - camera);
            float r = length(camera);
            float rmu = dot(camera, view_ray);
            float distance_to_top_atmosphere = -rmu - sqrt(rmu * rmu - r * r + TOP_RADIUS * TOP_RADIUS);
            if (distance_to_top_atmosphere > 0.0) {
                camera = camera + view_ray * distance_to_top_atmosphere;
                r = TOP_RADIUS;
                rmu += distance_to_top_atmosphere;
            }
            float mu  = rmu / r;
            float mu_s = dot(camera, sun_direction) / r;
            float nu  = dot(view_ray, sun_direction);
            float d   = length(point - camera);
            bool ray_r_mu_intersects_ground = RayIntersectsGround(r, mu);

            transmittance = GetTransmittance(transmittance_texture, r, mu, d, ray_r_mu_intersects_ground);

            vec3 single_mie_scattering;
            vec3 scattering = GetCombinedScattering(
                scattering_texture, mie_scattering_texture,
                r, mu, mu_s, nu, ray_r_mu_intersects_ground, single_mie_scattering);

            // Compute scattering at the far endpoint and subtract: leaves only the segment
            // from camera to point. Same trick as the transmittance LUT factorization.
            float d_endpoint = max(d - shadow_length, 0.0);
            float r_p   = ClampRadius(sqrt(d_endpoint * d_endpoint + 2.0 * r * mu * d_endpoint + r * r));
            float mu_p  = (r * mu + d_endpoint) / r_p;
            float mu_s_p = (r * mu_s + d_endpoint * nu) / r_p;
            vec3 single_mie_scattering_p;
            vec3 scattering_p = GetCombinedScattering(
                scattering_texture, mie_scattering_texture,
                r_p, mu_p, mu_s_p, nu, ray_r_mu_intersects_ground, single_mie_scattering_p);

            // Subtract scattering past the shadow-clipped endpoint from the camera-side
            // scattering. With `shadow_length = 0`, d_endpoint = d and this gives the
            // standard `[camera, point]` segment contribution.
            vec3 shadow_transmittance = transmittance;
            if (shadow_length > 0.0) {
                shadow_transmittance = GetTransmittance(
                    transmittance_texture, r, mu, d, ray_r_mu_intersects_ground);
            }
            scattering = scattering - shadow_transmittance * scattering_p;
            single_mie_scattering = single_mie_scattering - shadow_transmittance * single_mie_scattering_p;
            // Hack from the reference: avoid Mie-scattering ringing at the horizon by
            // smoothly cutting it off when the view ray passes near the ground from below.
            single_mie_scattering = single_mie_scattering * smoothstep(0.0, 0.01, mu_s);

            return scattering * RayleighPhaseFunction(nu) +
                   single_mie_scattering * MiePhaseFunction(MIE_PHASE_G, nu);
        }

        // Sun + sky irradiance on a horizontal patch at altitude r with surface normal
        // `normal` and sun direction `sun_direction`. Used by the ground shader to compute
        // direct + ambient contribution before applying surface albedo.
        vec3 GetSunAndSkyIrradiance(
            sampler2D transmittance_texture,
            sampler2D irradiance_texture,
            vec3 point, vec3 normal, vec3 sun_direction,
            out vec3 sky_irradiance
        ) {
            float r = length(point);
            float mu_s = dot(point, sun_direction) / r;
            // Indirect (sky) irradiance — clamped by surface tilt so a surface tilted away
            // from the dome receives a proportionally smaller sky contribution.
            sky_irradiance = GetIrradiance(irradiance_texture, r, mu_s) *
                             (1.0 + dot(normal, point) / r) * 0.5;
            // Direct sun: SOLAR_IRRADIANCE × T(point→sun) × max(N·sun, 0).
            return SOLAR_IRRADIANCE *
                   GetTransmittanceToSun(transmittance_texture, r, mu_s) *
                   max(dot(normal, sun_direction), 0.0);
        }

        // Bruneton's standard exposure tonemap: linear radiance → ~sRGB. With the reference's
        // physical units, an `exposure` of ~10 produces well-balanced output.
        vec3 BrunetonTonemap(vec3 radiance, float exposure) {
            vec3 linear = vec3(1.0) - exp(-radiance * exposure);
            return pow(linear, vec3(1.0 / 2.2));
        }
    """.trimIndent()

    /**
     * Format a `Double` as a GLSL `float` literal — always with a decimal point so
     * the GLSL parser doesn't mistake `8000` for an int. Avoids per-call `toString()`
     * issues with locale-sensitive separators (Kotlin `Double.toString` is locale-free
     * on JVM but JS / Android may print integers without a fractional part).
     */
    private fun f(v: Double): String {
        // Use scientific notation for very small magnitudes to keep the literal short
        // without losing precision (the reference uses `5.802e-6` style). For larger
        // values, plain decimal form is fine — Kotlin's Double.toString is locale-free
        // and never inserts grouping separators.
        val abs = if (v < 0.0) -v else v
        val s = if (abs != 0.0 && abs < 1e-3) {
            val exp = kotlin.math.floor(kotlin.math.log10(abs)).toInt()
            val mantissa = v / 10.0.pow(exp)
            "${mantissa}e${exp}"
        } else {
            v.toString()
        }
        return if ('.' in s || 'e' in s || 'E' in s) s else "$s.0"
    }
}
