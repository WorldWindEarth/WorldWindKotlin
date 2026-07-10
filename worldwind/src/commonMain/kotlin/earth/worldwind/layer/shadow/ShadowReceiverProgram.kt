package earth.worldwind.layer.shadow

import earth.worldwind.draw.DrawContext
import earth.worldwind.util.kgl.GL_DEPTH_ATTACHMENT
import earth.worldwind.util.kgl.GL_TEXTURE0
import earth.worldwind.util.kgl.GL_TEXTURE1

/** Sentinel [DrawContext.lastShadowTextureBindStamp]: units 1..4 hold the null shadow map. */
private const val NULL_MAPS_STAMP = -2L

/**
 * Common contract implemented by every shader program that samples the cascaded shadow maps
 * (see [ShadowReceiverGlsl] for the GLSL these methods drive). Lets
 * [applyShadowReceiverUniforms] bind cascade depth textures and load the per-frame cascade
 * matrices in one place rather than copy-pasting the recipe into every receiver drawable.
 */
interface ShadowReceiverProgram {

    /**
     * Enables shadow sampling and uploads the cascade matrices, split depths, texel sizes,
     * light direction and fade distance from [state]. Cascade sampler bindings
     * (`GL_TEXTURE1..4`) are baked in at program init time; the caller is responsible for
     * binding the matching cascade depth textures to those units before invoking this method —
     * [applyShadowReceiverUniforms] does both in lockstep.
     */
    fun loadShadowEnabled(state: ShadowState)

    /** Disables shadow sampling. Receivers' fragments fall through to a fixed `1.0` visibility. */
    fun loadShadowDisabled()

    /**
     * Frame stamp of the last [ShadowState] whose uniforms were uploaded into this program.
     * GL uniforms are program-state and persist across draw calls, so once a program has been
     * loaded with the current frame's cascades it doesn't need to be loaded again until the
     * cascades change. Implementations declare this as a plain `var` field, default `-1L`.
     */
    var shadowUploadStamp: Long
}

/**
 * Binds the per-frame cascade depth textures to texture units 1..4 and pushes the cascade
 * uniforms into [program] — or, when no shadow state is available (no
 * [earth.worldwind.layer.shadow.ShadowLayer] this frame, or pick mode, or the platform can't
 * run the depth-texture cascade pipeline, or the caller passes `applyShadow = false`), calls
 * [ShadowReceiverProgram.loadShadowDisabled] so the receiver shader's `applyShadow` branch
 * elides the lookup. Active texture unit is restored to `GL_TEXTURE0` on the way out so
 * subsequent texture binds in the caller's draw method land on the surface texture as expected.
 *
 * The [applyShadow] parameter lets per-shape drawables opt out (e.g. a shape with
 * `ShapeAttributes.shadowMode` that doesn't receive).
 *
 * Centralising this here means the per-receiver drawables
 * ([earth.worldwind.draw.DrawableMesh], [earth.worldwind.draw.DrawableShape],
 * [earth.worldwind.draw.DrawableCollada], [earth.worldwind.draw.DrawableSurfaceTexture],
 * [earth.worldwind.draw.DrawableSurfaceShape]) drop ~30 lines of
 * copy-pasted state-binding into a single call.
 */
fun DrawContext.applyShadowReceiverUniforms(program: ShadowReceiverProgram, applyShadow: Boolean = true) {
    val state = shadowState
    if (!applyShadow || isPickMode || state == null || !state.isReady) {
        program.loadShadowDisabled()
        // The program's uniforms were touched (applyShadow=0) - it must re-upload on its
        // next enabled draw even within the same frame stamp.
        program.shadowUploadStamp = -1L
        // The shared texture binds are only disturbed by pick / no-state frames; a plain
        // per-shape opt-out leaves units 1..4 intact - resetting here would force every
        // following receiver to re-bind all cascades.
        if (isPickMode || state == null || !state.isReady) {
            // WebGL2 validates sampler type vs texture format at draw time even when the
            // shader never samples - sampler2DShadow units must hold a compare-mode depth
            // texture, so park them on the 1x1 null map until real cascades bind.
            if (gl.hasShadowSamplers && lastShadowTextureBindStamp != NULL_MAPS_STAMP) {
                for (i in 0 until ShadowReceiverGlsl.CASCADE_COUNT) {
                    activeTextureUnit(GL_TEXTURE1 + i)
                    nullShadowDepthTexture.bindTexture(this)
                }
                activeTextureUnit(GL_TEXTURE0)
                lastShadowTextureBindStamp = NULL_MAPS_STAMP
            } else if (!gl.hasShadowSamplers) {
                lastShadowTextureBindStamp = -1L
            }
        }
        return
    }
    val stamp = state.frameStamp
    // Units 1..4 stay bound for the whole frame - skip redundant rebinds.
    if (lastShadowTextureBindStamp != stamp) {
        for (i in 0 until state.cascadeCount) {
            activeTextureUnit(GL_TEXTURE1 + i)
            shadowCascadeFramebuffer(i).getAttachedTexture(GL_DEPTH_ATTACHMENT).bindTexture(this)
        }
        activeTextureUnit(GL_TEXTURE0)
        lastShadowTextureBindStamp = stamp
    }
    // Uniforms persist per program - upload once per frame stamp.
    if (program.shadowUploadStamp != stamp) {
        program.loadShadowEnabled(state)
        program.shadowUploadStamp = stamp
    }
}
