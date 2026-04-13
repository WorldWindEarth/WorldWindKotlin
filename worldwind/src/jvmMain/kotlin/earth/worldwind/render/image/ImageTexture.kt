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
    override val hasMipMap = isPowerOfTwo(image.width) && isPowerOfTwo(image.height)
    override val isImageUpload = true

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

            // If the bitmap has power-of-two dimensions, generate the texture object's image data for image levels 1
            // through level N, and configure the texture object's filtering modes to use those image levels.
            if (hasMipMap) dc.gl.generateMipmap(GL_TEXTURE_2D)
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
