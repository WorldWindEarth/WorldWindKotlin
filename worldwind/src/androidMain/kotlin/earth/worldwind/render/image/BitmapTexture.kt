package earth.worldwind.render.image

import android.graphics.Bitmap
import android.opengl.GLUtils
import earth.worldwind.draw.DrawContext
import earth.worldwind.render.Texture
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.kgl.GL_TEXTURE_2D
import earth.worldwind.util.math.isPowerOfTwo

open class BitmapTexture(
    bitmap: Bitmap, private val isRecycleOnLoad: Boolean = true
) : Texture(bitmap.width, bitmap.height, GLUtils.getInternalFormat(bitmap), GLUtils.getType(bitmap)) {
    protected var bitmap: Bitmap? = bitmap
    // TODO consider using Bitmap.hasMipMap
    //override val hasMipMap = bitmap.hasMipMap()
    override var hasMipMap = false

    init {
        coordTransform.setToVerticalFlip()
    }

    override fun release(dc: DrawContext) {
        super.release(dc)
        bitmap?.recycle()
        bitmap = null // Bitmap can be non-null if the texture has never been used
    }

    override fun allocTexImage(dc: DrawContext) {
        try {
            bitmap?.also { bitmap ->
                // Specify the OpenGL texture 2D object's base image data (level 0).
                GLUtils.texImage2D(GL_TEXTURE_2D, 0 /*level*/, bitmap, 0 /*border*/)

                // Generate mipmaps when the runtime supports them: always for POT dimensions,
                // additionally for NPOT on GLES3+ (proxied by supportsSizedTextureFormats).
                // GLES2 forbids mipmaps on NPOT and would leave the texture incomplete.
                if ((isPowerOfTwo(bitmap.width) && isPowerOfTwo(bitmap.height)) || dc.gl.supportsSizedTextureFormats) {
                    dc.gl.generateMipmap(GL_TEXTURE_2D)
                    hasMipMap = true
                }
            }
        } catch (e: Exception) {
            // The Android utility was unable to load the texture image data.
            logMessage(
                ERROR, "Texture", "loadTexImage",
                "Exception attempting to load texture image '$bitmap'", e
            )
        } finally {
            if (isRecycleOnLoad) {
                bitmap?.recycle()
                bitmap = null
            }
        }
    }
}