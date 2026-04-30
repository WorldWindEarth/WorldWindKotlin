package earth.worldwind.layer.shadow

import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.DrawableShadow
import earth.worldwind.geom.Vec3

/**
 * Implemented by [earth.worldwind.draw.Drawable]s that can be rendered into the cascaded
 * shadow map's depth pass. Parallel to [earth.worldwind.draw.SightlineOccluder] but specialized
 * for the directional sun-shadow pipeline: the depth-pass program is the same MSM moments
 * shader used by the sightline pipeline, but the per-cascade matrices come from
 * [DrawableShadow] (orthographic) instead of from a perspective sightline frustum.
 *
 * Implementations are dispatched once per cascade by [DrawableShadow.draw]. Implementers should
 * call [DrawableShadow.loadCasterTranslation] (or the matrix variant) to set the per-shape
 * model transform, then issue draw calls for their triangle geometry. Line / point primitives
 * should be skipped — they don't contribute meaningful occlusion volumes and would alias on
 * the shadow map.
 */
interface ShadowCaster {
    /**
     * Renders this drawable's caster geometry into the currently bound cascade shadow map.
     * Called once per cascade by [DrawableShadow]. The depth-pass program is already bound
     * and the cascade's MVP base matrix loaded; the implementation only needs to bind its
     * own vertex buffer, set its model translation via [DrawableShadow.loadCasterTranslation]
     * / [DrawableShadow.loadCasterMatrix], and call [DrawableShadow.drawShapeStateOccluder]
     * (or issue raw draws for non-DrawShapeState geometry).
     */
    fun drawShadowDepth(dc: DrawContext, shadow: DrawableShadow)

    /**
     * World-space bounding sphere center used to skip cascades whose AABB doesn't intersect
     * this caster. Default `null` opts out of culling — the caster is dispatched into every
     * cascade. Implementations populate this from their shape's `boundingBox.center` to gain
     * per-cascade culling.
     */
    val shadowCasterCenter: Vec3? get() = null

    /** World-space bounding sphere radius. Only consulted when [shadowCasterCenter] is non-null. */
    val shadowCasterRadius: Double get() = 0.0
}
