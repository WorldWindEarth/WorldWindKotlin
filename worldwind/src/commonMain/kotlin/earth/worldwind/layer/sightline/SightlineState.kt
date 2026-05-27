package earth.worldwind.layer.sightline

import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color

/**
 * Per-frame snapshot of the active directional / omnidirectional sightline so receiver
 * programs can sample the moments framebuffer in their own fragment shader (parallel to
 * [earth.worldwind.layer.shadow.ShadowState]).
 *
 * Populated by [earth.worldwind.draw.DrawableSightline] after its depth + blur passes,
 * before any receiver renders. Read by drawables that opt in via [SightlineReceiverProgram]
 * and the [applySightlineReceiverUniforms] helper. `null` between frames and on frames
 * without an enabled sightline; receivers must treat a `null` state as "no sightline".
 */
class SightlineState {
    /** True when the active sightline uses the omnidirectional cube-map path (samplerCube). */
    var omnidirectional = false

    /**
     * Sightline view matrix (world -> sightline-eye). Directional path stores
     * `inv(centerTransform * forward-rotation)`; omni path stores `inv(centerTransform)`.
     */
    val sightlineView = Matrix4()

    /** Cube-map perspective projection (directional path only; ignored when [omnidirectional]). */
    val cubeMapProjection = Matrix4()

    /** Range cap in world units. Receiver fragments past `range` are unlit. */
    var range = 0f

    /** Visible (line-of-sight) tint color. Premultiplied by the receiver. */
    val visibleColor = Color(0f, 0f, 0f, 0f)

    /** Occluded (out-of-line-of-sight) tint color. Premultiplied by the receiver. */
    val occludedColor = Color(0f, 0f, 0f, 0f)

    /**
     * Frame stamp incremented each time [earth.worldwind.draw.DrawableSightline] re-populates this state.
     * Receiver programs cache the stamp and skip uniform uploads when the stamp matches —
     * GL uniforms persist across draws, so the second program-bind in the same frame
     * doesn't need to re-load the matrices.
     */
    var frameStamp = 0L
        private set

    /** Bumps the frame stamp; call once per fully-populated frame. */
    fun bumpFrameStamp() { frameStamp++ }
}
