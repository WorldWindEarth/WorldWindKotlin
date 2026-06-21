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
 * Texture backed by a tightly-packed, top-row-first RGBA8 byte array — the output of the
 * KTX2 / Basis-Universal transcoder (`KHR_texture_basisu`), which has no platform image
 * object to wrap. Mirrors `ImageTexture`'s vertical flip and mipmap policy so basisu textures
 * line up with glTF UVs exactly like the JPEG/PNG path, and premultiplies alpha to match the
 * engine's premultiplied blend (`GL_ONE`, `GL_ONE_MINUS_SRC_ALPHA`).
 *
 * Unsized `GL_RGBA` internal format keeps it valid on WebGL1 / GLES2.
 */
open class RgbaTexture(rgba: ByteArray, width: Int, height: Int) : Texture(width, height, GL_RGBA, GL_UNSIGNED_BYTE) {
    // Premultiply into a copy; the model's source array may be shared by other primitives.
    protected var rgba: ByteArray? = premultiplyRgba(rgba)
    override var hasMipMap = false

    init {
        coordTransform.setToVerticalFlip()
    }

    override fun release(dc: DrawContext) {
        super.release(dc)
        rgba = null // allow GC if texture was evicted before first bind
    }

    override fun allocTexImage(dc: DrawContext) {
        try {
            val pixels = rgba ?: return
            dc.gl.pixelStorei(GL_UNPACK_ALIGNMENT, 1)
            dc.gl.texImage2D(GL_TEXTURE_2D, 0, format, width, height, 0, format, type, pixels)
            dc.gl.pixelStorei(GL_UNPACK_ALIGNMENT, 0)

            // POT always; NPOT only on GLES3+/WebGL2/desktop GL (else incomplete on GLES2/WebGL1).
            if ((isPowerOfTwo(width) && isPowerOfTwo(height)) || dc.gl.supportsSizedTextureFormats) {
                dc.gl.generateMipmap(GL_TEXTURE_2D)
                hasMipMap = true
            }
        } catch (e: Exception) {
            logMessage(ERROR, "RgbaTexture", "allocTexImage", "Exception uploading RGBA texture", e)
        } finally {
            rgba = null // release CPU copy after GPU upload
        }
    }

    private companion object {
        fun premultiplyRgba(src: ByteArray): ByteArray {
            val out = src.copyOf()
            var i = 0
            while (i + 3 < out.size) {
                val a = out[i + 3].toInt() and 0xFF
                if (a != 0xFF) {
                    out[i] = ((out[i].toInt() and 0xFF) * a / 255).toByte()
                    out[i + 1] = ((out[i + 1].toInt() and 0xFF) * a / 255).toByte()
                    out[i + 2] = ((out[i + 2].toInt() and 0xFF) * a / 255).toByte()
                }
                i += 4
            }
            return out
        }
    }
}
