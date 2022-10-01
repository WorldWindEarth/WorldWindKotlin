package earth.worldwind.render.image

import earth.worldwind.draw.DrawContext
import earth.worldwind.render.Texture
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.kgl.*
import earth.worldwind.util.math.isPowerOfTwo
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte

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
            val pixels = (image!!.raster.dataBuffer as DataBufferByte).data

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
}
