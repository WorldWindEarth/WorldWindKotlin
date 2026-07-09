package earth.worldwind.layer.shadow

import earth.worldwind.draw.DrawContext

/**
 * Reusable GLSL fragments concatenated into every shadow receiver's fragment shader.
 *
 * Receiver vertex shader must emit:
 *  - `varying vec3 worldPos` — **camera-relative** world position (`worldVertex - cameraPoint`),
 *    computed from uniforms the drawable composed against the camera point in double precision
 *    on the CPU. A raw ECEF position quantizes to ~0.5 m in a float32 varying — enough to
 *    erase street-scale shadow structure entirely.
 *  - `varying float viewDepth` — positive distance to the camera plane (`gl_Position.w`)
 *
 * Receiver contract - one lighting model engine-wide (see
 * [earth.worldwind.render.program.LightingGlsl]): shadows attenuate only the direct sun
 * term, never the ambient term, so material Lambert and the cascade term can't
 * double-darken the same fragment.
 *  - Lit receivers call `shadowLitFactor(lambert, worldPos, viewDepth, worldNormal)` -
 *    the whole lighting multiplier with the sun term shadow-attenuated. Emitted when
 *    [fragmentDeclarations] is built with `lit = true`; requires `LightingGlsl.DECLARATIONS`
 *    earlier in the shader. A zero normal degrades to depth-only sampling.
 *  - Unlit receivers (terrain imagery, photogrammetry, points) call
 *    `shadowAlbedoFactor(worldPos, viewDepth)` - the shadow term is their only sun
 *    shading, so it darkens the whole albedo toward `ambientShadow`.
 *  - `shadowSunVisibility` overloads expose the raw `[0, 1]` visibility; the normal-aware
 *    overload adds a normal-offset receiver bias scaled by the active cascade's texel
 *    world size. All short-circuit to 1.0 when `applyShadow` is false.
 *
 * Occlusion resolve is a bilinear-weighted 3x3 percentage-closer filter — the exact sum of
 * nine hardware-PCF taps, evaluated over the 4x4 texel neighbourhood with separable tent
 * weights (16 fetches). Continuous everywhere, no per-pixel rotation noise, stable under
 * camera motion thanks to the cascade texel snapping.
 */
object ShadowReceiverGlsl {
    /** Number of cascades the shader code is unrolled for. Matches [ShadowState.DEFAULT_CASCADE_COUNT]. */
    const val CASCADE_COUNT = ShadowState.DEFAULT_CASCADE_COUNT

    /** Toggled by each receiver program's `shadowsEnabled` flag. */
    const val SHADOWS_ENABLED_DEFINE = "#define SHADOWS_ENABLED\n"

    /**
     * Receiver-side constant depth bias for opaque primitives (shapes, 3D-Tile meshes,
     * models), in units of the active cascade's `[0, 1]` depth range. Small because the
     * caster pass already applies slope-scaled polygon offset.
     */
    const val PRIMITIVE_DEPTH_BIAS = "2e-5"

    /**
     * Receiver-side constant depth bias for terrain-draped receivers — larger than the
     * primitive bias because terrain triangles are big and often nearly light-parallel.
     * Deliberately NOT scaled with view distance: normalized bias already grows with
     * cascade range, and distance scaling once ballooned to tens of km at globe range.
     */
    const val TERRAIN_DEPTH_BIAS = "1e-4"

    private fun mapSize(i: Int) = "vec2(${DrawContext.shadowCascadeMapSize(i)}.0)"

    /**
     * Builds the fragment-shader declaration block. [depthBias] is the receiver's constant
     * depth bias as a GLSL float literal. [lit] additionally emits `shadowLitFactor` for
     * receivers with a lighting model (requires `LightingGlsl.DECLARATIONS` above this block).
     */
    fun fragmentDeclarations(depthBias: String = PRIMITIVE_DEPTH_BIAS, lit: Boolean = false): String = """
        uniform bool applyShadow;
        uniform sampler2D shadowMap0;
        uniform sampler2D shadowMap1;
        uniform sampler2D shadowMap2;
        uniform sampler2D shadowMap3;
        uniform mat4 shadowMatrix0;
        uniform mat4 shadowMatrix1;
        uniform mat4 shadowMatrix2;
        uniform mat4 shadowMatrix3;
        uniform vec4 cascadeFarDepths;
        uniform vec4 cascadeTexelWorldSizes;
        /* Per-cascade kernel-slope bias in normalized depth units: the PCF kernel reads
           texels up to 1.5 away, where a ~45-degree surface legitimately sits closer to the
           sun by that many texel world-sizes. Computed on the CPU as
           kernelTexels * texelWorldSize / cascadeDepthRange. */
        uniform vec4 cascadeDepthBiases;
        uniform vec3 shadowLightDirection;
        uniform float shadowMaxDistance;
        uniform float ambientShadow;
        uniform int debugShadowMode; /* 0 off, 1 cascade bands, 2 footprint, 3 raw map depth */

        const vec2 shadowMapSize0 = ${mapSize(0)};
        const vec2 shadowMapSize1 = ${mapSize(1)};
        const vec2 shadowMapSize2 = ${mapSize(2)};
        const vec2 shadowMapSize3 = ${mapSize(3)};

        /* Constant receiver bias; glPolygonOffset in the caster pass carries the slope part. */
        const float shadowDepthBias = $depthBias;

        /* Normal-offset scale in cascade texels; grows toward light-grazing surfaces. */
        const float shadowNormalOffsetTexels = 2.0;

        /* World-space cap on the normal offset - uncapped, far-cascade multi-metre texels
           push samples along noisy photogrammetry normals and shimmer. */
        const float shadowNormalOffsetMax = 2.0;

        /* Bilinear-weighted 3x3 percentage-closer filter: the exact sum of nine 2x2
           hardware-PCF taps at 1-texel spacing, expanded to per-texel separable tent weights
           over the 4x4 neighbourhood - [1-f, 1, 1, f] per axis, normalised by 9. Smooth in
           both space and depth-edge crossing; no per-pixel rotation noise.

           [dzduv] is the receiver-plane depth gradient: each tap's comparison depth slides
           along the receiver's own surface plane, so sloped surfaces don't self-shadow
           against their neighbouring texels while chimney-scale contact shadows keep
           sub-texel precision. Clamped to [planeBiasClamp] so silhouette-crossing
           derivatives can't fling the prediction. */
        float shadowPcf(sampler2D shadowMap, vec2 uv, vec2 mapSize, float receiverDepth, vec2 dzduv, float planeBiasClamp) {
            vec2 st = uv * mapSize - 0.5;
            vec2 base = floor(st);
            vec2 f = st - base;
            vec4 wx = vec4(1.0 - f.x, 1.0, 1.0, f.x);
            vec4 wy = vec4(1.0 - f.y, 1.0, 1.0, f.y);
            vec2 invSize = 1.0 / mapSize;
            float sum = 0.0;
            for (int j = 0; j < 4; j++) {
                for (int i = 0; i < 4; i++) {
                    vec2 texelUV = (base + vec2(float(i) - 1.0, float(j) - 1.0) + 0.5) * invSize;
                    float casterDepth = texture2D(shadowMap, texelUV).r;
                    float tapDepth = receiverDepth + clamp(dot(texelUV - uv, dzduv), -planeBiasClamp, planeBiasClamp);
                    sum += wx[i] * wy[j] * step(tapDepth, casterDepth);
                }
            }
            return sum * (1.0 / 9.0);
        }

        /* Projects the camera-relative position into the chosen cascade and runs the filter.
           Returns -1.0 when the position lands outside the cascade's [0, 1]^3 footprint -
           "no coverage opinion", NOT "lit": the cascade-blend below must keep the current
           cascade's answer instead of fading a real shadow toward light.
           [kernelBias] is the selected cascade's texel-scale bias (cascadeDepthBiases) - the
           constant fallback where derivatives are unavailable / degenerate, and the clamp
           scale for the receiver-plane prediction. */
        float sampleCascade(int cascadeIndex, vec3 position, vec3 dPosDx, vec3 dPosDy, float viewDepth, float kernelBias) {
            vec4 shadowPos;
            vec3 posDx;
            vec3 posDy;
            if (cascadeIndex == 0) {
                shadowPos = shadowMatrix0 * vec4(position, 1.0);
                posDx = (shadowMatrix0 * vec4(dPosDx, 0.0)).xyz;
                posDy = (shadowMatrix0 * vec4(dPosDy, 0.0)).xyz;
            } else if (cascadeIndex == 1) {
                shadowPos = shadowMatrix1 * vec4(position, 1.0);
                posDx = (shadowMatrix1 * vec4(dPosDx, 0.0)).xyz;
                posDy = (shadowMatrix1 * vec4(dPosDy, 0.0)).xyz;
            } else if (cascadeIndex == 2) {
                shadowPos = shadowMatrix2 * vec4(position, 1.0);
                posDx = (shadowMatrix2 * vec4(dPosDx, 0.0)).xyz;
                posDy = (shadowMatrix2 * vec4(dPosDy, 0.0)).xyz;
            } else {
                shadowPos = shadowMatrix3 * vec4(position, 1.0);
                posDx = (shadowMatrix3 * vec4(dPosDx, 0.0)).xyz;
                posDy = (shadowMatrix3 * vec4(dPosDy, 0.0)).xyz;
            }
            /* Orthographic light projection - w == 1, no perspective divide. */
            if (any(lessThan(shadowPos.xyz, vec3(0.0))) || any(greaterThan(shadowPos.xyz, vec3(1.0)))) {
                return -1.0;
            }
            /* Receiver-plane depth gradient d(depth)/d(uv): solve the 2x2 system mapping
               screen deltas to shadow-UV deltas. The position derivatives are taken OUTSIDE
               the cascade branch and transformed analytically (derivatives are linear), so
               quads straddling a cascade boundary don't produce garbage gradients - raw
               dFdx(shadowPos) under divergent control flow painted a thin dark line along
               every cascade boundary. Degenerate for point sprites - constant fallback. */
            vec2 dzduv = vec2(0.0);
            float constBias = kernelBias;
            #ifdef WW_HAS_DERIVATIVES
            float det = posDx.x * posDy.y - posDx.y * posDy.x;
            if (abs(det) > 1e-16) {
                dzduv = vec2(posDy.y * posDx.z - posDx.y * posDy.z,
                             posDx.x * posDy.z - posDy.x * posDx.z) / det;
                /* The plane term carries the slope; keep only a small quantisation floor. */
                constBias = kernelBias * 0.25;
            }
            #endif
            float bias = constBias + shadowDepthBias;
            float receiverDepth = shadowPos.z - bias;
            float planeBiasClamp = kernelBias * 4.0;
            if (cascadeIndex == 0) return shadowPcf(shadowMap0, shadowPos.xy, shadowMapSize0, receiverDepth, dzduv, planeBiasClamp);
            else if (cascadeIndex == 1) return shadowPcf(shadowMap1, shadowPos.xy, shadowMapSize1, receiverDepth, dzduv, planeBiasClamp);
            else if (cascadeIndex == 2) return shadowPcf(shadowMap2, shadowPos.xy, shadowMapSize2, receiverDepth, dzduv, planeBiasClamp);
            else return shadowPcf(shadowMap3, shadowPos.xy, shadowMapSize3, receiverDepth, dzduv, planeBiasClamp);
        }

        /* Fraction of each cascade's depth range used as the blend zone with its successor. */
        const float cascadeBlendFraction = 0.1;

        /* Core resolve behind the public helpers, returning raw [0, 1] sun visibility.
           A vec3(0) normal disables the normal-offset bias. */
        float shadowVisibilityInternal(vec3 position, float viewDepth, vec3 worldNormal, float nDotL) {
            /* Hoisted before any divergent branch so derivative quads stay coherent. */
            vec3 dPosDx = vec3(0.0);
            vec3 dPosDy = vec3(0.0);
            #ifdef WW_HAS_DERIVATIVES
            dPosDx = dFdx(position);
            dPosDy = dFdy(position);
            #endif
            /* Coarse sanity cap only - the visual end of coverage is the last cascade's own
               footprint edge in sampleCascade, so no scalar distance can cut a shadow the
               maps can actually render. */
            if (viewDepth >= shadowMaxDistance * 2.0) return 1.0;
            /* Cascade selection. ESSL 1.00 fragment shaders can't index vectors dynamically,
               so the per-cascade texel sizes are picked inside the same branch chain. */
            int cascade;
            float cascadeNear;
            float cascadeFar;
            float texelWorld;
            float texelWorldNext;
            float texelWorldPrev;
            float kernelBias;
            float kernelBiasNext;
            float kernelBiasPrev;
            if (viewDepth < cascadeFarDepths.x) {
                cascade = 0; cascadeNear = 0.0;                cascadeFar = cascadeFarDepths.x;
                texelWorld = cascadeTexelWorldSizes.x; texelWorldNext = cascadeTexelWorldSizes.y;
                kernelBias = cascadeDepthBiases.x;     kernelBiasNext = cascadeDepthBiases.y;
                texelWorldPrev = 0.0;                  kernelBiasPrev = 0.0;
            } else if (viewDepth < cascadeFarDepths.y) {
                cascade = 1; cascadeNear = cascadeFarDepths.x; cascadeFar = cascadeFarDepths.y;
                texelWorld = cascadeTexelWorldSizes.y; texelWorldNext = cascadeTexelWorldSizes.z;
                kernelBias = cascadeDepthBiases.y;     kernelBiasNext = cascadeDepthBiases.z;
                texelWorldPrev = cascadeTexelWorldSizes.x; kernelBiasPrev = cascadeDepthBiases.x;
            } else if (viewDepth < cascadeFarDepths.z) {
                cascade = 2; cascadeNear = cascadeFarDepths.y; cascadeFar = cascadeFarDepths.z;
                texelWorld = cascadeTexelWorldSizes.z; texelWorldNext = cascadeTexelWorldSizes.w;
                kernelBias = cascadeDepthBiases.z;     kernelBiasNext = cascadeDepthBiases.w;
                texelWorldPrev = cascadeTexelWorldSizes.y; kernelBiasPrev = cascadeDepthBiases.y;
            } else {
                cascade = 3; cascadeNear = cascadeFarDepths.z; cascadeFar = cascadeFarDepths.w;
                texelWorld = cascadeTexelWorldSizes.w; texelWorldNext = 0.0;
                kernelBias = cascadeDepthBiases.w;     kernelBiasNext = 0.0;
                texelWorldPrev = cascadeTexelWorldSizes.z; kernelBiasPrev = cascadeDepthBiases.z;
            }
            if (debugShadowMode == 1) {
                /* Cascade layout view: 1.0 / 0.75 / 0.5 / 0.25 per cascade. */
                return 1.0 - float(cascade) * 0.25;
            }
            if (debugShadowMode >= 2) {
                vec4 dbgPos;
                if (cascade == 0) dbgPos = shadowMatrix0 * vec4(position, 1.0);
                else if (cascade == 1) dbgPos = shadowMatrix1 * vec4(position, 1.0);
                else if (cascade == 2) dbgPos = shadowMatrix2 * vec4(position, 1.0);
                else dbgPos = shadowMatrix3 * vec4(position, 1.0);
                bool outside = any(lessThan(dbgPos.xyz, vec3(0.0))) || any(greaterThan(dbgPos.xyz, vec3(1.0)));
                if (debugShadowMode == 2) return outside ? 0.1 : 1.0;
                /* Mode 3: project the raw shadow-map depth onto the scene - empty maps
                   read as uniform 1.0 (white); populated maps show caster silhouettes. */
                if (outside) return 0.0;
                float dbgDepth;
                if (cascade == 0) dbgDepth = texture2D(shadowMap0, dbgPos.xy).r;
                else if (cascade == 1) dbgDepth = texture2D(shadowMap1, dbgPos.xy).r;
                else if (cascade == 2) dbgDepth = texture2D(shadowMap2, dbgPos.xy).r;
                else dbgDepth = texture2D(shadowMap3, dbgPos.xy).r;
                return dbgDepth;
            }
            /* Normal-offset receiver bias: push the sample point toward the sun-facing side
               by up to [shadowNormalOffsetTexels] of the active cascade's texel world size,
               more for light-grazing surfaces. */
            float offsetStrength = (1.0 - nDotL) * shadowNormalOffsetTexels;
            vec3 position0 = position + worldNormal * min(offsetStrength * texelWorld, shadowNormalOffsetMax);
            float visibility = sampleCascade(cascade, position0, dPosDx, dPosDy, viewDepth, kernelBias);
            /* In the deepest [cascadeBlendFraction] of each cascade lerp toward the next
               cascade's visibility to hide the seam. Blend only between REAL coverage: the
               per-cascade snapped footprints are offset from each other, so blend-zone (and
               slice-entry sliver) positions can fall outside one of them - a -1.0 "no
               coverage" there must defer to whichever cascade has data instead of fading
               the shadow toward lit. The last cascade has no successor and keeps sampling
               past its slice; its own footprint edge is the end of coverage. */
            if (cascade < 3) {
                float blendStart = cascadeFar - cascadeBlendFraction * (cascadeFar - cascadeNear);
                float t = smoothstep(blendStart, cascadeFar, viewDepth);
                if (t > 0.0 || visibility < 0.0) {
                    vec3 position1 = position + worldNormal * min(offsetStrength * texelWorldNext, shadowNormalOffsetMax);
                    float visibilityNext = sampleCascade(cascade + 1, position1, dPosDx, dPosDy, viewDepth, kernelBiasNext);
                    if (visibility < 0.0) visibility = visibilityNext;
                    else if (visibilityNext >= 0.0) visibility = mix(visibility, visibilityNext, t);
                }
            }
            /* Snapped footprints are offset per cascade, so a position depth-assigned to one
               cascade can sit outside its box while the PREVIOUS cascade still covers it
               (depth windows carry sun-side slack; on fast zoom-in the near cascade exits
               first). Prefer real neighbour data over dropping the shadow. */
            if (visibility < 0.0 && cascade > 0) {
                /* Bias and offset must be the PREVIOUS cascade's - its finer texels need a
                   proportionally smaller reach, or fallback pixels read lighter than their
                   normally-sampled neighbours. */
                vec3 positionPrev = position + worldNormal * min(offsetStrength * texelWorldPrev, shadowNormalOffsetMax);
                visibility = sampleCascade(cascade - 1, positionPrev, dPosDx, dPosDy, viewDepth, kernelBiasPrev);
            }
            /* Still no coverage: genuinely beyond every footprint - lit. */
            if (visibility < 0.0) visibility = 1.0;
            return visibility;
        }

        /* Raw sun visibility - 1.0 lit, 0.0 occluded - for receivers without a normal. */
        float shadowSunVisibility(vec3 position, float viewDepth) {
            if (!applyShadow) return 1.0;
            return shadowVisibilityInternal(position, viewDepth, vec3(0.0), 1.0);
        }

        /* Raw sun visibility with a texel-scaled normal-offset receiver bias. A zero
           normal degrades to the depth-only resolve. */
        float shadowSunVisibility(vec3 position, float viewDepth, vec3 worldNormal) {
            if (!applyShadow) return 1.0;
            float nDotL = clamp(dot(worldNormal, shadowLightDirection), 0.0, 1.0);
            return shadowVisibilityInternal(position, viewDepth, worldNormal, nDotL);
        }

        /* Whole-albedo factor for unlit receivers (terrain imagery, photogrammetry,
           points): the shadow term is their only sun shading, floored at ambientShadow. */
        float shadowAlbedoFactor(vec3 position, float viewDepth) {
            float visibility = shadowSunVisibility(position, viewDepth);
            if (applyShadow && debugShadowMode != 0) return visibility;
            return mix(ambientShadow, 1.0, visibility);
        }
    """.trimIndent() + if (lit) "\n" + """
        /* Lit receivers: the full lighting multiplier with only the direct sun term
           shadow-attenuated. Lambert owns the dark side, so back faces can never be
           double-darkened by the cascade term. */
        float shadowLitFactor(float lambert, vec3 position, float viewDepth, vec3 worldNormal) {
            float visibility = shadowSunVisibility(position, viewDepth, worldNormal);
            if (applyShadow && debugShadowMode != 0) return visibility;
            return litShadingFactor(lambert, visibility);
        }
    """.trimIndent() else ""
}
