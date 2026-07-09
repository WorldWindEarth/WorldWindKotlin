package earth.worldwind.layer.sightline

import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color

/**
 * Per-frame snapshot of the active directional / omnidirectional sightline so receiver
 * programs can sample the sightline depth cube in their own fragment shader (parallel to
 * [earth.worldwind.layer.shadow.ShadowState]).
 *
 * Populated by [earth.worldwind.draw.DrawableSightline] after its depth pass, before any
 * receiver renders. Read by drawables that opt in via [SightlineReceiverProgram] and the
 * [applySightlineReceiverUniforms] helper. `null` between frames and on frames without an
 * enabled sightline; receivers must treat a `null` state as "no sightline".
 */
class SightlineState {
    /** World -> sightline-local transform (`inv(centerTransform)`; local frame has Z = up). */
    val sightlineView = Matrix4()

    /** Range cap in world units. Receiver fragments past `range` are untinted. */
    var range = 0f

    /**
     * Unit horizontal forward direction of the directional wedge in the local frame,
     * `(sin heading, cos heading)`. Unused when the azimuth wedge is disabled ([cosHalfFov] = -1).
     */
    var forwardX = 0f
    var forwardY = 1f

    /** `cos(fieldOfView / 2)` for the directional azimuth wedge; `-1` disables it (omni). */
    var cosHalfFov = -1f

    /** `sin(fieldOfView / 2)` capping elevation above the horizon; `1` disables it (omni). */
    var sinHalfFov = 1f

    /** Visible (line-of-sight) tint color. Premultiplied by the receiver. */
    val visibleColor = Color(0f, 0f, 0f, 0f)

    /** Occluded (out-of-line-of-sight) tint color. Premultiplied by the receiver. */
    val occludedColor = Color(0f, 0f, 0f, 0f)
}
