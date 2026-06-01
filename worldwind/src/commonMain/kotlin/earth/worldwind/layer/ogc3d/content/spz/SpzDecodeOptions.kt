package earth.worldwind.layer.ogc3d.content.spz

/**
 * Per-attribute skip flags for [SpzGaussianLoader]. Each `skip*` flag short-circuits the
 * corresponding decode loop (the cursor still advances over the bytes when later sections
 * follow), and the payload field is left empty/null. Use [ROUND_SPLAT] when the renderer
 * only needs centres + scales + rgba — the rotation, opacity, and SH decodes are by far
 * the most expensive operations in the parser.
 */
class SpzDecodeOptions(
    val skipRotations: Boolean = false,
    val skipOpacities: Boolean = false,
    val skipSphericalHarmonics: Boolean = false,
) {
    companion object {
        /** Decode every field. Spec-compliant default. */
        val FULL = SpzDecodeOptions()

        /** Round-splat renderer setup: only centres + scales + rgba (alpha baked in). */
        val ROUND_SPLAT = SpzDecodeOptions(
            skipRotations = true,
            skipOpacities = true,
            skipSphericalHarmonics = true,
        )

        /** Anisotropic-splat renderer setup: centres + scales + rotations + rgba (alpha
         *  baked in); SH still skipped (DC term only). Use when the shader does proper
         *  covariance projection but no view-dependent colour. */
        val ANISOTROPIC = SpzDecodeOptions(
            skipRotations = false,
            skipOpacities = true,
            skipSphericalHarmonics = true,
        )

        /** Anisotropic-splat renderer setup with SH=1 view-dependent colour. Decodes all
         *  bands the payload ships; the renderer is responsible for picking the prefix it
         *  needs (typically SH=1 → first 9 floats per splat for the RGB-interleaved layout). */
        val ANISOTROPIC_SH1 = SpzDecodeOptions(
            skipRotations = false,
            skipOpacities = true,
            skipSphericalHarmonics = false,
        )
    }
}
