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
import org.khronos.webgl.TexImageSource
import org.khronos.webgl.WebGLRenderingContext.Companion.UNPACK_PREMULTIPLY_ALPHA_WEBGL
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLImageElement

open class ImageTexture(image: TexImageSource, width: Int, height: Int) : Texture(width, height, GL_RGBA, GL_UNSIGNED_BYTE) {
    protected var image: TexImageSource? = image
    override var hasMipMap = false

    constructor(image: HTMLImageElement) : this(image, image.width, image.height)

    constructor(image: HTMLCanvasElement) : this(image, image.width, image.height)

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

            // Generate mipmaps when the runtime supports them: always for POT dimensions,
            // additionally for NPOT on WebGL2 (proxied by supportsSizedTextureFormats).
            // WebGL1 forbids mipmaps on NPOT textures and would leave the texture
            // incomplete - so skip there.
            if ((isPowerOfTwo(width) && isPowerOfTwo(height)) || dc.gl.supportsSizedTextureFormats) {
                dc.gl.generateMipmap(GL_TEXTURE_2D)
                hasMipMap = true
            }
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