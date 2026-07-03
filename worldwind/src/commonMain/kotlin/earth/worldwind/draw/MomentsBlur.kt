package earth.worldwind.draw

import earth.worldwind.render.Framebuffer
import earth.worldwind.render.program.SightlineMomentsBlurProgram
import earth.worldwind.util.kgl.GL_BLEND
import earth.worldwind.util.kgl.GL_COLOR_ATTACHMENT0
import earth.worldwind.util.kgl.GL_DEPTH_TEST
import earth.worldwind.util.kgl.GL_FLOAT
import earth.worldwind.util.kgl.GL_TEXTURE0
import earth.worldwind.util.kgl.GL_TRIANGLE_STRIP

/**
 * Two-pass separable Gaussian blur ping-pong over a moments texture, shared by the cascaded-shadow
 * and sightline draw paths (the blur/depth *programs* are already shared; this is the GL
 * orchestration around them). Pass 1 blurs horizontally from [srcFb] into [tempFb]; pass 2 blurs
 * vertically from [tempFb] back into [srcFb], so a receiver samples [srcFb] directly with no further
 * plumbing. [tapSpacing] is the per-pass tap offset in UV; both FBOs' colour attachment 0 must share
 * [srcFb]'s texture size.
 *
 * Depth test and blend are disabled for the full-screen replaces (the moments channels include
 * alpha < 1, which would blend with the cleared sentinel instead of overwriting it) and depth is
 * re-enabled afterwards. When [restoreCallerState] is true the caller's framebuffer + camera
 * viewport are restored and blend re-enabled (the sightline path, whose occlusion pass runs next);
 * the cascade path passes false and lets its own caller restore blend. Bails safely on any bind
 * failure.
 */
internal fun DrawContext.separableBlurMoments(
    blur: SightlineMomentsBlurProgram,
    srcFb: Framebuffer,
    tempFb: Framebuffer,
    tapSpacing: Float,
    restoreCallerState: Boolean,
) {
    val srcTex = srcFb.getAttachedTexture(GL_COLOR_ATTACHMENT0)
    val tempTex = tempFb.getAttachedTexture(GL_COLOR_ATTACHMENT0)

    if (!unitSquareBuffer.bindBuffer(this)) return
    gl.vertexAttribPointer(0 /*vertexPoint*/, 2, GL_FLOAT, false, 0, 0)
    gl.disable(GL_DEPTH_TEST)
    gl.disable(GL_BLEND)
    val previousFramebuffer = currentFramebuffer
    try {
        // Pass 1: horizontal. srcFb -> tempFb.
        if (!tempFb.bindFramebuffer(this)) return
        gl.viewport(0, 0, srcTex.width, srcTex.height)
        activeTextureUnit(GL_TEXTURE0)
        if (!srcTex.bindTexture(this)) return
        blur.loadBlurDirection(tapSpacing, 0f)
        gl.drawArrays(GL_TRIANGLE_STRIP, 0, 4)

        // Pass 2: vertical. tempFb -> srcFb.
        if (!srcFb.bindFramebuffer(this)) return
        gl.viewport(0, 0, srcTex.width, srcTex.height)
        if (!tempTex.bindTexture(this)) return
        blur.loadBlurDirection(0f, tapSpacing)
        gl.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
    } finally {
        if (restoreCallerState) {
            bindFramebuffer(previousFramebuffer)
            gl.viewport(viewport.x, viewport.y, viewport.width, viewport.height)
        }
        gl.enable(GL_DEPTH_TEST)
        if (restoreCallerState) gl.enable(GL_BLEND)
        defaultTexture.bindTexture(this)
    }
}
