package earth.worldwind.layer.sightline

import earth.worldwind.draw.DrawContext

/**
 * Reusable GLSL fragments that any program can splice into its fragment shader to make its
 * own pixels modulate against the active sightline's depth cube. Mirrors
 * [earth.worldwind.layer.shadow.ShadowReceiverGlsl] for sun shadows.
 *
 * The vertex shader pulls in [VERTEX_DECLARATIONS] and calls `emitSightlineVaryings(vertex)`
 * once per vertex (`vertex` is the position `sightlineLocalMatrix` expects — camera-relative
 * world for embedded receivers, tile-origin-relative for the terrain overlay pass). The
 * fragment shader pulls in [FRAGMENT_DECLARATIONS] and calls `computeSightlineTint()` to get
 * a premultiplied RGBA — `vec4(0)` means "no tint". Compose it over the surface colour:
 *
 *   vec4 tint = computeSightlineTint();
 *   gl_FragColor.rgb = gl_FragColor.rgb * (1.0 - tint.a) + tint.rgb;
 *   gl_FragColor.a = max(gl_FragColor.a, tint.a);
 *
 * Occlusion is resolved against a depth cube map (see [DrawContext.sightlineDepthCubeTexture]):
 * the fragment's sightline-local direction picks the cube texel, and the fragment's own window
 * depth along the dominant face axis is compared against the stored hardware depth with a 3x3
 * binomial tent PCF — the same design as the cascaded sun shadows, including the
 * `WW_SHADOW_SAMPLERS` hardware depth-compare variant (`samplerCubeShadow`, one compare per
 * tap in the sampler) with the in-shader `step` compare as the GLSL ES 1.00 fallback.
 *
 * Receivers gate the splice with `#ifdef SIGHTLINE_ENABLED` so a single GLSL source supports
 * both the receiver-aware and base variants.
 */
object SightlineReceiverGlsl {
    /** Preprocessor define enabled by each receiver program's `sightlineEnabled` flag. */
    const val SIGHTLINE_ENABLED_DEFINE = "#define SIGHTLINE_ENABLED\n"

    /**
     * Block to splice into the **vertex** shader. `sightlineLocalMatrix` maps the caller's
     * vertex position into the sightline-local frame (Z = up, origin at the sightline);
     * the caller computes its own `gl_Position` alongside.
     */
    val VERTEX_DECLARATIONS: String = """
        uniform mat4 sightlineLocalMatrix;

        varying vec3 sightlineLocalPos;

        void emitSightlineVaryings(vec4 vertexPos) {
            sightlineLocalPos = (sightlineLocalMatrix * vertexPos).xyz;
        }
    """.trimIndent()

    /**
     * Block to splice into the **fragment** shader. Provides `computeSightlineTint` which
     * returns the premultiplied tint to blend into the surface colour.
     *
     * The depth cube sampler lives on texture unit 5 (units 1..4 hold the shadow cascades);
     * the binding is baked in at program init time and the matching texture is bound via
     * [applySightlineReceiverUniforms] before draw.
     */
    val FRAGMENT_DECLARATIONS: String = """
        uniform bool applySightline;
        #ifdef WW_SHADOW_SAMPLERS
        uniform highp samplerCubeShadow sightlineDepthSampler;
        #else
        uniform highp samplerCube sightlineDepthSampler;
        #endif
        uniform float sightlineRange;
        uniform float sightlineDepthScale;    /* range/(range-1): window depth = scale*(1-1/d), near = 1 */
        uniform vec2 sightlineForwardAz;      /* unit horizontal forward of the wedge (directional) */
        uniform float sightlineCosHalfFov;    /* cos(fov/2); -1 disables the azimuth wedge (omni) */
        uniform float sightlineSinHalfFov;    /* sin(fov/2) elevation cap; 1 disables it (omni) */
        uniform vec4 sightlineColors[2];      /* [0] visible, [1] occluded; premultiplied */

        varying vec3 sightlineLocalPos;

        const float sightlineMapSize = ${DrawContext.SIGHTLINE_MAP_SIZE}.0;

        /**
         * Returns the premultiplied tint for this fragment. Caller composes it via
         *   gl_FragColor.rgb = gl_FragColor.rgb * (1 - tint.a) + tint.rgb;
         * `vec4(0)` short-circuits when the fragment is outside the sightline volume.
         */
        vec4 computeSightlineTint() {
            if (!applySightline) return vec4(0.0);
            vec3 local = sightlineLocalPos;
            /* Hoisted before any divergent branch so derivative quads stay coherent. */
            vec3 dLx = vec3(0.0);
            vec3 dLy = vec3(0.0);
            #ifdef WW_HAS_DERIVATIVES
            dLx = dFdx(sightlineLocalPos);
            dLy = dFdy(sightlineLocalPos);
            #endif
            float dist = length(local);
            /* Fragments inside the 1 m near plane get a negative reference depth below and
               resolve as visible - no explicit near mask needed, only division safety. */
            if (dist < 0.01 || dist >= sightlineRange) return vec4(0.0);
            /* Directional wedge: azimuth within fov/2 of forward, elevation below fov/2.
               Omni loads cosHalfFov = -1 and sinHalfFov = 1, which can never reject. */
            if (dot(local.xy, sightlineForwardAz) < sightlineCosHalfFov * length(local.xy)) return vec4(0.0);
            if (local.z > sightlineSinHalfFov * dist) return vec4(0.0);
            /* Distance along the dominant face axis - the depth the cube face stored. */
            vec3 a = abs(local);
            float dAxis = max(a.x, max(a.y, a.z));
            /* Tangent basis of the dominant face; one step shifts the lookup one texel. */
            vec3 t1;
            vec3 t2;
            if (a.x >= a.y && a.x >= a.z) { t1 = vec3(0.0, 1.0, 0.0); t2 = vec3(0.0, 0.0, 1.0); }
            else if (a.y >= a.z) { t1 = vec3(1.0, 0.0, 0.0); t2 = vec3(0.0, 0.0, 1.0); }
            else { t1 = vec3(1.0, 0.0, 0.0); t2 = vec3(0.0, 1.0, 0.0); }
            float texelStep = 2.0 * dAxis / sightlineMapSize;
            /* Receiver-plane slope: predict how the surface's axis distance changes per face
               texel so grazing receivers (street pavement) compare each PCF tap against the
               plane, not the fragment - the same cure as the sun receiver's dzduv slide. */
            vec2 dduv = vec2(0.0);
            #ifdef WW_HAS_DERIVATIVES
            vec3 axisV = (local - t1 * dot(local, t1) - t2 * dot(local, t2)) / dAxis;
            float u = dot(local, t1) / dAxis;
            float v = dot(local, t2) / dAxis;
            float dax = dot(axisV, dLx);
            float day = dot(axisV, dLy);
            float dux = (dot(t1, dLx) - u * dax) / dAxis;
            float duy = (dot(t1, dLy) - u * day) / dAxis;
            float dvx = (dot(t2, dLx) - v * dax) / dAxis;
            float dvy = (dot(t2, dLy) - v * day) / dAxis;
            float det = dux * dvy - duy * dvx;
            if (abs(det) > 1e-12) dduv = vec2(dax * dvy - day * dvx, dux * day - duy * dax) / det;
            #endif
            /* Per-texel slide in axis distance, clamped so silhouette discontinuities can't
               drag the prediction to another surface. */
            float duTexel = 2.0 / sightlineMapSize;
            vec2 slide = clamp(dduv * duTexel, vec2(-8.0 * texelStep), vec2(8.0 * texelStep));
            /* 3x3 binomial tent PCF; each tap compares against the plane-predicted depth,
               biased toward visible by ~two texels of world distance (dz/dd = scale/d^2). */
            float occluded = 0.0;
            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    vec3 dir = local + (t1 * float(i) + t2 * float(j)) * texelStep;
                    float dPred = max(dAxis + slide.x * float(i) + slide.y * float(j) - 2.0 * texelStep, 0.01);
                    float refDepth = sightlineDepthScale * (1.0 - 1.0 / dPred);
                    float w = (2.0 - abs(float(i))) * (2.0 - abs(float(j)));
                    #ifdef WW_SHADOW_SAMPLERS
                    /* LEQUAL compare returns visibility; the tap contributes its complement. */
                    occluded += w * (1.0 - texture(sightlineDepthSampler, vec4(dir, refDepth)));
                    #else
                    occluded += w * step(textureCube(sightlineDepthSampler, dir).r, refDepth);
                    #endif
                }
            }
            occluded /= 16.0;
            return mix(sightlineColors[0], sightlineColors[1], occluded);
        }
    """.trimIndent()
}
