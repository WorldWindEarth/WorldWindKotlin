package earth.worldwind.render.program

/**
 * Engine-wide lighting model shared by every lit fragment shader:
 * `albedo * (hemisphericAmbient + sceneDiffuse * lambert * sunVisibility)`.
 *
 * The sun-visibility factor (cascade shadows) attenuates only the direct term; the
 * ambient term is shadow-independent. A fully shadowed lit face and a sun-averted face
 * both converge to the ambient brightness instead of multiplying two independent
 * darkening floors - which is what used to read as "double shading" on meshes.
 *
 * Ambient is hemispheric: up-facing surfaces receive more sky light than down-facing
 * ones (`mix(sceneAmbientDown, sceneAmbientUp, dot(N, up) * 0.5 + 0.5)`), averaging
 * the former flat 0.35. Callers pass `upFactor` precomputed in whatever space their
 * lighting normal lives in (0.5 when no usable normal).
 */
object LightingGlsl {
    /** Fragment-shader snippet; include unconditionally, before any shadow declarations. */
    val DECLARATIONS = """
        const float sceneAmbientDown = 0.25;
        const float sceneAmbientUp = 0.45;
        const float sceneDiffuse = 0.65;

        /* Full lighting multiplier; pass sunVisibility 1.0 when shadows are off. */
        float litShadingFactor(float lambert, float upFactor, float sunVisibility) {
            float ambient = mix(sceneAmbientDown, sceneAmbientUp, upFactor);
            return ambient + sceneDiffuse * lambert * sunVisibility;
        }
    """.trimIndent()
}
