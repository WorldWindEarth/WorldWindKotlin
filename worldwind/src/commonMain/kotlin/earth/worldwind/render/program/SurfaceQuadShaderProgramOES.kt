package earth.worldwind.render.program

/**
 * OES external-texture variant of [SurfaceQuadShaderProgram] used when the interior texture has
 * `target = GL_TEXTURE_EXTERNAL_OES` (typically a `SurfaceTexture` on Android driven by
 * MediaPlayer / MediaCodec / Camera2). Identical homography surface-quad shader; only the
 * fragment-stage sampler type changes:
 *
 *   * `#extension GL_OES_EGL_image_external : require` at the top of the fragment source, and
 *   * `samplerExternalOES` in place of `sampler2D` for `texSampler`.
 *
 * Both edits are derived from the parent's source at construction so the kernel stays the
 * single source of truth: touch [SurfaceQuadShaderProgram]'s shader strings and the OES
 * variant follows automatically.
 *
 * Available on Android (GLES 2+ via the `GL_OES_EGL_image_external` extension); other targets
 * have no OES producer wired up.
 */
class SurfaceQuadShaderProgramOES : SurfaceQuadShaderProgram() {
    init {
        val src = programSources
        programSources = arrayOf(
            src[0],
            "#extension GL_OES_EGL_image_external : require\n" +
                src[1].replace("sampler2D", "samplerExternalOES")
        )
    }

    companion object {
        val KEY = SurfaceQuadShaderProgramOES::class
    }
}
