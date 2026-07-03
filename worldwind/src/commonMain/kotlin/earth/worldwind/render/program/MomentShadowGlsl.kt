package earth.worldwind.render.program

/**
 * The Hamburger 4-moment Cholesky occlusion reconstruction (Peters & Klein 2015) — the single most
 * precision-fragile fragment in the engine. Adreno-class and iOS Metal-backed GLES3 compilers
 * reorder its catastrophic cancellation, so it lives in exactly one place and is spliced verbatim
 * into every shadow / sightline receiver that needs it (the 2D cascade receiver and the sightline
 * 2D + cube-map receivers).
 *
 * [OCCLUDE_MASK_FUNCTION] defines `float msmOccludeMask(vec4 moments, float z0, float momentBias)`:
 * given the raw filtered `moments`, the receiver depth `z0` **already biased by the caller** (each
 * receiver subtracts its own platform depth bias), and the platform `momentBias`, it returns the
 * occlusion fraction `0` (visible) … `1` (fully occluded). Callers that want *visibility* return
 * `1.0 - msmOccludeMask(...)`.
 *
 * The arithmetic must stay byte-identical across edits: reordering the operands can flip the sign of
 * the cancellation on the fragile compilers above, so treat any change here as touching every
 * receiver at once and re-verify shadows + sightlines on Adreno / iOS.
 *
 * Wrapped in a `#ifndef` include guard: some programs (e.g. the 3D-Tiles receivers) splice BOTH the
 * cascade-shadow and the sightline receiver blocks into one fragment shader, so the definition would
 * otherwise appear twice and fail to link ("function already has a body").
 */
object MomentShadowGlsl {
    val OCCLUDE_MASK_FUNCTION: String = """
        #ifndef MSM_OCCLUDE_MASK_DEFINED
        #define MSM_OCCLUDE_MASK_DEFINED
        float msmOccludeMask(vec4 moments, float z0, float momentBias) {
            vec4 b = mix(moments, vec4(0.5, 0.333333333, 0.25, 0.2), momentBias);
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
            return clamp(sw.z + sw.w * quotient, 0.0, 1.0);
        }
        #endif
    """.trimIndent()
}
