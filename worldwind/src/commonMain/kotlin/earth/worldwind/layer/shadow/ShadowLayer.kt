package earth.worldwind.layer.shadow

import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.DrawableShadow
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.AbstractLayer
import earth.worldwind.render.RenderContext
import earth.worldwind.render.program.DirectionalDepthProgram
import earth.worldwind.util.Logger.INFO
import earth.worldwind.util.Logger.log
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Adds directional sun-shadow rendering to the scene. When this layer is in the layer list,
 * shapes (3D meshes, polygons, COLLADA / glTF models, OGC 3D-Tile meshes, etc.) cast shadows
 * onto terrain and onto each other from the same world-space sun direction that drives shape
 * lighting and atmospheric scattering ([RenderContext.lightDirection]).
 *
 * Implementation is **Cascaded Shadow Maps** ([cascadeCount] cascades, default 4) rendered as
 * plain depth textures and resolved with a bilinear-weighted percentage-closer filter for
 * smooth, stable penumbrae. Three properties matter for city-scale quality:
 *
 *  - **Scene-fit splits.** The cascade depth range is fit to the view-depth extent of the
 *    content actually on screen ([RenderContext.shadowSceneBounds], accumulated from drawable
 *    bounding spheres on the previous frame) rather than to a terrain-ray probe. When the
 *    camera is close to a small object, per-cascade windows are additionally clamped to
 *    street-scale distances ([maximumCascadeDistances]) so the closest cascade stays
 *    centimetre-sharp.
 *  - **Camera-relative receiver math.** Cascade matrices handed to receivers map
 *    `worldPos - cameraPoint`, composed in double precision on the CPU. Raw ECEF positions
 *    quantize to ~0.5 m in float32 varyings, which would destroy street-scale shadows.
 *  - **Slope-scaled caster bias.** The depth pass renders true depth, so hardware
 *    `glPolygonOffset` provides per-triangle slope-proportional bias; receivers add only a
 *    small constant bias plus a normal-offset term scaled by the cascade's texel world size.
 *
 * Receivers sample the cascade depth textures in their own fragment shaders and modulate
 * their output colour by the resulting visibility. The [ambientShadow] knob controls how dark
 * fully-occluded fragments appear: `0.0` = pure black shadow, `1.0` = no darkening. Per-shape
 * opt-out is via `ShapeAttributes.shadowMode` (and `ColladaScene.shadowMode` /
 * `GltfScene.shadowMode` / `Ogc3dTilesLayer.shadowMode`) — the [ShadowMode] enum is decoupled
 * from `isLightingEnabled` and separates cast vs receive. Terrain always receives.
 *
 * Place this layer **after** [earth.worldwind.layer.atmosphere.AtmosphereLayer] in the layer
 * list so the atmosphere has already populated [RenderContext.lightDirection] before the
 * cascade matrices are computed.
 */
open class ShadowLayer : AbstractLayer("Shadow") {
    override var isPickEnabled = false

    /** Number of cascades. Currently fixed at [ShadowState.DEFAULT_CASCADE_COUNT]. */
    val cascadeCount: Int get() = ShadowState.DEFAULT_CASCADE_COUNT

    /**
     * Cascade split interpolation between uniform (`0.0`) and logarithmic (`1.0`) spacing.
     * The default `0.9` is log-dominant so close-range cascades stay tight; the uniform
     * fraction keeps far cascades from collapsing when the near plane is very close.
     */
    var splitLambda: Double = 0.9

    /**
     * Floor for the last cascade's far cap, in metres. The actual cap each frame is
     * `max(maximumDistance, viewingDistance * 2.0)` — tilted / far-focused views push the
     * cascades out toward the focal point without per-app tuning — further tightened to the
     * scene's own depth extent when drawable bounds are available. Receivers fade shadows
     * out over the last 20% of the effective cap.
     */
    var maximumDistance: Double = DEFAULT_MAXIMUM_DISTANCE

    /**
     * Floor for the scene-fit near distance, in metres. Keeps the logarithmic split from
     * collapsing the closest cascade to centimetres when the camera projection's near plane
     * is very close.
     */
    var minNearDistance: Double = 0.5

    /**
     * Street-scale cap on each cascade's depth window, in metres, at a street-level fit
     * anchor; the caps relax proportionally as the nearest content recedes. The last entry
     * is unbounded so distant coverage never disappears. At the reference 1024² maps the
     * 50 m closest cascade keeps ~5 cm texels.
     */
    val maximumCascadeDistances = doubleArrayOf(50.0, 300.0, 1200.0, Double.MAX_VALUE)

    /**
     * Ambient floor for fully-occluded fragments. See [ShadowState.ambientShadow].
     */
    var ambientShadow: Float = ShadowState.DEFAULT_AMBIENT_SHADOW

    /**
     * How many cascades (closest first) recruit OFF-SCREEN shadow casters. A caster outside
     * the view frustum is kept alive - rasterizing into the depth maps but skipping the
     * color pass - when its bounds intersect one of the first N cascades' light-space boxes,
     * so its shadow still reaches visible ground. `0` disables keepalive: off-screen objects
     * cast nothing (cheapest, matches most engines). Higher values extend correct shadows to
     * coarser cascades at the cost of a larger resident caster set - in dense 3D-Tiles
     * scenes each step roughly grows the kept-alive tile ring by the next cascade's
     * footprint. Clamped to [cascadeCount].
     */
    var offscreenCasterCascades: Int = DEFAULT_OFFSCREEN_CASTER_CASCADES

    /** Debug: 0 off, 1 cascade bands, 2 footprint coverage, 3 raw shadow-map depth; logs re-fits when non-zero. */
    var debugShadowMode: Int = 0

    /**
     * Shared per-frame state. Reused across frames – the layer mutates the cascade matrices
     * in place every render, then attaches the same instance to [RenderContext.shadowState].
     */
    protected val shadowState = ShadowState()

    // Reusable scratch matrices and vectors avoid per-frame allocation churn on the render
    // thread. Not thread-safe; doRender is called serially per WorldWindow.
    private val viewToWorld = Matrix4()
    private val lightRotation = Matrix4()
    private val sliceCorners = Array(8) { Vec3() }
    private val scratchVec = Vec3()
    private val rightVec = Vec3()
    private val upVec = Vec3()
    private val forwardVec = Vec3()
    private val upRefVec = Vec3()
    private val splits = DoubleArray(ShadowState.DEFAULT_CASCADE_COUNT + 1)

    // Sticky scene fit (see doRender): the cascade layout anchors here and only re-fits when
    // the scene drifts beyond the hysteresis band, keeping shadow-map texels world-pinned
    // during pans.
    private var hasFit = false
    private var fitNear = 0.0
    private var fitFar = 0.0
    private var fitCapScale = 1.0
    private var wasteViolationFrames = 0
    private val anchoredLightDirection = Vec3()
    private var hasLightAnchor = false
    /** Closest cascade's texel world size from the previous frame — scales the light-anchor tolerance. */
    private var lastTexelWorld0 = 0.0

    override fun doRender(rc: RenderContext) {
        if (rc.globe.is2D) return // No shadow rendering on 2D globe
        if (rc.isPickMode) return // Picks bypass shadows entirely

        // Sun-below-horizon early-exit: dot(lightDirection, camera up) = sin(elevation);
        // the -0.05 margin avoids terminator flicker.
        val cp = rc.cameraPoint
        val eyeMagSq = cp.x * cp.x + cp.y * cp.y + cp.z * cp.z
        if (eyeMagSq > 0.0) {
            val invEyeMag = 1.0 / sqrt(eyeMagSq)
            val sinElevation = (rc.lightDirection.x * cp.x +
                rc.lightDirection.y * cp.y + rc.lightDirection.z * cp.z) * invEyeMag
            if (sinElevation < -0.05) return
        }

        shadowState.ambientShadow = ambientShadow
        shadowState.debugShadowMode = debugShadowMode
        shadowState.lightDirection.copy(rc.lightDirection)
        shadowState.cameraPoint.copy(rc.cameraPoint)
        shadowState.frameStamp++
        shadowState.reset()

        // Camera near/far from the projection matrix. Standard OpenGL perspective:
        //   m[10] = -(f+n)/(f-n), m[11] = -2fn/(f-n)
        // Solving: n = m[11] / (m[10] - 1); f = m[11] / (m[10] + 1).
        val pm = rc.projection.m
        val projNear = pm[11] / (pm[10] - 1)
        val projFar = pm[11] / (pm[10] + 1)

        // Scene-fit depth range from previous-frame drawable bounds; the terrain-ray
        // viewingDistance covers terrain-only scenes, and the far cap auto-raises with the
        // lookAt range for tilted globe-scale views.
        val lookAtRange = max(0.0, rc.viewingDistance)
        val farCap = max(maximumDistance, lookAtRange * 2.0)
        val bounds = rc.shadowSceneBounds
        // Caster bounds roll over from the previous frame, so the first fit sees none and
        // shadows stay invisible until the next redraw - request it (once: hasFit latches).
        if (!hasFit && !bounds.hasData) rc.requestRedraw()
        var shadowNear = max(minNearDistance, projNear)
        var shadowFar = min(farCap, projFar)
        if (bounds.hasData) {
            shadowNear = max(shadowNear, min(bounds.near, max(lookAtRange, minNearDistance)))
            // 15% headroom past the caster bounds: long shadows land beyond the casters
            // themselves, and the distance fade must not swallow them at globe scale.
            shadowFar = min(shadowFar, max(bounds.far * 1.15, lookAtRange))
        }
        if (shadowFar <= shadowNear) return // nothing within shadow range this frame

        // STICKY fit: the raw inputs drift continuously with camera motion and LoD
        // streaming; refitting per frame changes the texel quantum and shadow edges crawl.
        // Coverage loss on either end leaves content sampling outside the cascade footprint
        // (visibly unshadowed), so both ends re-fit immediately - ladder quantization lands
        // repeated refits on identical rungs, so there is no ping-pong. A merely WASTEFUL
        // fit waits out LoD flutter.
        // Tight near slack (matches far's 1.05): content nearer than the fit sits in front
        // of every cascade footprint and cannot be shadowed until a refit.
        val nearCoverageViolation = hasFit && shadowNear < fitNear / 1.05
        val farCoverageViolation = hasFit && shadowFar > fitFar * 1.05
        val wasteViolation = hasFit && (shadowNear > fitNear * 4.0 || shadowFar < fitFar / 4.0)
        val severeViolation = hasFit && (
            shadowNear > fitNear * 16.0 ||
            shadowFar > fitFar * 6.0 || shadowFar < fitFar / 16.0)
        wasteViolationFrames = if (wasteViolation) wasteViolationFrames + 1 else 0
        val needRefit = !hasFit || severeViolation || nearCoverageViolation ||
            farCoverageViolation || wasteViolationFrames >= REFIT_DEBOUNCE_FRAMES
        if (needRefit) {
            wasteViolationFrames = 0
            // Ladder-quantized so repeated refits around the same view land on identical values.
            fitNear = max(minNearDistance, ladderFloor(shadowNear))
            fitFar = max(ladderCeil(shadowFar), fitNear * 4.0)
            // Street caps scale with distance to the nearest content - smooth, no binary
            // threshold for LoD streaming to flicker across.
            fitCapScale = max(1.0, fitNear / 25.0)
            hasFit = true
            if (debugShadowMode != 0) logRefit(rc)
        }

        // Cascade splits: lerp between uniform and logarithmic spacing by [splitLambda].
        splits[0] = fitNear
        splits[cascadeCount] = fitFar
        val range = fitFar - fitNear
        val ratio = fitFar / fitNear
        for (i in 1 until cascadeCount) {
            val p = i.toDouble() / cascadeCount
            val logScale = fitNear * ratio.pow(p)
            val uniformScale = fitNear + range * p
            splits[i] = uniformScale + (logScale - uniformScale) * splitLambda
        }

        // Cap cascade windows so close cascades stay centimetre-sharp regardless of horizon
        // distance; the last cascade keeps the remaining range.
        var distance = splits[0]
        for (i in 0 until cascadeCount - 1) {
            distance += min(splits[i + 1] - splits[i], maximumCascadeDistances[i] * fitCapScale)
            splits[i + 1] = min(distance, splits[i + 1])
        }

        // Inverse modelview = world ← view (orthonormal: transpose + translation flip).
        viewToWorld.invertOrthonormalMatrix(rc.modelview)

        // Frustum tangents at unit depth from the projection: m[5] = 1/tan(fovY/2), m[0] = m[5]/aspect.
        val tanHalfFovY = 1.0 / pm[5]
        val aspect = pm[5] / pm[0]

        // Sticky light anchor: camera-derived suns rotate in ECEF on every pan, and any
        // light rotation re-phases the snap grid (light-frame coordinates sit at ECEF
        // magnitude). Tolerance is adaptive — allow only the angle that displaces a shadow
        // across the shadow range by ~2 closest-cascade texels, so every re-anchor step is
        // sub-pixel at street AND globe scale. Shading terms keep the continuous sun.
        val trueLight = rc.lightDirection
        val anchorTolerance = (2.0 * lastTexelWorld0 / max(fitFar, 1.0)).coerceIn(2e-5, 0.01)
        val anchorDot = if (hasLightAnchor) anchoredLightDirection.dot(trueLight) else -1.0
        if (!hasLightAnchor || anchorDot < cos(anchorTolerance)) {
            anchoredLightDirection.copy(trueLight).normalize()
            hasLightAnchor = true
        }

        // Light-space rotation: forward = -lightDirection; ECEF +Z as the up reference,
        // +Y when the light is itself near the pole.
        forwardVec.copy(anchoredLightDirection).multiply(-1.0).normalize()
        if (abs(forwardVec.z) > 0.99) upRefVec.set(0.0, 1.0, 0.0) else upRefVec.set(0.0, 0.0, 1.0)
        // right = upRef × forward, up = forward × right (recomputed for orthonormality).
        rightVec.copy(upRefVec).cross(forwardVec).normalize()
        upVec.copy(forwardVec).cross(rightVec).normalize()
        // Rotation-only world → light-eye; per-cascade translation added later.
        lightRotation.set(
            rightVec.x, rightVec.y, rightVec.z, 0.0,
            upVec.x, upVec.y, upVec.z, 0.0,
            -forwardVec.x, -forwardVec.y, -forwardVec.z, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )

        // Per cascade: slice corners → light-space fit → ortho projection.
        var anyValid = false
        for (i in 0 until cascadeCount) {
            val sliceNear = splits[i]
            val sliceFar = splits[i + 1]
            if (sliceFar <= sliceNear) continue
            if (computeCascade(rc, i, sliceNear, sliceFar, tanHalfFovY, aspect)) anyValid = true
        }
        if (!anyValid) return // all cascades degenerate — no shadows this frame

        shadowState.shadowDistance = shadowFar
        shadowState.offscreenCasterCascades = offscreenCasterCascades.coerceIn(0, cascadeCount)
        shadowState.isReady = true
        if (debugShadowMode != 0 && needRefit) {
            for (i in 0 until cascadeCount) {
                val c = shadowState.cascades[i]
                log(
                    INFO, "ShadowLayer cascade $i: far=${c.farViewDepth} texel=${c.texelWorldSize} " +
                        "range=${c.range} valid=${c.isValid}"
                )
            }
        }
        lastTexelWorld0 = shadowState.cascades[0].texelWorldSize

        // DrawableShadow draws in the BACKGROUND group, before any receiver samples the maps.
        rc.shadowState = shadowState

        val pool = rc.getDrawablePool(DrawableShadow.KEY)
        val drawable = DrawableShadow.obtain(pool)
        drawable.depthProgram = rc.getShaderProgram { DirectionalDepthProgram() }
        rc.offerBackgroundDrawable(drawable)
    }

    /** Debug: dump the fit anchors and raw scene inputs on every re-fit. */
    private fun logRefit(rc: RenderContext) {
        val bounds = rc.shadowSceneBounds
        log(
            INFO, "ShadowLayer refit: fitNear=$fitNear fitFar=$fitFar capScale=$fitCapScale " +
                "boundsNear=${if (bounds.hasData) bounds.near else -1.0} " +
                "boundsFar=${if (bounds.hasData) bounds.far else -1.0} " +
                "viewingDistance=${rc.viewingDistance}"
        )
    }

    /**
     * Computes one cascade's light-space matrices. Returns `true` when the cascade has a
     * non-degenerate footprint (the typical case); `false` when the slice projects to a
     * zero-area / zero-depth box (e.g. all corners coplanar with the light direction —
     * extremely flat sun angles).
     */
    private fun computeCascade(
        rc: RenderContext,
        cascadeIndex: Int,
        sliceNear: Double,
        sliceFar: Double,
        tanHalfFovY: Double,
        aspect: Double,
    ): Boolean {
        val cascade = shadowState.cascades[cascadeIndex]

        // 8 view-space slice corners (-Z forward; y = d * tanHalfFovY, x = aspect * y).
        val nearY = sliceNear * tanHalfFovY
        val nearX = nearY * aspect
        val farY = sliceFar * tanHalfFovY
        val farX = farY * aspect
        // 0..3 near-plane corners, 4..7 far-plane corners.
        sliceCorners[0].set(-nearX, -nearY, -sliceNear)
        sliceCorners[1].set(+nearX, -nearY, -sliceNear)
        sliceCorners[2].set(+nearX, +nearY, -sliceNear)
        sliceCorners[3].set(-nearX, +nearY, -sliceNear)
        sliceCorners[4].set(-farX, -farY, -sliceFar)
        sliceCorners[5].set(+farX, -farY, -sliceFar)
        sliceCorners[6].set(+farX, +farY, -sliceFar)
        sliceCorners[7].set(-farX, +farY, -sliceFar)
        // View → world.
        for (corner in sliceCorners) corner.multiplyByMatrix(viewToWorld)

        // Footprint radius from the slice's bounding sphere computed in VIEW space (splits +
        // projection only — never camera pose) and ladder-quantized: the snap grid index sits
        // at ECEF magnitude (~1e8 texels), so even a 9th-digit radius change re-phases the
        // whole map. Sphere centre balances near/far corner distances:
        //   (c - near)^2 + nearSq = (c - far)^2 + farSq
        val nearSq = nearX * nearX + nearY * nearY
        val farSq = farX * farX + farY * farY
        val centerDistance = (
            (farSq - nearSq) / (2.0 * (sliceFar - sliceNear)) + (sliceFar + sliceNear) * 0.5
        ).coerceIn(sliceNear, sliceFar)
        val farDelta = sliceFar - centerDistance
        val sphereRadius = ladderCeil(sqrt(farSq + farDelta * farDelta), RADIUS_LADDER_BASE)

        // Light-eye-rotated coordinates of the sphere centre (xy drives the ortho window)
        // and the slice corners' light-depth extent (z drives the depth window).
        scratchVec.set(0.0, 0.0, -centerDistance).multiplyByMatrix(viewToWorld).multiplyByMatrix(lightRotation)
        var cx = scratchVec.x
        var cy = scratchVec.y
        var zMin = Double.POSITIVE_INFINITY
        var zMax = Double.NEGATIVE_INFINITY
        for (corner in sliceCorners) {
            scratchVec.copy(corner).multiplyByMatrix(lightRotation)
            if (scratchVec.z < zMin) zMin = scratchVec.z
            if (scratchVec.z > zMax) zMax = scratchVec.z
        }

        // Extend the near plane toward the sun: the footprint-scaled pullback captures
        // terrain-scale casters, the accumulated caster light-axis top captures floating
        // casters (a model at altitude) that a cascade's own window wouldn't reach.
        var zNearLight = zMax + max(500.0, 2.0 * sphereRadius)
        val bounds = rc.shadowSceneBounds
        if (bounds.hasData && bounds.maxCasterLightZ > -Double.MAX_VALUE) {
            zNearLight = max(zNearLight, bounds.maxCasterLightZ + 500.0)
        }
        val zFarLight = zMin
        val depthRange = zNearLight - zFarLight
        if (depthRange <= 0.0 || sphereRadius <= 0.0) return false

        // Texel-grid snap: pins shadow-map cells to fixed world positions across frames.
        val mapSize = DrawContext.shadowCascadeMapSize(cascadeIndex).toDouble()
        val texelSize = 2.0 * sphereRadius / mapSize
        cx = floor(cx / texelSize) * texelSize
        cy = floor(cy / texelSize) * texelSize

        val xMin = cx - sphereRadius
        val xMax = cx + sphereRadius
        val yMin = cy - sphereRadius
        val yMax = cy + sphereRadius

        // lightView = translate(0, 0, -zNearLight) * lightRotation, built directly.
        cascade.lightView.set(
            lightRotation.m[0], lightRotation.m[1], lightRotation.m[2], 0.0,
            lightRotation.m[4], lightRotation.m[5], lightRotation.m[6], 0.0,
            lightRotation.m[8], lightRotation.m[9], lightRotation.m[10], -zNearLight,
            0.0, 0.0, 0.0, 1.0,
        )

        // Standard GL ortho(xMin, xMax, yMin, yMax, near=0, far=depthRange).
        val invX = 2.0 / (xMax - xMin)
        val invY = 2.0 / (yMax - yMin)
        val invZ = 2.0 / depthRange
        cascade.lightProjection.set(
            invX, 0.0, 0.0, -(xMax + xMin) / (xMax - xMin),
            0.0, invY, 0.0, -(yMax + yMin) / (yMax - yMin),
            0.0, 0.0, -invZ, -1.0,
            0.0, 0.0, 0.0, 1.0,
        )

        // Composed world → light-clip for the depth pass (see [CascadeState.lightProjectionView]).
        cascade.lightProjectionView.copy(cascade.lightProjection).multiplyByMatrix(cascade.lightView)

        // Receiver matrix: camera-relative world → [0,1]^3 texture space, composed in double.
        val cam = shadowState.cameraPoint
        cascade.shadowMatrix
            .copy(TEX_SCALE_BIAS)
            .multiplyByMatrix(cascade.lightProjectionView)
            .multiplyByTranslation(cam.x, cam.y, cam.z)

        cascade.range = depthRange
        cascade.texelWorldSize = texelSize
        cascade.farViewDepth = sliceFar
        // Snapped xy AABB for per-cascade caster culling (light-eye-rotated frame).
        cascade.boxXMin = xMin
        cascade.boxXMax = xMax
        cascade.boxYMin = yMin
        cascade.boxYMax = yMax
        cascade.isValid = true
        return true
    }

    companion object {
        /** Default floor for the last cascade's far cap, in metres. */
        const val DEFAULT_MAXIMUM_DISTANCE: Double = 10_000.0

        /** Default [offscreenCasterCascades]: the two street-scale cascades. Correct shadows
         *  from just-off-screen casters where texels resolve them, without recruiting the
         *  coarse cascades' kilometre-scale rings (device-measured at ~2.5x resident tiles
         *  and ~4 ms/frame in dense city pans when all cascades recruit). */
        const val DEFAULT_OFFSCREEN_CASTER_CASCADES: Int = 2

        /**
         * Geometric quantization step applied to the fit inputs at refit time, so repeated
         * refits around the same view land on identical values.
         */
        private const val LADDER_BASE = 1.5

        /**
         * Consecutive frames a wasteful-fit violation must persist before re-anchoring —
         * long enough that LoD-streaming spikes never re-grid the shadows.
         */
        private const val REFIT_DEBOUNCE_FRAMES = 30

        /**
         * Quantization step for the cascade footprint radius. Finer than [LADDER_BASE] so
         * at most ~20% texel density is wasted; still discrete, so the texel quantum can't
         * drift with camera pose.
         */
        private const val RADIUS_LADDER_BASE = 1.2

        /** Rounds [value] down to the nearest power of [base]. */
        private fun ladderFloor(value: Double, base: Double = LADDER_BASE): Double =
            if (value <= 0.0) 0.0 else base.pow(floor(ln(value) / ln(base)))

        /** Rounds [value] up to the nearest power of [base]. */
        private fun ladderCeil(value: Double, base: Double = LADDER_BASE): Double =
            if (value <= 0.0) 0.0 else base.pow(ceil(ln(value) / ln(base)))

        /** Clip space `[-1,1]` → texture space `[0,1]` scale-bias, folded into [ShadowState.CascadeState.shadowMatrix]. */
        private val TEX_SCALE_BIAS = Matrix4(
            0.5, 0.0, 0.0, 0.5,
            0.0, 0.5, 0.0, 0.5,
            0.0, 0.0, 0.5, 0.5,
            0.0, 0.0, 0.0, 1.0,
        )
    }
}
