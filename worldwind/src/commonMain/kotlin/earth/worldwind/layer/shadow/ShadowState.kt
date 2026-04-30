package earth.worldwind.layer.shadow

import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3

/**
 * Per-frame state for the directional sun-shadow pipeline. Owned by [ShadowLayer], populated
 * during render, and consumed by [earth.worldwind.draw.DrawableShadow] (depth pass) and by
 * receiver shaders (terrain, lit shapes, unlit shapes) during the main pass.
 *
 * The pipeline uses **Cascaded Shadow Maps** (CSM): the camera frustum is sliced into
 * [cascadeCount] depth ranges and each slice gets its own light-space orthographic projection
 * sized to that slice's world-space footprint. Receivers select a cascade per-fragment from
 * view-space depth.
 *
 * Shadow attenuation is **Moment Shadow Mapping** (Hamburger 4-moment, Peters & Klein 2015) –
 * the same scheme used by the omnidirectional sightline. Soft edges come for free from the
 * receiver's Cholesky reconstruction; an MSM moments texture is shared with the existing
 * sightline pipeline only conceptually – the FBOs are separate. On platforms without
 * `RGBA32F` (e.g. WebGL1) the receivers fall back to PCF.
 */
class ShadowState(
    /**
     * Number of cascades. PSSM splits the view frustum into this many slices. Default 3
     * is a typical balance between quality at close range and far-range coverage on a globe.
     */
    val cascadeCount: Int = DEFAULT_CASCADE_COUNT,
) {
    /** One [CascadeState] per slice; index 0 is the closest cascade. */
    val cascades = Array(cascadeCount) { CascadeState() }

    /**
     * Multiplier for shadowed-fragment colour. `0.0` => fully black shadow, `1.0` => no
     * darkening (sky-light only term). The default `0.4` is a perceptual mid-point that reads
     * as a directional shadow without obscuring the receiver's underlying albedo.
     */
    var ambientShadow: Float = DEFAULT_AMBIENT_SHADOW

    /**
     * Far cap for the largest cascade in metres. View-frustum depths beyond this distance are
     * not shadow-mapped; receivers gate their shadow lookup with this value to avoid sampling
     * out-of-range texels.
     */
    var maxCascadeDistance: Double = DEFAULT_MAX_CASCADE_DISTANCE

    /**
     * `true` when the underlying GL implementation supports `RGBA32F` colour attachments and
     * the engine is using the MSM moments path; `false` when receivers should use PCF instead.
     * Set by [ShadowLayer] during render.
     */
    var useMSM: Boolean = true

    /**
     * Monotonic counter incremented every frame that [ShadowLayer.doRender] populates new
     * cascades. Drives the per-frame caching in [applyShadowReceiverUniforms]: cascade texture
     * binds and per-program uniform uploads are skipped when this stamp hasn't changed since
     * the last call.
     */
    var frameStamp: Long = 0

    /** Linear depth metric range for moments storage = `1.0 / maxCascadeDistance`. */
    val invMaxCascadeDistance: Float get() = (1.0 / maxCascadeDistance).toFloat()

    /**
     * Per-cascade light-space transforms and view-frustum slice metadata. The view matrix
     * orients the orthographic frustum along the sun direction; the projection matrix sizes
     * it to encompass the slice's world-space corner points.
     */
    class CascadeState {
        /**
         * World → light-eye-space rotation+translation. Identity when the cascade is empty
         * (e.g. sun below the horizon and the slice is fully self-occluded).
         */
        val lightView = Matrix4()

        /**
         * Light-eye-space → light-clip-space orthographic projection sized to cover this
         * slice's world-space bounding box.
         */
        val lightProjection = Matrix4()

        /** Composed `lightProjection * lightView`. Receivers use this to derive shadow UVs. */
        val lightProjectionView = Matrix4()

        /**
         * Linear depth metric range in metres for this cascade. The depth pass writes
         * `-eye_z / range` so the receiver can do an absolute depth comparison without the
         * orthographic projection's sign-of-depth artefacts.
         */
        var range: Double = 1.0

        /** View-space `-z` lower bound of the camera-frustum slice this cascade covers. */
        var nearViewDepth: Double = 0.0

        /** View-space `-z` upper bound of the camera-frustum slice this cascade covers. */
        var farViewDepth: Double = 0.0

        /**
         * Light-eye-space xy AABB of the cascade's stable bounding sphere — populated by
         * [ShadowLayer.computeCascade] after the texel-grid snap. Used by [intersectsSphere]
         * to fast-reject casters that fall entirely outside this cascade.
         */
        var boxXMin: Double = 0.0
        var boxXMax: Double = 0.0
        var boxYMin: Double = 0.0
        var boxYMax: Double = 0.0

        /** `true` when this cascade has been populated for the current frame. */
        var isValid: Boolean = false

        fun copyFrom(source: CascadeState) {
            lightView.copy(source.lightView)
            lightProjection.copy(source.lightProjection)
            lightProjectionView.copy(source.lightProjectionView)
            range = source.range
            nearViewDepth = source.nearViewDepth
            farViewDepth = source.farViewDepth
            boxXMin = source.boxXMin
            boxXMax = source.boxXMax
            boxYMin = source.boxYMin
            boxYMax = source.boxYMax
            isValid = source.isValid
        }

        /**
         * Sphere-vs-AABB intersection test in light-eye space. The world-space sphere
         * `(worldCenter, worldRadius)` is transformed through [lightView] and tested against
         * the cascade's xy AABB plus its `[-range, +infty]` z range — casters above the slice
         * (positive `ez`) still cast shadows down, so there's no upper z bound.
         */
        fun intersectsSphere(worldCenter: Vec3, worldRadius: Double): Boolean {
            // Inline lightView * worldCenter to avoid a Vec3 allocation on the hot path.
            // lightView is rotation + z-translation, so x/y of the result lives in the same
            // light-eye-rotated frame as boxX/Y; z is post-translation.
            val m = lightView.m
            val ex = m[0] * worldCenter.x + m[1] * worldCenter.y + m[2] * worldCenter.z + m[3]
            val ey = m[4] * worldCenter.x + m[5] * worldCenter.y + m[6] * worldCenter.z + m[7]
            val ez = m[8] * worldCenter.x + m[9] * worldCenter.y + m[10] * worldCenter.z + m[11]
            if (ex + worldRadius < boxXMin || ex - worldRadius > boxXMax) return false
            if (ey + worldRadius < boxYMin || ey - worldRadius > boxYMax) return false
            return ez + worldRadius >= -range
        }
    }

    fun reset() {
        for (cascade in cascades) cascade.isValid = false
    }

    /**
     * Deep-copy [source] into this instance. Lets [ShadowLayer] publish per-frame cascades
     * into a [Frame]-owned [ShadowState] without leaking a reference to its scratch state.
     */
    fun copyFrom(source: ShadowState) {
        require(cascadeCount == source.cascadeCount) { "cascadeCount mismatch" }
        ambientShadow = source.ambientShadow
        maxCascadeDistance = source.maxCascadeDistance
        useMSM = source.useMSM
        frameStamp = source.frameStamp
        for (i in cascades.indices) cascades[i].copyFrom(source.cascades[i])
    }

    companion object {
        const val DEFAULT_CASCADE_COUNT: Int = 3
        const val DEFAULT_AMBIENT_SHADOW: Float = 0.4f
        /**
         * Default floor for the largest cascade's far cap. PSSM splits divide [near, this cap];
         * a smaller cap concentrates texel density near the camera. 100 km is a good close-range
         * default (city / building scale shadows). [ShadowLayer] auto-scales the actual cap with
         * camera altitude so globe-scale views reach their casters without per-app tuning.
         */
        const val DEFAULT_MAX_CASCADE_DISTANCE: Double = 100_000.0
    }
}
