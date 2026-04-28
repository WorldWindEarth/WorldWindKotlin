package earth.worldwind.render.image

import earth.worldwind.draw.DrawContext
import earth.worldwind.render.Texture
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.kgl.*
import earth.worldwind.util.math.isPowerOfTwo
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

open class ImageTexture(image: BufferedImage) : Texture(image.width, image.height, GL_BGRA, GL_UNSIGNED_BYTE) {
    protected var bgraBytes: ByteArray? = image.toBgraBytes()
    /**
     * Set by [allocTexImage] once the runtime can be probed: we always generate mipmaps for
     * power-of-two images (every GL profile supports it) and additionally for NPOT images
     * on modern GL where `supportsSizedTextureFormats` is true (proxies for
     * GLES3+/WebGL2/desktop GL, which gate NPOT mipmap support in lockstep with sized
     * texture formats). Anisotropic filtering pairs with mipmaps to keep oblique-angle
     * samples sharp.
     */
    override var hasMipMap = false

    init {
        coordTransform.setToVerticalFlip()
    }

    override fun release(dc: DrawContext) {
        super.release(dc)
        bgraBytes = null // allow GC if texture was evicted before first bind
    }

    override fun allocTexImage(dc: DrawContext) {
        try {
            val pixels = bgraBytes ?: return

            // Specify the OpenGL texture 2D object's base image data (level 0).
            dc.gl.pixelStorei(GL_UNPACK_ALIGNMENT, 1)
            dc.gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, format, type, pixels)
            dc.gl.pixelStorei(GL_UNPACK_ALIGNMENT, 0)

            // Generate mipmaps when the runtime supports them: always for POT dimensions (every
            // GL profile), additionally for NPOT on GLES3+/WebGL2/desktop GL (proxied by
            // supportsSizedTextureFormats). Skipping on GLES2/WebGL1 NPOT avoids the spec's
            // incomplete-texture trap.
            if ((isPowerOfTwo(width) && isPowerOfTwo(height)) || dc.gl.supportsSizedTextureFormats) {
                dc.gl.generateMipmap(GL_TEXTURE_2D)
                hasMipMap = true
            }
        } catch (e: Exception) {
            logMessage(
                ERROR, "Texture", "loadTexImage",
                "Exception attempting to load texture image", e
            )
        } finally {
            bgraBytes = null // release CPU copy after GPU upload
        }
    }

    /**
     * Converts arbitrary [BufferedImage] storage into tightly packed premultiplied-alpha BGRA bytes for GL upload.
     */
    protected open fun BufferedImage.toBgraBytes(): ByteArray {
        val argb = if (type == BufferedImage.TYPE_INT_ARGB) (raster.dataBuffer as DataBufferInt).data
        else IntArray(width * height).also { getRGB(0, 0, width, height, it, 0, width) }

        val bgra = ByteArray(argb.size * 4)
        var bi = 0
        for (c in argb) {
            val alpha = (c ushr 24) and 0xFF
            val red = (c ushr 16) and 0xFF
            val green = (c ushr 8) and 0xFF
            val blue = c and 0xFF
            bgra[bi++] = (blue * alpha / 255).toByte()  // B
            bgra[bi++] = (green * alpha / 255).toByte() // G
            bgra[bi++] = (red * alpha / 255).toByte()   // R
            bgra[bi++] = (alpha * alpha / 255).toByte() // A
        }
        return bgra
    }
}
