package earth.worldwind.layer.shadow

import earth.worldwind.draw.DrawContext

/**
 * Reusable GLSL fragments concatenated into every shadow receiver's fragment shader so all
 * receivers compute occlusion the same way.
 *
 * Conventions the receiver's vertex shader must follow:
 *  - emit `varying vec3 worldPos` = world-space Cartesian position (`vertexOrigin + localPosition`).
 *  - emit `varying float viewDepth` = positive distance to the camera plane (`gl_Position.w`
 *    for a standard perspective projection).
 *
 * The receiver fragment shader calls [computeShadowVisibility] which returns 1.0 fully lit,
 * [ambientShadow] fully occluded, smoothly varying in between. When `applyShadow` is false
 * it short-circuits to 1.0; receivers can call it unconditionally.
 *
 * Two algorithms are available, selected at runtime via the `useMSM` uniform: 9-tap rotated
 * PCF (default; portable) or Hamburger 4-moment Cholesky (smoother penumbra; precision-fragile
 * on Adreno-class shader compilers). The cascade depth pass writes linear caster depth to
 * `moments.x`, which both algorithms read.
 */
object ShadowReceiverGlsl {
    /** Number of cascades the shader code is unrolled for. Matches [ShadowState.DEFAULT_CASCADE_COUNT]. */
    const val CASCADE_COUNT = ShadowState.DEFAULT_CASCADE_COUNT

    private val texel0 = (1.0 / DrawContext.SHADOW_CASCADE_MAP_SIZES[0]).toFloat()
    private val texel1 = (1.0 / DrawContext.SHADOW_CASCADE_MAP_SIZES[1]).toFloat()
    private val texel2 = (1.0 / DrawContext.SHADOW_CASCADE_MAP_SIZES[2]).toFloat()

    val FRAGMENT_DECLARATIONS: String = """
        uniform bool applyShadow;
        /* Selects the receiver path: false = PCF, true = MSM. Driven by [ShadowLayer.algorithm]. */
        uniform bool useMSM;
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

        /* Per-cascade texel size in normalised UV units. Constants from [DrawContext.SHADOW_CASCADE_MAP_SIZES];
           the PCF kernel scales offsets by this so penumbra width stays consistent across cascade resolutions. */
        const vec2 cascadeTexelSize0 = vec2($texel0);
        const vec2 cascadeTexelSize1 = vec2($texel1);
        const vec2 cascadeTexelSize2 = vec2($texel2);

        /* PCF tunables. [pcfDepthBias] sized for the 3x3 kernel - a sloped receiver projects
           different depths across the kernel; without enough bias half the taps register as
           shadowed even on a fully-lit slope (~50% gray artifact). The trade-off is mild
           peter-panning on steep silhouettes. */
        const float pcfKernelRadius = 2.5;
        const float pcfDepthBias = 5e-3;

        /* MSM tunables. Reference biases work on IEEE-FP-strict compilers (JVM / desktop GL /
           WebGL2). Adreno-class compilers reorder the Cholesky's catastrophic-cancellation
           subtractions and produce noise at this bias - PCF is the appropriate choice there. */
        const float msmMomentBias = 3e-5;
        const float msmDepthBias = 1e-5;

        /* Hamburger 4-moment Cholesky reconstruction (Peters & Klein 2015).
           Returns 1.0 = visible, 0.0 = fully occluded. */
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
            return 1.0 - occludeMask;
        }

        /* 9-tap PCF (3x3 grid) with per-pixel grid rotation. The rotation hides the kernel
           pattern - without it neighbouring fragments sample identical offsets and the grid
           shows as a regular dither. Common industry-default penumbra reconstruction. */
        float pcfShadow(sampler2D shadowMap, vec2 shadowUV, float receiverDepth, vec2 texelSize) {
            float angle = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) * 6.28318530718;
            float cosA = cos(angle);
            float sinA = sin(angle);
            float sum = 0.0;
            for (int j = 0; j < 3; j++) {
                for (int i = 0; i < 3; i++) {
                    vec2 grid = vec2(float(i) - 1.0, float(j) - 1.0);
                    vec2 rotated = vec2(cosA * grid.x - sinA * grid.y,
                                        sinA * grid.x + cosA * grid.y);
                    vec2 offset = rotated * pcfKernelRadius * texelSize;
                    float casterDepth = texture2D(shadowMap, shadowUV + offset).x;
                    sum += step(receiverDepth, casterDepth + pcfDepthBias);
                }
            }
            return sum * (1.0 / 9.0);
        }

        /* Projects worldPos into the chosen cascade's shadow space and runs the active
           reconstruction (PCF or MSM). Returns 1.0 when worldPos lands outside the [0, 1]^2
           footprint - the cascade picker is expected to choose an in-bounds cascade. */
        float sampleCascade(int cascadeIndex, vec3 worldPos) {
            vec4 lightClip;
            if (cascadeIndex == 0) lightClip = lightProjectionView0 * vec4(worldPos, 1.0);
            else if (cascadeIndex == 1) lightClip = lightProjectionView1 * vec4(worldPos, 1.0);
            else lightClip = lightProjectionView2 * vec4(worldPos, 1.0);
            /* Orthographic light projection - w == 1, no perspective divide. */
            vec3 ndc = lightClip.xyz;
            vec2 shadowUV = ndc.xy * 0.5 + 0.5;
            float receiverDepth = ndc.z * 0.5 + 0.5;
            if (any(lessThan(shadowUV, vec2(0.0))) || any(greaterThan(shadowUV, vec2(1.0)))
                || receiverDepth < 0.0 || receiverDepth > 1.0) {
                return 1.0;
            }
            if (useMSM) {
                vec4 moments;
                if (cascadeIndex == 0) moments = texture2D(shadowMap0, shadowUV);
                else if (cascadeIndex == 1) moments = texture2D(shadowMap1, shadowUV);
                else moments = texture2D(shadowMap2, shadowUV);
                return msmOcclusion(moments, receiverDepth);
            }
            if (cascadeIndex == 0) return pcfShadow(shadowMap0, shadowUV, receiverDepth, cascadeTexelSize0);
            else if (cascadeIndex == 1) return pcfShadow(shadowMap1, shadowUV, receiverDepth, cascadeTexelSize1);
            else return pcfShadow(shadowMap2, shadowUV, receiverDepth, cascadeTexelSize2);
        }

        /* Fraction of each cascade's depth range used as the blend zone with the next cascade.
           Larger = softer transition at the cost of double-sampling more fragments. */
        const float cascadeBlendFraction = 0.15;

        float computeShadowVisibility(vec3 worldPos, float viewDepth) {
            if (!applyShadow) return 1.0;
            int cascade;
            float cascadeNear;
            float cascadeFar;
            if (viewDepth < cascadeFarDepth0) {
                cascade = 0; cascadeNear = 0.0;             cascadeFar = cascadeFarDepth0;
            } else if (viewDepth < cascadeFarDepth1) {
                cascade = 1; cascadeNear = cascadeFarDepth0; cascadeFar = cascadeFarDepth1;
            } else if (viewDepth < cascadeFarDepth2) {
                cascade = 2; cascadeNear = cascadeFarDepth1; cascadeFar = cascadeFarDepth2;
            } else {
                return 1.0; /* beyond all cascades -> unshadowed */
            }
            float visibility = sampleCascade(cascade, worldPos);
            /* Smooth boundary: in the deepest [cascadeBlendFraction] of this cascade lerp
               toward the next cascade's visibility to hide the seam. */
            if (cascade < 2) {
                float blendStart = cascadeFar - cascadeBlendFraction * (cascadeFar - cascadeNear);
                float t = smoothstep(blendStart, cascadeFar, viewDepth);
                if (t > 0.0) {
                    float visibilityNext = sampleCascade(cascade + 1, worldPos);
                    visibility = mix(visibility, visibilityNext, t);
                }
            }
            return mix(ambientShadow, 1.0, visibility);
        }
    """.trimIndent()
}
