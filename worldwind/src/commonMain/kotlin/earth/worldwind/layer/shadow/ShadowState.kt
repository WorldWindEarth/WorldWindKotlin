package earth.worldwind.layer.shadow

import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3

/**
 * Per-frame state for the directional sun-shadow pipeline. Owned by [ShadowLayer], populated
 * during render, and consumed by [earth.worldwind.draw.DrawableShadow] (depth pass) and by
 * receiver shaders (terrain, lit shapes, 3D-Tile meshes) during the main pass.
 *
 * The pipeline uses **Cascaded Shadow Maps**: the camera frustum's scene-fit depth range is
 * sliced into [cascadeCount] sub-ranges and each slice gets its own light-space orthographic
 * projection sized to that slice's world-space footprint. The depth pass renders casters into
 * one depth texture per cascade; receivers select a cascade per-fragment from view-space depth
 * and resolve occlusion with a bilinear-weighted percentage-closer filter.
 *
 * Receiver-side shadow math runs in **camera-relative coordinates**: [CascadeState.shadowMatrix]
 * maps `worldPos - cameraPoint` into the cascade's `[0, 1]^3` texture space. Composing the
 * camera translation into the matrix on the CPU (in double precision) keeps fragment-shader
 * inputs at view-distance magnitude — a raw ECEF world position quantizes to ~0.5 m in float32,
 * which would erase street-scale shadow detail entirely.
 */
class ShadowState(
    /** Number of cascades. Default 4 spans street-scale detail to globe-scale coverage. */
    val cascadeCount: Int = DEFAULT_CASCADE_COUNT,
) {
    /** One [CascadeState] per slice; index 0 is the closest cascade. */
    val cascades = Array(cascadeCount) { CascadeState() }

    /**
     * Multiplier for shadowed-fragment colour on UNLIT receivers (terrain imagery,
     * photogrammetry, points), where the shadow term is the only sun shading. `0.0` =>
     * fully black shadow, `1.0` => no darkening; the default `0.4` is a perceptual
     * mid-point that reads as a directional shadow without obscuring the underlying
     * albedo. Lit receivers ignore it - their shadow floor is the scene ambient term
     * (see [earth.worldwind.render.program.LightingGlsl]).
     */
    var ambientShadow: Float = DEFAULT_AMBIENT_SHADOW

    /**
     * Effective far cap of the last cascade in metres for this frame. Receivers fade shadows
     * out over the last 20% of this distance so the far cascade edge never pops.
     */
    var shadowDistance: Double = 0.0

    /**
     * World-space unit vector toward the sun, snapshotted from
     * [earth.worldwind.render.RenderContext.lightDirection]. Receivers use it for the
     * normal-offset and normal-shading terms.
     */
    val lightDirection = Vec3(0.0, 0.0, 1.0)

    /** Camera position the cascade [CascadeState.shadowMatrix] matrices are relative to. */
    val cameraPoint = Vec3()

    /**
     * `true` when the cascade depth maps are populated and receivers may sample them.
     * Set by [ShadowLayer] at render time; cleared by [earth.worldwind.draw.DrawableShadow]
     * at draw time when the GL implementation can't run the pipeline (no sized formats /
     * depth textures).
     */
    var isReady: Boolean = false

    /**
     * Debug visualisation mode: 0 off, 1 cascade bands (1.0 / 0.75 / 0.5 / 0.25 per
     * cascade), 2 footprint coverage, 3 raw shadow-map depth projected onto the scene.
     */
    var debugShadowMode: Int = 0

    /** Snapshot of [ShadowLayer.offscreenCasterCascades], clamped to [cascadeCount].
     *  Consumed by [earth.worldwind.render.RenderContext.intersectsShadowCasterRegion]. */
    var offscreenCasterCascades: Int = 0

    /**
     * Monotonic counter incremented every frame that [ShadowLayer.doRender] populates new
     * cascades. Drives the per-frame caching in [applyShadowReceiverUniforms]: cascade texture
     * binds and per-program uniform uploads are skipped when this stamp hasn't changed since
     * the last call.
     */
    var frameStamp: Long = 0

    /**
     * Per-cascade light-space transforms and view-frustum slice metadata.
     */
    class CascadeState {
        /**
         * World → light-eye-space rotation+translation, with the cascade's near plane at
         * light-eye z = 0. Drives the depth pass ([earth.worldwind.draw.DrawableShadow]
         * composes caster model matrices against it) and caster culling.
         */
        val lightView = Matrix4()

        /** Light-eye-space → light-clip-space orthographic projection for the depth pass. */
        val lightProjection = Matrix4()

        /**
         * Composed `lightProjection * lightView` (world → light clip), kept in double for
         * the depth pass: caster MVPs must be composed against it on the CPU so the large
         * light-frame translations cancel before the float32 upload — uploading
         * `lightView * model` alone quantizes caster geometry to ~0.5 m at ECEF magnitude.
         */
        val lightProjectionView = Matrix4()

        /**
         * Camera-relative world → cascade texture space (`xy` = UV in `[0, 1]`, `z` = depth in
         * `[0, 1]`). Composed on the CPU in double precision as
         * `texScaleBias * lightProjection * lightView * translate(cameraPoint)` so receivers
         * feed it `worldPos - cameraPoint` at full float32 precision.
         */
        val shadowMatrix = Matrix4()

        /** Light-space depth range in metres (near plane to far plane of [lightProjection]). */
        var range: Double = 1.0

        /** World-space size of one shadow-map texel in metres — drives the receiver normal-offset bias. */
        var texelWorldSize: Double = 0.0

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
            shadowMatrix.copy(source.shadowMatrix)
            range = source.range
            texelWorldSize = source.texelWorldSize
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
        isReady = false
        for (cascade in cascades) cascade.isValid = false
    }

    /**
     * Deep-copy [source] into this instance. Lets [ShadowLayer] publish per-frame cascades
     * into a [earth.worldwind.frame.Frame]-owned [ShadowState] without leaking a reference to
     * its scratch state.
     */
    fun copyFrom(source: ShadowState) {
        require(cascadeCount == source.cascadeCount) { "cascadeCount mismatch" }
        ambientShadow = source.ambientShadow
        shadowDistance = source.shadowDistance
        debugShadowMode = source.debugShadowMode
        offscreenCasterCascades = source.offscreenCasterCascades
        lightDirection.copy(source.lightDirection)
        cameraPoint.copy(source.cameraPoint)
        isReady = source.isReady
        frameStamp = source.frameStamp
        for (i in cascades.indices) cascades[i].copyFrom(source.cascades[i])
    }

    companion object {
        const val DEFAULT_CASCADE_COUNT: Int = 4
        const val DEFAULT_AMBIENT_SHADOW: Float = 0.4f
    }
}
