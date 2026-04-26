package earth.worldwind.draw

/**
 * Marker for a [Drawable] that wishes to participate as an occluder in
 * [DrawableSightline]'s depth pass.
 *
 * The built-in shape, mesh and COLLADA drawables already cast shadows in the sightline
 * overlay via dedicated handlers inside [DrawableSightline]. Custom drawables that live
 * outside the engine's known set - or any subclass that needs different rendering than
 * the default - implement this interface and the sightline will dispatch to them
 * alongside the built-in handlers.
 *
 * Surface decals (decals rasterised onto the terrain texture), screen-space sprites
 * (placemarks, leader lines, labels) and dedicated line drawables should not implement
 * this interface — they don't represent occluding volumes and would produce visual
 * artefacts in the visibility map.
 */
interface SightlineOccluder {
    /**
     * Render this drawable's filled-triangle geometry into the sightline's depth pass.
     *
     * Implementations are responsible for:
     *  - binding their own vertex / element buffers,
     *  - configuring vertex attribute 0 (the position attribute - 3 GL_FLOATs at the
     *    appropriate stride/offset),
     *  - composing their model transform with the active sightline matrices via
     *    [DrawableSightline.loadOccluderMatrix] or [DrawableSightline.loadOccluderTranslation]
     *    immediately before each draw call,
     *  - issuing `glDrawElements` / `glDrawArrays`.
     *
     * Polygon offset is already disabled by the caller (shapes don't self-shadow), and the
     * depth-only colour mask is not applied during the sightline depth pass for VSM/depth
     * receivers - the caller has set up exactly the state appropriate for opaque triangle
     * rasterisation. The only state the implementation typically needs to manage is cull
     * face if its geometry isn't a closed CCW solid.
     *
     * Called once per sightline face during the depth pass, so any non-trivial setup that
     * doesn't depend on the active face matrices belongs in the caller's `makeDrawable`,
     * not here.
     */
    fun drawSightlineDepth(dc: DrawContext, sightline: DrawableSightline)
}
