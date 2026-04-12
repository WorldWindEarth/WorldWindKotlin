package earth.worldwind.render.image

import earth.worldwind.draw.DrawContext
import earth.worldwind.render.Texture
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.kgl.*
import earth.worldwind.util.math.isPowerOfTwo
import java.awt.image.BufferedImage

open class ImageTexture(image: BufferedImage) : Texture(image.width, image.height, GL_BGRA, GL_UNSIGNED_BYTE) {
    protected var image: BufferedImage? = image
    override val hasMipMap = isPowerOfTwo(image.width) && isPowerOfTwo(image.height)

    init {
        coordTransform.setToVerticalFlip()
    }

    override fun release(dc: DrawContext) {
        super.release(dc)
        image = null // Image can be non-null if the texture has never been used
    }

    override fun allocTexImage(dc: DrawContext) {
        try {
            val pixels = image?.toBgraBytes() ?: return

            // Specify the OpenGL texture 2D object's base image data (level 0).
            dc.gl.pixelStorei(GL_UNPACK_ALIGNMENT, 1)
            dc.gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, format, type, pixels)
            dc.gl.pixelStorei(GL_UNPACK_ALIGNMENT, 0)

            // If the bitmap has power-of-two dimensions, generate the texture object's image data for image levels 1
            // through level N, and configure the texture object's filtering modes to use those image levels.
            if (hasMipMap) dc.gl.generateMipmap(GL_TEXTURE_2D)
        } catch (e: Exception) {
            // The Android utility was unable to load the texture image data.
            logMessage(
                ERROR, "Texture", "loadTexImage",
                "Exception attempting to load texture image '$image'", e
            )
        } finally {
            image = null
        }
    }

    /**
     * Converts arbitrary BufferedImage storage into tightly packed BGRA bytes for GL_UNSIGNED_BYTE upload.
     */
    protected open fun BufferedImage.toBgraBytes(): ByteArray {
        val argb = IntArray(width * height)
        getRGB(0, 0, width, height, argb, 0, width)
        val bgra = ByteArray(argb.size * 4)
        var bi = 0
        for (c in argb) {
            bgra[bi++] = (c and 0xFF).toByte()          // B
            bgra[bi++] = ((c ushr 8) and 0xFF).toByte() // G
            bgra[bi++] = ((c ushr 16) and 0xFF).toByte()// R
            bgra[bi++] = ((c ushr 24) and 0xFF).toByte()// A
        }
        return bgra
    }
}
