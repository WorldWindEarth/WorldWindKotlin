package earth.worldwind.render

import earth.worldwind.draw.DrawContext
import earth.worldwind.util.kgl.*

open class Framebuffer : RenderResource {
    protected var framebufferName = KglFramebuffer.NONE
    protected val attachedTextures = mutableMapOf<Int,Texture>()

    override fun release(dc: DrawContext) {
        if (framebufferName.isValid()) {
            deleteFramebuffer(dc)
            attachedTextures.clear()
        }
    }

    fun bindFramebuffer(dc: DrawContext): Boolean {
        if (!framebufferName.isValid()) createFramebuffer(dc)
        if (framebufferName.isValid()) dc.bindFramebuffer(framebufferName)
        return framebufferName.isValid()
    }

    /**
     * Attach a texture to the given FBO attachment point. The default `textarget` is
     * `GL_TEXTURE_2D`; pass a specific cube-map face target (e.g.
     * `GL_TEXTURE_CUBE_MAP_POSITIVE_X`) to attach a face of a cube-map texture. The same
     * cube-map texture can be re-attached to the same FBO multiple times with different
     * faces to render into all six faces of one cube map without rebinding the FBO.
     */
    fun attachTexture(dc: DrawContext, texture: Texture, attachment: Int, textarget: Int = GL_TEXTURE_2D): Boolean {
        if (!framebufferName.isValid()) createFramebuffer(dc)
        if (framebufferName.isValid()) {
            framebufferTexture(dc, texture, attachment, textarget)
            attachedTextures[attachment] = texture
        }
        return framebufferName.isValid()
    }

    fun getAttachedTexture(attachment: Int) = attachedTextures[attachment] ?: error("Invalid attachment type")

    /** Underlying GL framebuffer name. Created lazily on first access. */
    fun getFramebufferName(dc: DrawContext): KglFramebuffer {
        if (!framebufferName.isValid()) createFramebuffer(dc)
        return framebufferName
    }

    fun isFramebufferComplete(dc: DrawContext) = framebufferStatus(dc) == GL_FRAMEBUFFER_COMPLETE

    protected open fun createFramebuffer(dc: DrawContext) {
        val currentFramebuffer = dc.currentFramebuffer
        try {
            // Create the OpenGL framebuffer object.
            framebufferName = dc.gl.createFramebuffer()
            dc.gl.bindFramebuffer(GL_FRAMEBUFFER, framebufferName)
        } finally {
            // Restore the current OpenGL framebuffer object binding.
            dc.gl.bindFramebuffer(GL_FRAMEBUFFER, currentFramebuffer)
        }
    }

    protected open fun deleteFramebuffer(dc: DrawContext) {
        dc.gl.deleteFramebuffer(framebufferName)
        framebufferName = KglFramebuffer.NONE
    }

    protected open fun framebufferTexture(
        dc: DrawContext, texture: Texture?, attachment: Int, textarget: Int = GL_TEXTURE_2D
    ) {
        val currentFramebuffer = dc.currentFramebuffer
        try {
            // Make the OpenGL framebuffer object the currently active framebuffer.
            dc.bindFramebuffer(framebufferName)
            // Attach the texture to the framebuffer object, or remove the attachment if the texture is null.
            val textureName = texture?.getTextureName(dc) ?: KglTexture.NONE
            dc.gl.framebufferTexture2D(GL_FRAMEBUFFER, attachment, textarget, textureName, 0 /*level*/)
        } finally {
            // Restore the current OpenGL framebuffer object binding.
            dc.bindFramebuffer(currentFramebuffer)
        }
    }

    protected open fun framebufferStatus(dc: DrawContext): Int {
        val currentFramebuffer = dc.currentFramebuffer
        return try {
            // Make the OpenGL framebuffer object the currently active framebuffer.
            dc.bindFramebuffer(framebufferName)
            // Get the OpenGL framebuffer object status code.
            dc.gl.checkFramebufferStatus(GL_FRAMEBUFFER)
        } finally {
            // Restore the current OpenGL framebuffer object binding.
            dc.bindFramebuffer(currentFramebuffer)
        }
    }
}