package earth.worldwind.render.image

import earth.worldwind.draw.DrawContext
import earth.worldwind.render.Texture
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.kgl.GL_RGBA
import earth.worldwind.util.kgl.GL_TEXTURE_2D
import earth.worldwind.util.kgl.GL_UNSIGNED_BYTE
import earth.worldwind.util.kgl.WebKgl
import earth.worldwind.util.math.isPowerOfTwo
import org.khronos.webgl.WebGLRenderingContext.Companion.UNPACK_PREMULTIPLY_ALPHA_WEBGL
import org.w3c.dom.HTMLCanvasElement

open class CanvasTexture(image: HTMLCanvasElement) : Texture(image.width, image.height, GL_RGBA, GL_UNSIGNED_BYTE) {
    protected var image: HTMLCanvasElement? = image
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
            // Specify the OpenGL texture 2D object's base image data (level 0).
            dc.gl.pixelStorei(UNPACK_PREMULTIPLY_ALPHA_WEBGL, 1)
            (dc.gl as WebKgl).gl.texImage2D(GL_TEXTURE_2D, 0, format, format, type, image)
            dc.gl.pixelStorei(UNPACK_PREMULTIPLY_ALPHA_WEBGL, 0)

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
