package earth.worldwind.render

import earth.worldwind.draw.DrawContext
import earth.worldwind.util.kgl.*

/**
 * Framebuffer with a multisample color renderbuffer attached. Used as the surface-shape
 * render target so triangle and line edges get MSAA coverage instead of binary 1-bit
 * coverage; [resolveTo] then blits the result into a single-sample texture for sampling.
 *
 * Only construct when [Kgl.supportsMultisampleFBO] is `true`.
 */
open class MultisampleFramebuffer(
    private val width: Int,
    private val height: Int,
    private val samples: Int,
) : Framebuffer() {
    private var colorRenderbuffer = KglRenderbuffer.NONE

    override fun release(dc: DrawContext) {
        if (colorRenderbuffer.isValid()) {
            dc.gl.deleteRenderbuffer(colorRenderbuffer)
            colorRenderbuffer = KglRenderbuffer.NONE
        }
        super.release(dc)
    }

    override fun createFramebuffer(dc: DrawContext) {
        super.createFramebuffer(dc)
        // Bind via raw `gl.bindFramebuffer` (not `dc.bindFramebuffer`) and restore via raw
        // call too — using the cached `dc.bindFramebuffer` here would leave `dc.framebuffer`
        // out of sync with the actual GL state across the restore.
        val currentFramebuffer = dc.currentFramebuffer
        try {
            colorRenderbuffer = dc.gl.createRenderbuffer()
            dc.gl.bindRenderbuffer(GL_RENDERBUFFER, colorRenderbuffer)
            dc.gl.renderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_RGBA8, width, height)
            dc.gl.bindFramebuffer(GL_FRAMEBUFFER, framebufferName)
            dc.gl.framebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, colorRenderbuffer)
        } finally {
            dc.gl.bindFramebuffer(GL_FRAMEBUFFER, currentFramebuffer)
        }
    }

    /**
     * Resolves this multisample framebuffer's color contents into [dest]'s color attachment
     * via `glBlitFramebuffer`. [dest] must be a single-sample framebuffer with the same
     * `width × height` and the same color internal format (`GL_RGBA8`).
     */
    fun resolveTo(dc: DrawContext, dest: Framebuffer) {
        val src = getFramebufferName(dc)
        val dst = dest.getFramebufferName(dc)
        if (!src.isValid() || !dst.isValid()) return
        dc.gl.bindFramebuffer(GL_READ_FRAMEBUFFER, src)
        dc.gl.bindFramebuffer(GL_DRAW_FRAMEBUFFER, dst)
        dc.gl.blitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_LINEAR)
        // Re-bind GL_FRAMEBUFFER (which aliases both read and draw) to whatever DrawContext
        // was tracking, leaving the cache in sync.
        dc.gl.bindFramebuffer(GL_FRAMEBUFFER, dc.currentFramebuffer)
    }
}
