package earth.worldwind.layer.shadow

/**
 * Reusable GLSL fragments for shadow receivers (terrain, lit shapes, unlit shapes). Each
 * receiver shader concatenates these chunks into its source so all receivers compute occlusion
 * the same way and only one Cholesky implementation needs maintenance.
 *
 * Conventions:
 *  - The vertex shader emits a varying `vec3 worldPos` containing the fragment's world-space
 *    Cartesian position (i.e. `vertexOrigin + localPosition`). The receiver's fragment shader
 *    transforms this into each cascade's clip space on demand.
 *  - The vertex shader emits a varying `float viewDepth = -eyeSpaceZ` (positive distance to
 *    the camera plane); receivers select a cascade by comparing this against the per-cascade
 *    far depth uniform.
 *  - Cascade textures are bound to the texture units passed in the per-receiver uniforms.
 *  - When `applyShadow` is `false` (no [ShadowLayer] active for the frame) the helper returns
 *    `1.0` (fully lit) without sampling, so receivers can call it unconditionally and pay
 *    only one branch.
 *
 * The Cholesky reconstruction is identical to [earth.worldwind.render.program.SightlineProgramCube]
 * — the same Hamburger 4-moment formulation produces the same `occludeMask ∈ [0, 1]` here.
 * `1.0 - occludeMask` is the visibility (lighting) factor; that's what receivers multiply
 * their lit term by.
 */
object ShadowReceiverGlsl {
    /** Number of cascades the receivers' shader code is unrolled for. Matches [ShadowState.DEFAULT_CASCADE_COUNT]. */
    const val CASCADE_COUNT = ShadowState.DEFAULT_CASCADE_COUNT

    /**
     * Fragment-shader uniforms and helper function. Declares:
     *  - `bool applyShadow` — false disables shadow sampling.
     *  - `sampler2D shadowMap[CASCADE_COUNT]` — cascade moments textures.
     *  - `mat4 lightProjectionView[CASCADE_COUNT]` — world → light-clip per cascade.
     *  - `float cascadeFarDepth[CASCADE_COUNT]` — view-space far depth per cascade.
     *  - `float ambientShadow` — colour multiplier for fully-occluded fragments [0, 1].
     *
     * Returns `float computeShadowVisibility(vec3 worldPos, float viewDepth)`:
     * `1.0` for fully lit, `ambientShadow` for fully occluded, smoothly varying in between.
     */
    val FRAGMENT_DECLARATIONS: String = """
        uniform bool applyShadow;
        uniform sampler2D shadowMap0;
        uniform sampler2D shadowMap1;
        uniform sampler2D shadowMap2;
        uniform mat4 lightProjectionView0;
        uniform mat4 lightProjectionView1;
        uniform mat4 lightProjectionView2;
        uniform float cascadeFarDepth0;
        uniform float cascadeFarDepth1;
        uniform float cascadeFarDepth2;
        uniform float ambientShadow;

        /* Hamburger MSM bias (Peters & Klein 2015). Same constants as the sightline cube
           receiver - full-float storage tolerates very small biases. */
        const float msmMomentBias = 3e-5;
        const float msmDepthBias = 1e-5;

        /* Cholesky 4-moment occlusion. Returns 1.0 = visible, 0.0 = fully occluded. */
        float msmOcclusion(vec4 moments, float receiverDepth) {
            vec4 b = mix(moments, vec4(0.5, 0.333333333, 0.25, 0.2), msmMomentBias);
            float z0 = receiverDepth - msmDepthBias;
            float L32D22 = b.z - b.x * b.y;
            float D22 = b.y - b.x * b.x;
            float D33D22 = (b.w - b.y * b.y) * D22 - L32D22 * L32D22;
            float invD22 = 1.0 / D22;
            float L32 = L32D22 * invD22;
            vec3 c = vec3(1.0, z0, z0 * z0);
            c.y -= b.x;
            c.z -= b.y + L32 * c.y;
            c.y *= invD22;
            c.z *= D22 / D33D22;
            c.y -= L32 * c.z;
            c.x -= dot(c.yz, b.xy);
            float p = c.y / c.z;
            float q = c.x / c.z;
            float r = sqrt(p * p * 0.25 - q);
            float z1 = -p * 0.5 - r;
            float z2 = -p * 0.5 + r;
            vec4 sw = (z2 < z0) ? vec4(z1, z0, 1.0, 1.0)
                    : (z1 < z0) ? vec4(z0, z1, 0.0, 1.0)
                    : vec4(0.0);
            float quotient = (sw.x * z2 - b.x * (sw.x + z2) + b.y)
                           / ((z2 - sw.y) * (z0 - z1));
            float occludeMask = clamp(sw.z + sw.w * quotient, 0.0, 1.0);
            /* 1.0 - occludeMask = visibility (lighting factor). */
            return 1.0 - occludeMask;
        }

        /* Maps a world-space position into the chosen cascade's [0, 1]^3 shadow space and
           returns 1.0 when the position lands outside the [0, 1]^2 footprint (fragment lit
           by sky-light only - i.e. fully visible to the cascade gate but still subject to
           the next-cascade lookup if the caller chose to chain). */
        float sampleCascade(int cascadeIndex, vec3 worldPos) {
            vec4 lightClip;
            if (cascadeIndex == 0) lightClip = lightProjectionView0 * vec4(worldPos, 1.0);
            else if (cascadeIndex == 1) lightClip = lightProjectionView1 * vec4(worldPos, 1.0);
            else lightClip = lightProjectionView2 * vec4(worldPos, 1.0);
            /* Orthographic light projection -> w == 1, no perspective divide needed. */
            vec3 ndc = lightClip.xyz;
            vec2 shadowUV = ndc.xy * 0.5 + 0.5;
            float receiverDepth = ndc.z * 0.5 + 0.5;
            /* Outside cascade footprint -> treat as visible; the cascade picker promotes
               the fragment to a wider cascade in that case, so the visible result here is
               fine when the picker lands on the closest in-bounds cascade. */
            if (any(lessThan(shadowUV, vec2(0.0))) || any(greaterThan(shadowUV, vec2(1.0)))
                || receiverDepth < 0.0 || receiverDepth > 1.0) {
                return 1.0;
            }
            vec4 moments;
            if (cascadeIndex == 0) moments = texture2D(shadowMap0, shadowUV);
            else if (cascadeIndex == 1) moments = texture2D(shadowMap1, shadowUV);
            else moments = texture2D(shadowMap2, shadowUV);
            return msmOcclusion(moments, receiverDepth);
        }

        /* Cascade picker: linearly compare viewDepth (positive distance to camera plane)
           against per-cascade far depths. Returns the visibility factor in [0, 1]. */
        float computeShadowVisibility(vec3 worldPos, float viewDepth) {
            if (!applyShadow) return 1.0;
            int cascade;
            if (viewDepth < cascadeFarDepth0) cascade = 0;
            else if (viewDepth < cascadeFarDepth1) cascade = 1;
            else if (viewDepth < cascadeFarDepth2) cascade = 2;
            else return 1.0; /* beyond all cascades -> unshadowed */
            float visibility = sampleCascade(cascade, worldPos);
            /* Mix the shadowed value toward the ambient floor so fully-occluded fragments
               still receive [ambientShadow] amount of "skylight" rather than going pure black. */
            return mix(ambientShadow, 1.0, visibility);
        }
    """.trimIndent()
}
