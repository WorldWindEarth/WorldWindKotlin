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

    /** Highest CAMERA-RELATIVE light-axis coordinate (`dot(lightDir, center - camera) + radius`)
     *  of any contributor. Consumed only when [casterOverflow] is set: the per-caster sphere
     *  list is the precise mechanism, this is the conservative global fallback that lifts
     *  every cascade. Kept camera-relative so [ShadowLayer] can re-base it to its ANCHORED
     *  light axis without planet-magnitude error. */
    var maxCasterLightZ = -Double.MAX_VALUE
        private set

    /** World bounding spheres (x, y, z, radius) of ELEVATED casters (camera-relative
     *  light-axis top above [ELEVATED_CASTER_THRESHOLD]), consumed by [ShadowLayer] to lift
     *  only the cascades whose light-space window each sphere overlaps — one model at
     *  altitude must not inflate street-level cascades. Ground-level content never
     *  registers; each cascade's slice pullback already covers it. */
    val casterSpheres = DoubleArray(MAX_TRACKED_CASTERS * 4)
    var casterCount = 0
        private set

    /** More casters contributed than [casterSpheres] holds; [maxCasterLightZ] kicks in. */
    var casterOverflow = false
        private set

    /** `true` when at least one drawable contributed this frame. */
    val hasData get() = far > 0.0 && near < far

    fun addCasterSphere(x: Double, y: Double, z: Double, radius: Double) {
        if (casterCount < MAX_TRACKED_CASTERS) {
            val base = casterCount * 4
            casterSpheres[base] = x
            casterSpheres[base + 1] = y
            casterSpheres[base + 2] = z
            casterSpheres[base + 3] = radius
            casterCount++
        } else casterOverflow = true
    }

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
        casterCount = 0
        casterOverflow = false
    }

    fun copyFrom(source: ShadowSceneBounds) {
        near = source.near
        far = source.far
        maxCasterLightZ = source.maxCasterLightZ
        source.casterSpheres.copyInto(casterSpheres, 0, 0, source.casterCount * 4)
        casterCount = source.casterCount
        casterOverflow = source.casterOverflow
    }

    companion object {
        /** Per-frame cap on individually tracked elevated casters; beyond it the global
         *  fallback applies. Elevated casters are rare (aircraft-style models), so the cap
         *  exists only as an overflow guard. */
        const val MAX_TRACKED_CASTERS = 64

        /** Camera-relative light-axis top above which a caster registers an individual
         *  sphere - matches the slice pullback slack that covers everything below it. */
        const val ELEVATED_CASTER_THRESHOLD = 500.0
    }
}
