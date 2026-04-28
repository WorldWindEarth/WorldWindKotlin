package earth.worldwind.render

import earth.worldwind.draw.DrawContext
import earth.worldwind.util.kgl.GL_RGBA
import earth.worldwind.util.kgl.GL_TEXTURE_EXTERNAL_OES
import earth.worldwind.util.kgl.GL_UNSIGNED_BYTE

/**
 * Texture wrapper for external producers (typically Android `SurfaceTexture` from MediaPlayer /
 * MediaCodec / Camera2) bound to `GL_TEXTURE_EXTERNAL_OES`. Storage is owned by the external
 * producer; this class never calls `glTexImage2D`. Sample with the OES shader variant
 * (`samplerExternalOES`) - the standard `sampler2D` won't bind to this target.
 *
 * `width` and `height` are advisory (used by callers that need a nominal size); the actual
 * texture dimensions follow whatever the external source produces. The internal format / type
 * fields are unused for OES samples but kept for the [Texture] superclass contract.
 *
 * Available on Android (GLES 2+ via the `GL_OES_EGL_image_external` extension). Other targets
 * have no producer wired up and shouldn't construct this directly.
 */
open class OesExternalTexture(
    width: Int,
    height: Int,
) : Texture(
    width, height,
    format = GL_RGBA, type = GL_UNSIGNED_BYTE,
    isRT = false, internalFormat = GL_RGBA, target = GL_TEXTURE_EXTERNAL_OES,
) {
    /**
     * No-op: external producer (e.g. `SurfaceTexture`) supplies the storage. Calling
     * `glTexImage2D` on `GL_TEXTURE_EXTERNAL_OES` is an error on most drivers.
     */
    override fun allocTexImage(dc: DrawContext) { /* external storage */ }
}
