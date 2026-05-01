package earth.worldwind.layer.shadow

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.util.kgl.GL_COLOR_ATTACHMENT0
import earth.worldwind.util.kgl.GL_TEXTURE0
import earth.worldwind.util.kgl.GL_TEXTURE1

/**
 * Common contract implemented by every shader program that samples the cascaded shadow map
 * (see [ShadowReceiverGlsl] for the GLSL these methods drive). Lets
 * [applyShadowReceiverUniforms] bind cascade textures and load the per-frame cascade matrices
 * in one place rather than copy-pasting the recipe into every receiver drawable.
 */
interface ShadowReceiverProgram {

    /**
     * Enables shadow sampling and uploads the cascade matrices, view-depth ranges, and
     * receiver algorithm choice. Cascade sampler bindings (`GL_TEXTURE1..3`) are baked in
     * at program init time; the caller is responsible for binding the matching cascade
     * textures to those units before invoking this method - [applyShadowReceiverUniforms]
     * does both in lockstep. The [useMSM] flag drives the GLSL branch in
     * [ShadowReceiverGlsl.computeShadowVisibility]: `true` runs the Hamburger 4-moment
     * Cholesky path, `false` runs PCF.
     */
    fun loadShadowEnabled(
        ambientShadow: Float,
        lightProjectionView0: Matrix4,
        lightProjectionView1: Matrix4,
        lightProjectionView2: Matrix4,
        cascadeFarDepth0: Float,
        cascadeFarDepth1: Float,
        cascadeFarDepth2: Float,
        useMSM: Boolean,
    )

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
 * Binds the per-frame cascade moments textures to texture units 1..3 and pushes the cascade
 * matrices into [program] - or, when no shadow state is available (no [ShadowLayer] this
 * frame, or pick mode, or the platform doesn't support `RGBA32F` for moments, or the caller
 * passes `applyShadow = false`), calls [ShadowReceiverProgram.loadShadowDisabled] so the
 * receiver shader's `applyShadow` branch elides the lookup. Active texture unit is restored
 * to `GL_TEXTURE0` on the way out so subsequent texture binds in the caller's draw method
 * land on the surface texture as expected.
 *
 * The [applyShadow] parameter lets per-shape drawables opt out (e.g. a shape with
 * `isLightingEnabled = false` is sun-independent and shouldn't receive shadow attenuation).
 *
 * Centralising this here means the per-receiver drawables ([DrawableMesh], [DrawableShape],
 * [DrawableCollada], [DrawableSurfaceTexture], [DrawableSurfaceShape]) drop ~30 lines of
 * copy-pasted state-binding into a single call.
 */
fun DrawContext.applyShadowReceiverUniforms(program: ShadowReceiverProgram, applyShadow: Boolean = true) {
    val state = shadowState
    val algorithm = state?.algorithm
    if (!applyShadow || isPickMode || state == null || algorithm == null) {
        program.loadShadowDisabled()
        // Pick passes (and shadow-disabled frames) wipe `applyShadowId` to 0 and may displace
        // cascade-texture bindings on units 1..3. Reset both caches so the next enabled call
        // re-binds textures and re-uploads uniforms — without this, the JVM/Android display
        // pipeline (pick frame followed by a redraw of the same regular frame, same stamp)
        // would skip the re-upload and leave shadows turned off until the camera moves.
        program.shadowUploadStamp = -1L
        lastShadowTextureBindStamp = -1L
        return
    }
    val stamp = state.frameStamp
    // Cascade textures sit on units 1..3; once bound they stay bound for the rest of the
    // frame because no other drawable touches those units. Skip the redundant rebinds.
    if (lastShadowTextureBindStamp != stamp) {
        for (i in 0 until state.cascadeCount) {
            activeTextureUnit(GL_TEXTURE1 + i)
            shadowCascadeFramebuffer(i).getAttachedTexture(GL_COLOR_ATTACHMENT0).bindTexture(this)
        }
        activeTextureUnit(GL_TEXTURE0)
        lastShadowTextureBindStamp = stamp
    }
    // GL uniforms persist on the program until overwritten. Skip the matrix uploads when this
    // program already saw the current frame's cascades.
    if (program.shadowUploadStamp != stamp) {
        program.loadShadowEnabled(
            state.ambientShadow,
            state.cascades[0].lightProjectionView,
            state.cascades[1].lightProjectionView,
            state.cascades[2].lightProjectionView,
            state.cascades[0].farViewDepth.toFloat(),
            state.cascades[1].farViewDepth.toFloat(),
            state.cascades[2].farViewDepth.toFloat(),
            algorithm == ShadowAlgorithm.MSM,
        )
        program.shadowUploadStamp = stamp
    }
}
