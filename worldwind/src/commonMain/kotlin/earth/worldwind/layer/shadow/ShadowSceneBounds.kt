package earth.worldwind.layer.shadow

import kotlin.math.max
import kotlin.math.min

/**
 * View-depth extent of shadow-relevant scene content, accumulated from drawable bounding
 * spheres as they are offered and consumed by [ShadowLayer] on the next frame (the shadow
 * layer renders before content layers). Fitting cascades to actual content — instead of a
 * terrain-ray probe — is what keeps street-level views at centimetre texels.
 */
class ShadowSceneBounds {
    /** Closest view-forward distance of any contributing sphere, clamped to >= 0. */
    var near = Double.MAX_VALUE
        private set

    /** Farthest view-forward distance of any contributing sphere. */
    var far = 0.0
        private set

    /** Highest light-axis coordinate (`dot(lightDir, center) + radius`) of any contributor —
     *  cascade depth windows extend to cover it so floating casters (a model at altitude)
     *  still rasterize into the cascades their shadows land in. */
    var maxCasterLightZ = -Double.MAX_VALUE
        private set

    /** `true` when at least one drawable contributed this frame. */
    val hasData get() = far > 0.0 && near < far

    fun contribute(distanceAlongForward: Double, radius: Double, lightZTop: Double) {
        maxCasterLightZ = max(maxCasterLightZ, lightZTop)
        val sphereFar = distanceAlongForward + radius
        if (sphereFar <= 0.0) return // entirely behind the camera plane
        // Containing spheres (large parent / fallback tiles) would collapse near to zero at
        // any altitude; half the centre distance is the content proxy — SSE refinement puts
        // genuinely close content in small tiles whose exact surface distance wins the min.
        val sphereNear = max(max(distanceAlongForward - radius, distanceAlongForward * 0.5), 0.0)
        near = min(near, sphereNear)
        far = max(far, sphereFar)
    }

    fun clear() {
        near = Double.MAX_VALUE
        far = 0.0
        maxCasterLightZ = -Double.MAX_VALUE
    }

    fun copyFrom(source: ShadowSceneBounds) {
        near = source.near
        far = source.far
        maxCasterLightZ = source.maxCasterLightZ
    }
}
