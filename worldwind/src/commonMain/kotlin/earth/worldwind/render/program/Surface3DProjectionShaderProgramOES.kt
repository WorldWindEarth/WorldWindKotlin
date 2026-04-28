package earth.worldwind.render.program

/**
 * OES external-texture variant of [Surface3DProjectionShaderProgram] used when the
 * source texture has `target = GL_TEXTURE_EXTERNAL_OES` (Android MediaPlayer ->
 * SurfaceTexture path). Identical 3D camera-frustum projection shader; only the
 * fragment-stage sampler type changes:
 *
 *   * `#extension GL_OES_EGL_image_external : require` at the top of the fragment source, and
 *   * `samplerExternalOES` in place of `sampler2D` for `texSampler`.
 *
 * Both edits are derived from the parent's source at construction so the kernel stays
 * the single source of truth: touch [Surface3DProjectionShaderProgram]'s shader strings
 * and the OES variant follows automatically.
 */
class Surface3DProjectionShaderProgramOES : Surface3DProjectionShaderProgram() {
    init {
        val src = programSources
        programSources = arrayOf(
            src[0],
            "#extension GL_OES_EGL_image_external : require\n" +
                src[1].replace("sampler2D", "samplerExternalOES")
        )
    }

    companion object {
        val KEY = Surface3DProjectionShaderProgramOES::class
    }
}
