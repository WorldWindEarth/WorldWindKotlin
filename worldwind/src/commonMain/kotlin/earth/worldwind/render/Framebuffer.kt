package earth.worldwind.render

import earth.worldwind.draw.DrawContext
import earth.worldwind.util.kgl.*

open class Framebuffer : RenderResource {
    var framebufferName = KglFramebuffer.NONE
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

    fun attachTexture(dc: DrawContext, texture: Texture, attachment: Int): Boolean {
        if (!framebufferName.isValid()) createFramebuffer(dc)
        if (framebufferName.isValid()) {
            framebufferTexture(dc, texture, attachment)
            attachedTextures[attachment] = texture
        }
        return framebufferName.isValid()
    }

    fun getAttachedTexture(attachment: Int) = attachedTextures[attachment] ?: error("Invalid attachment type")

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

    protected open fun framebufferTexture(dc: DrawContext, texture: Texture?, attachment: Int) {
        val currentFramebuffer = dc.currentFramebuffer
        try {
            // Make the OpenGL framebuffer object the currently active framebuffer.
            dc.bindFramebuffer(framebufferName)
            // Attach the texture to the framebuffer object, or remove the attachment if the texture is null.
            val textureName = texture?.getTextureName(dc) ?: KglTexture.NONE
            dc.gl.framebufferTexture2D(GL_FRAMEBUFFER, attachment, GL_TEXTURE_2D, textureName, 0 /*level*/)
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