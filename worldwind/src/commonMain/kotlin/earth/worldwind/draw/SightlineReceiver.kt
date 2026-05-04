package earth.worldwind.draw

/**
 * Marker for a [Drawable] that wishes to be tinted by [DrawableSightline]'s receiver pass —
 * i.e. its surface should display the visible / occluded colour the same way terrain does.
 *
 * Terrain is always rendered as a receiver. Drawables that represent additional opaque
 * surfaces inside the sightline volume (3D-tile meshes, custom terrain extensions) opt in
 * via this interface so the receiver pass overlays the visibility colour onto their
 * geometry too. Built-in shape / mesh / COLLADA drawables intentionally don't implement
 * this — they're treated as occluders only, mirroring the rationale documented on
 * [SightlineOccluder].
 *
 * Implementations typically also implement [SightlineOccluder]: a building should both
 * cast the visibility map (the back wall hides what's behind it) and receive it on its
 * front face. The two passes are independent however, so a drawable may opt into one
 * without the other.
 *
 * Surface decals, screen-space sprites and line drawables should not implement this
 * interface — alpha-blended quads and 1D primitives don't have a meaningful receiver
 * surface and would produce visual artefacts at the receiver's depth-vs-moments compare.
 */
interface SightlineReceiver {
    /**
     * Render this drawable's filled-triangle geometry into the sightline's receiver pass.
     *
     * Implementations are responsible for:
     *  - binding their own vertex / element buffers,
     *  - configuring vertex attribute 0 (the position attribute - 3 GL_FLOATs at the
     *    appropriate stride/offset),
     *  - composing their model transform with the active receiver shader's matrices via
     *    [DrawableSightline.loadReceiverModelMatrix] immediately before each draw call,
     *  - issuing `glDrawElements` / `glDrawArrays`.
     *
     * Depth test and blending are already enabled by the caller (the receiver pass writes
     * the visibility colour into the main framebuffer over previously-rendered geometry),
     * and the receiver shader is bound — directional or cube-map — based on the active
     * sightline path. The caller's [DrawableSightline.loadReceiverModelMatrix] handles the
     * path difference internally; implementations call it the same way regardless.
     *
     * Called once per receiver pass — directional sightlines run one pass per face plus
     * fills, omnidirectional sightlines run a single cube-map pass — so non-trivial setup
     * that doesn't depend on the active receiver matrices belongs in the caller's
     * `makeDrawable`, not here.
     */
    fun drawSightlineColor(dc: DrawContext, sightline: DrawableSightline)
}