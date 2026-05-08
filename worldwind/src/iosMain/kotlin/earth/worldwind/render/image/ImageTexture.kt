package earth.worldwind.render.image

import earth.worldwind.draw.DrawContext
import earth.worldwind.render.Texture
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.kgl.GL_RGBA
import earth.worldwind.util.kgl.GL_TEXTURE_2D
import earth.worldwind.util.kgl.GL_UNPACK_ALIGNMENT
import earth.worldwind.util.kgl.GL_UNSIGNED_BYTE
import earth.worldwind.util.math.isPowerOfTwo

/**
 * iOS GL texture backed by tightly-packed RGBA8 bytes from [ImageDecoder]. The pixel
 * format is fixed to RGBA8 (premultiplied) — same as Android and JS — so the standard
 * `texImage2D(ByteArray)` overload can upload directly without extra format conversion.
 *
 * Lifecycle: holds a strong reference to [rgbaBytes] until the first [allocTexImage]
 * call, then drops it so the CPU copy can be GC'd while the GPU keeps the texture.
 */
open class ImageTexture(image: ImageData) : Texture(image.width, image.height, GL_RGBA, GL_UNSIGNED_BYTE) {
    protected var rgbaBytes: ByteArray? = image.rgba
    override var hasMipMap = false

    init {
        // Decoder writes top-left origin (CGContext default). GL texture sampling expects
        // bottom-left origin, so flip texture coordinates rather than the CPU bytes.
        coordTransform.setToVerticalFlip()
    }

    override fun release(dc: DrawContext) {
        super.release(dc)
        rgbaBytes = null
    }

    override fun allocTexImage(dc: DrawContext) {
        try {
            val pixels = rgbaBytes ?: return
            dc.gl.pixelStorei(GL_UNPACK_ALIGNMENT, 1)
            dc.gl.texImage2D(GL_TEXTURE_2D, 0, format, width, height, 0, format, type, pixels)
            dc.gl.pixelStorei(GL_UNPACK_ALIGNMENT, 4)

            // Generate mipmaps on POT or sized-format-capable runtimes (iOS GLES3 always is).
            if ((isPowerOfTwo(width) && isPowerOfTwo(height)) || dc.gl.supportsSizedTextureFormats) {
                dc.gl.generateMipmap(GL_TEXTURE_2D)
                hasMipMap = true
            }
        } catch (e: Exception) {
            logMessage(ERROR, "ImageTexture", "allocTexImage", "Exception uploading image", e)
        } finally {
            rgbaBytes = null
        }
    }
}
