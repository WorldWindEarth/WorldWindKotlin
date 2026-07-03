package earth.worldwind.layer.sightline

import earth.worldwind.layer.shadow.defaultSightlineMomentBias
import earth.worldwind.render.program.MomentShadowGlsl

/**
 * Reusable GLSL fragments that any program can splice into its fragment shader to make its
 * own pixels modulate against the active sightline's moments map. Mirrors
 * [earth.worldwind.layer.shadow.ShadowReceiverGlsl] for sun shadows.
 *
 * The vertex shader pulls in [VERTEX_DECLARATIONS] and calls `emitSightlineVaryings(vertex)`
 * once per vertex (`vertex` is the model-space position to be transformed). The fragment
 * shader pulls in [FRAGMENT_DECLARATIONS] and calls `computeSightlineTint()` to get a
 * premultiplied RGBA — `vec4(0)` means "no tint". Compose it over the surface colour:
 *
 *   vec4 tint = computeSightlineTint();
 *   gl_FragColor.rgb = gl_FragColor.rgb * (1.0 - tint.a) + tint.rgb;
 *   gl_FragColor.a = max(gl_FragColor.a, tint.a);
 *
 * Receivers gate the splice with `#ifdef SIGHTLINE_ENABLED` so a single GLSL source supports
 * both the receiver-aware and base variants.
 */
object SightlineReceiverGlsl {
    /** Preprocessor define enabled by each receiver program's `sightlineEnabled` flag. */
    const val SIGHTLINE_ENABLED_DEFINE = "#define SIGHTLINE_ENABLED\n"

    /**
     * Block to splice into the **vertex** shader. Pulls the sightline-related varyings.
     * Caller's vertex shader provides `sightlineModelMatrix` (model -> world transform of
     * the receiver) and computes its own `gl_Position`; this block adds the sightline
     * varying outputs alongside.
     */
    val VERTEX_DECLARATIONS: String = """
        uniform mat4 sightlineMvMatrix;       /* sightlineView * modelMatrix; world for receivers in world coords */
        uniform mat4 sightlineProjMatrix;     /* cubeMapProjection (directional) */
        uniform mat4 sightlineLocalMatrix;    /* inv(centerTransform) * modelMatrix (omni) */
        uniform bool sightlineOmnidirectional;

        varying vec4 sightlinePosition;
        varying vec3 sightlineLocalPos;
        varying float sightlineDistance;

        void emitSightlineVaryings(vec4 vertexPos) {
            vec4 ep = sightlineMvMatrix * vertexPos;
            sightlineDistance = length(ep);
            if (sightlineOmnidirectional) {
                sightlineLocalPos = (sightlineLocalMatrix * vertexPos).xyz;
                sightlinePosition = vec4(0.0);
            } else {
                sightlinePosition = sightlineProjMatrix * ep;
                sightlineLocalPos = vec3(0.0);
            }
        }
    """.trimIndent()

    /**
     * Block to splice into the **fragment** shader. Provides `computeSightlineTint` which
     * returns the premultiplied tint to additively blend into the surface colour.
     *
     * Texture units: `sightlineMomentsSampler` = unit 4 (2D for directional path),
     * `sightlineMomentsCubeSampler` = unit 5 (cube for omni path). Both bindings are baked
     * in at program init time; the caller binds the matching textures via
     * [applySightlineReceiverUniforms] before draw.
     */
    val FRAGMENT_DECLARATIONS: String = """
        uniform bool applySightline;
        uniform bool sightlineOmnidirectional;
        uniform sampler2D sightlineMomentsSampler;
        uniform samplerCube sightlineMomentsCubeSampler;
        uniform float sightlineRange;
        uniform vec4 sightlineColors[2];      /* [0] visible, [1] occluded; premultiplied */

        varying vec4 sightlinePosition;
        varying vec3 sightlineLocalPos;
        varying float sightlineDistance;

        const vec3 sightlineMinusOne = vec3(-1.0, -1.0, -1.0);
        const vec3 sightlinePlusOne  = vec3( 1.0,  1.0,  1.0);
        /* Platform-templated via [defaultSightlineMomentBias]: IEEE-strict 3e-5 on JVM/JS/
           Android/desktop; 3e-2 on iOS Mac Simulator's Metal-backed GLES3 only, where the
           Cholesky reorder produces light-leak noise even in sightline's tight depth range. */
        const float sightlineMomentBias = $defaultSightlineMomentBias;
        const float sightlineDepthBias  = 1e-5;

        /* Hamburger 4-moment occlusion bound (0 = visible, 1 = fully occluded) - shared verbatim
           with the cascade-shadow receiver via [earth.worldwind.render.program.MomentShadowGlsl]. */
        ${MomentShadowGlsl.OCCLUDE_MASK_FUNCTION}

        /**
         * Returns the premultiplied tint for this fragment. Caller adds it via
         *   gl_FragColor.rgb = gl_FragColor.rgb * (1 - tint.a) + tint.rgb;
         * `vec4(0)` short-circuits when the fragment is outside the sightline volume.
         */
        vec4 computeSightlineTint() {
            if (!applySightline) return vec4(0.0);
            if (sightlineOmnidirectional) {
                float rangeMask = step(length(sightlineLocalPos), sightlineRange);
                vec3 absLocal = abs(sightlineLocalPos);
                float upMask = 1.0 - step(max(absLocal.x, absLocal.y), sightlineLocalPos.z);
                vec4 moments = textureCube(sightlineMomentsCubeSampler, sightlineLocalPos);
                float z0 = max(absLocal.x, max(absLocal.y, absLocal.z)) / sightlineRange;
                float occludeMask = msmOccludeMask(moments, z0 - sightlineDepthBias, sightlineMomentBias);
                return mix(sightlineColors[0], sightlineColors[1], occludeMask) * rangeMask * upMask;
            } else {
                vec3 clipCoord = sightlinePosition.xyz / sightlinePosition.w;
                vec3 clipMaskV = step(sightlineMinusOne, clipCoord) * step(clipCoord, sightlinePlusOne);
                float clipMask = clipMaskV.x * clipMaskV.y * clipMaskV.z;
                float rangeMask = step(sightlineDistance, sightlineRange);
                vec3 sampleCoord = clipCoord * 0.5 + 0.5;
                vec4 moments = texture2D(sightlineMomentsSampler, sampleCoord.xy);
                float z0 = sightlinePosition.w / sightlineRange;
                float occludeMask = msmOccludeMask(moments, z0 - sightlineDepthBias, sightlineMomentBias);
                return mix(sightlineColors[0], sightlineColors[1], occludeMask) * clipMask * rangeMask;
            }
        }
    """.trimIndent()
}