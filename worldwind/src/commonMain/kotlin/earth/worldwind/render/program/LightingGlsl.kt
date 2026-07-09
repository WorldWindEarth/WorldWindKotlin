package earth.worldwind.render.program

/**
 * Engine-wide lighting model shared by every lit fragment shader:
 * `albedo * (sceneAmbient + sceneDiffuse * lambert * sunVisibility)`.
 *
 * The sun-visibility factor (cascade shadows) attenuates only the direct term; the
 * ambient term is shadow-independent. A fully shadowed lit face and a sun-averted face
 * both converge to `sceneAmbient` brightness instead of multiplying two independent
 * darkening floors - which is what used to read as "double shading" on meshes.
 */
object LightingGlsl {
    /** Fragment-shader snippet; include unconditionally, before any shadow declarations. */
    val DECLARATIONS = """
        const float sceneAmbient = 0.35;
        const float sceneDiffuse = 0.65;

        /* Full lighting multiplier; pass sunVisibility 1.0 when shadows are off. */
        float litShadingFactor(float lambert, float sunVisibility) {
            return sceneAmbient + sceneDiffuse * lambert * sunVisibility;
        }
    """.trimIndent()
}
