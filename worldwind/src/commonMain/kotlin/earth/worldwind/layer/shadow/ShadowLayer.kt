package earth.worldwind.layer.shadow

import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.DrawableShadow
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.AbstractLayer
import earth.worldwind.render.RenderContext
import earth.worldwind.render.program.SightlineMomentsBlurProgram
import earth.worldwind.render.program.SightlineMomentsProgram
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Adds directional sun-shadow rendering to the scene. When this layer is in the layer list,
 * shapes (3D meshes, polygons, COLLADA / glTF models, etc.) cast shadows onto terrain and
 * onto each other from the same world-space sun direction that drives shape lighting and
 * atmospheric scattering ([RenderContext.lightDirection]).
 *
 * Implementation is **Cascaded Shadow Maps** (CSM, [cascadeCount] cascades by default 3) with
 * **Hamburger 4-moment Moment Shadow Mapping** (Peters & Klein 2015) for soft edges. Cascade
 * splits use a parallel-split (PSSM) scheme blended between uniform and logarithmic by
 * [splitBlend]; the largest cascade's far cap is `max(maxCascadeDistance, cameraAltitude*2)`
 * so it auto-scales from city-scale at low altitude to horizon-scale at globe view.
 *
 * Receivers (terrain, lit shapes, unlit shapes) sample the cascade moments textures in their
 * own fragment shaders and modulate their output colour by the resulting occlusion factor.
 * The [ambientShadow] knob controls how dark fully-occluded fragments appear: `0.0` = pure
 * black shadow, `1.0` = no darkening (sky-light only term). Shadow attenuation always applies
 * – the layer doesn't gate on whether a shape's `isLightingEnabled` flag is set.
 *
 * Performance:
 *  - Each cascade adds a depth pass over caster geometry plus a separable Gaussian blur.
 *  - The receiver-side shader cost is ~20 ALU ops + 1 cascade-selecting branch + 5 texture
 *    taps (centred + 4 diagonal) per fragment.
 *  - Memory: `cascadeCount * SHADOW_MAP_SIZE^2 * 16B (RGBA32F) + depth attachment` plus one
 *    blur ping-pong texture of the same size. At the default 3 cascades / 1024 px that's
 *    `3 * 4 + 4 = 16 MB`.
 *
 * Place this layer **after** [earth.worldwind.layer.atmosphere.AtmosphereLayer] in the layer
 * list so the atmosphere has already populated [RenderContext.lightDirection] before the
 * cascade matrices are computed. Layers that render before the shadow layer don't see
 * shadows; this is intentional – the only known case is the atmosphere itself, which has
 * its own ground-darkening from the day/night terminator and shouldn't double-shadow.
 */
open class ShadowLayer : AbstractLayer("Shadow") {
    override var isPickEnabled = false

    /** Number of PSSM cascades. Currently fixed at [ShadowState.DEFAULT_CASCADE_COUNT]. */
    val cascadeCount: Int get() = ShadowState.DEFAULT_CASCADE_COUNT

    /**
     * PSSM split blend factor. `0.0` = pure uniform splits, `1.0` = pure logarithmic. The
     * default `0.7` biases toward log so close-range cascades stay tight on a globe view.
     */
    var splitBlend: Double = 0.7

    /**
     * Floor for the largest cascade's far cap, in metres. The actual cap each frame is
     * `max(maxCascadeDistance, cameraAltitude * 2.0)` so high-altitude views automatically
     * push the last cascade out to the horizon without per-app tuning. The user-settable
     * value here only matters at low camera altitudes - at globe-scale views the altitude
     * term dominates.
     */
    var maxCascadeDistance: Double = ShadowState.DEFAULT_MAX_CASCADE_DISTANCE

    /**
     * Minimum near distance used when deriving cascade splits. When the camera projection's
     * own near plane is very close (sub-metre), PSSM log-splits collapse to nearly zero
     * extent for the closest cascade and the depth-pass loses precision. Clamping the input
     * near to this floor keeps all cascades workable at typical zoom levels.
     */
    var minNearDistance: Double = 1.0

    /**
     * Floor for the per-cascade light-space near-plane pullback, in metres. The actual
     * pullback each frame is `max(casterPullback, cameraAltitude * 0.1)` so high-altitude
     * views automatically capture orbital-altitude casters without per-app tuning. The
     * user-settable value only matters at low camera altitudes (e.g. a city view with a
     * satellite shape would set this manually).
     *
     * Larger values capture taller casters at the cost of MSM precision: the depth-range
     * window widens, so each `1.0/range` quantum shrinks. Globe-scale views with high-
     * altitude casters currently produce visible shadows only after a small zoom-in - the
     * soft-shadow reconstruction is unstable when one cascade texel covers many polygon-
     * interior fragments at near-identical depths. Tracking separately as a polish item.
     */
    var casterPullback: Double = 5_000.0

    /**
     * Ambient floor for fully-occluded fragments. See [ShadowState.ambientShadow].
     */
    var ambientShadow: Float = ShadowState.DEFAULT_AMBIENT_SHADOW

    /**
     * Shared per-frame state. Reused across frames – the layer mutates the cascade matrices
     * in place every render, then attaches the same instance to [RenderContext.shadowState].
     */
    protected val shadowState = ShadowState()

    // Reusable scratch matrices and vectors avoid per-frame allocation churn on the render
    // thread. Not thread-safe; doRender is called serially per WorldWindow.
    private val viewToWorld = Matrix4()
    private val lightRotation = Matrix4()
    private val cascadeWorldCorners = Array(8) { Vec3() }
    private val scratchVec = Vec3()
    private val rightVec = Vec3()
    private val upVec = Vec3()
    private val forwardVec = Vec3()
    private val upRefVec = Vec3()
    private val splits = DoubleArray(ShadowState.DEFAULT_CASCADE_COUNT + 1)

    // Effective per-frame values, derived in doRender() from the user knobs and camera altitude.
    // Stored as fields so computeCascade() reads the same value used for the cascade splits.
    private var effectiveMaxCascadeDistance: Double = 0.0
    private var effectiveCasterPullback: Double = 0.0

    override fun doRender(rc: RenderContext) {
        if (rc.globe.is2D) return // No shadow rendering on 2D globe
        if (rc.isPickMode) return // Picks bypass shadows entirely

        // Sun-below-horizon early-exit. [RenderContext.lightDirection] points TOWARD the sun
        // (the shadow pipeline negates it later to get the travel direction). Dot it with the
        // camera's local up (= cameraPoint normalised, in ECEF) to recover sin(elevation):
        // `+1` when the sun is overhead, `0` at the horizon, negative when below. The small
        // `< -0.05` margin keeps the test from flickering on the exact terminator and avoids
        // running the shadow pass for the night side of the globe, where receivers fall back
        // to ambient anyway.
        val cp = rc.cameraPoint
        val eyeMagSq = cp.x * cp.x + cp.y * cp.y + cp.z * cp.z
        if (eyeMagSq > 0.0) {
            val invEyeMag = 1.0 / sqrt(eyeMagSq)
            val sinElevation = (rc.lightDirection.x * cp.x +
                rc.lightDirection.y * cp.y + rc.lightDirection.z * cp.z) * invEyeMag
            if (sinElevation < -0.05) return
        }

        // Auto-scale cascade extents with camera altitude. The user-set [maxCascadeDistance]
        // and [casterPullback] act as floors; at planetary-scale views the altitude-derived
        // terms take over, so apps don't need per-tutorial overrides for globe cameras.
        val cameraAltitude = max(0.0, rc.camera.position.altitude)
        effectiveMaxCascadeDistance = max(maxCascadeDistance, cameraAltitude * 2.0)
        effectiveCasterPullback = max(casterPullback, cameraAltitude * 0.1)

        // Sync state knobs to ShadowState so receivers see the current configuration.
        // [useMSM] is finalized at draw time by DrawableShadow once the GL context is available.
        shadowState.ambientShadow = ambientShadow
        shadowState.maxCascadeDistance = effectiveMaxCascadeDistance
        shadowState.frameStamp++
        shadowState.reset()

        // Compute camera near/far from the projection matrix. Standard OpenGL perspective:
        //   m[10] = -(f+n)/(f-n), m[11] = -2fn/(f-n)
        // Solving: n = m[11] / (m[10] - 1); f = m[11] / (m[10] + 1).
        val pm = rc.projection.m
        val cameraNear = max(minNearDistance, pm[11] / (pm[10] - 1))
        val cameraFar = min(effectiveMaxCascadeDistance, pm[11] / (pm[10] + 1))
        if (cameraFar <= cameraNear) return // degenerate projection — skip this frame

        // PSSM split distances. splits[0] = near, splits[N] = far (or maxCascadeDistance).
        // splits[i] for 0 < i < N blends uniform and log per Lloyd / Tadamura.
        splits[0] = cameraNear
        splits[cascadeCount] = cameraFar
        val invN = 1.0 / cascadeCount
        for (i in 1 until cascadeCount) {
            val frac = i * invN
            val uniform = cameraNear + (cameraFar - cameraNear) * frac
            val log = cameraNear * (cameraFar / cameraNear).pow(frac)
            splits[i] = log * splitBlend + uniform * (1.0 - splitBlend)
        }

        // Inverse modelview = world ← view. Camera is orthonormal so inversion is a transpose
        // of the upper 3x3 plus a translation flip — invertOrthonormalMatrix does both.
        viewToWorld.invertOrthonormalMatrix(rc.modelview)

        // Camera frustum tangents at unit depth: y_near = z * tanHalfFovY, x_near = aspect * y_near.
        // From the perspective projection: m[5] = 1/tan(fov_y/2); m[0] = 1/(aspect * tan(fov_y/2)).
        val tanHalfFovY = 1.0 / pm[5]
        val aspect = pm[5] / pm[0]

        // Light-space rotation: forward = -lightDirection (we look in the direction the light
        // travels). Pick an "up" reference orthogonal to the light direction; ECEF +Z (north
        // pole) works except when the light is itself near the pole, in which case +Y is
        // an unambiguous fallback.
        forwardVec.copy(rc.lightDirection).multiply(-1.0).normalize()
        if (abs(forwardVec.z) > 0.99) upRefVec.set(0.0, 1.0, 0.0) else upRefVec.set(0.0, 0.0, 1.0)
        // right = upRef × forward, up = forward × right (recomputed for orthonormality).
        rightVec.copy(upRefVec).cross(forwardVec).normalize()
        upVec.copy(forwardVec).cross(rightVec).normalize()
        // Rotation-only world → light-eye matrix. Translation is added per-cascade so each
        // cascade's near plane lands at light-eye-z = 0 (matches SightlineMomentsProgram's
        // perpDepth = -ep.z * invRange convention without an extra offset uniform).
        lightRotation.set(
            rightVec.x, rightVec.y, rightVec.z, 0.0,
            upVec.x, upVec.y, upVec.z, 0.0,
            -forwardVec.x, -forwardVec.y, -forwardVec.z, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )

        // Per-cascade: compute world-space slice corners → light-eye-space AABB → ortho proj.
        var anyValid = false
        for (i in 0 until cascadeCount) {
            val sliceNear = splits[i]
            val sliceFar = splits[i + 1]
            if (sliceFar <= sliceNear) continue
            if (computeCascade(i, sliceNear, sliceFar, tanHalfFovY, aspect)) anyValid = true
        }
        if (!anyValid) return // sun below horizon or all cascades degenerate — no shadows this frame

        // Publish state and enqueue the depth-pass drawable. DrawableShadow runs early in the
        // draw queue (BACKGROUND group) so the cascade textures are populated before any
        // receivers sample them.
        rc.shadowState = shadowState

        val pool = rc.getDrawablePool(DrawableShadow.KEY)
        val drawable = DrawableShadow.obtain(pool)
        drawable.momentsProgram = rc.getShaderProgram(SightlineMomentsProgram.KEY) { SightlineMomentsProgram() }
        drawable.momentsBlurProgram = rc.getShaderProgram(SightlineMomentsBlurProgram.KEY) { SightlineMomentsBlurProgram() }
        rc.offerBackgroundDrawable(drawable)
    }

    /**
     * Computes one cascade's light-space matrices. Returns `true` when the cascade has a
     * non-degenerate footprint (the typical case); `false` when the slice projects to a
     * zero-area / zero-depth box (e.g. all corners coplanar with the light direction —
     * extremely flat sun angles).
     */
    private fun computeCascade(
        cascadeIndex: Int,
        sliceNear: Double,
        sliceFar: Double,
        tanHalfFovY: Double,
        aspect: Double,
    ): Boolean {
        val cascade = shadowState.cascades[cascadeIndex]

        // Build 8 view-space corners of the slice. View space puts -Z forward, so a depth
        // distance `d` corresponds to view-z = -d. y_extent = d * tanHalfFovY, x = aspect * y.
        val nearY = sliceNear * tanHalfFovY
        val nearX = nearY * aspect
        val farY = sliceFar * tanHalfFovY
        val farX = farY * aspect
        // Order: 0..3 are near-plane corners, 4..7 are far-plane corners
        // (-x,-y), (+x,-y), (+x,+y), (-x,+y) on each plane.
        cascadeWorldCorners[0].set(-nearX, -nearY, -sliceNear)
        cascadeWorldCorners[1].set(+nearX, -nearY, -sliceNear)
        cascadeWorldCorners[2].set(+nearX, +nearY, -sliceNear)
        cascadeWorldCorners[3].set(-nearX, +nearY, -sliceNear)
        cascadeWorldCorners[4].set(-farX, -farY, -sliceFar)
        cascadeWorldCorners[5].set(+farX, -farY, -sliceFar)
        cascadeWorldCorners[6].set(+farX, +farY, -sliceFar)
        cascadeWorldCorners[7].set(-farX, +farY, -sliceFar)
        // View → world.
        for (corner in cascadeWorldCorners) corner.multiplyByMatrix(viewToWorld)

        // Stable cascade footprint: bounding sphere of the 8 corners in light-eye-rotated
        // space (no translation yet). Tight AABB of the slice corners is rotation-dependent —
        // a pure camera rotation stretches/squeezes the AABB extent and changes the per-texel
        // metre quantum, producing a 1-pixel shimmer along shadow edges. The bounding sphere
        // is rotation-invariant, so the cascade footprint stays the same size as the camera
        // orbits; texel-snapping the sphere centre then pins each shadow-map cell to a fixed
        // world position. Cost is ~1.4× lower texel density than the tight AABB.
        var cx = 0.0
        var cy = 0.0
        var zMin = Double.POSITIVE_INFINITY
        var zMax = Double.NEGATIVE_INFINITY
        for (corner in cascadeWorldCorners) {
            scratchVec.copy(corner).multiplyByMatrix(lightRotation)
            cx += scratchVec.x
            cy += scratchVec.y
            if (scratchVec.z < zMin) zMin = scratchVec.z
            if (scratchVec.z > zMax) zMax = scratchVec.z
        }
        cx /= cascadeWorldCorners.size.toDouble()
        cy /= cascadeWorldCorners.size.toDouble()

        // Max squared distance from centroid (in xy plane) gives the sphere radius. Z extent
        // is handled separately — depth shimmer doesn't affect shadow edges, only x/y does.
        var maxR2 = 0.0
        for (corner in cascadeWorldCorners) {
            scratchVec.copy(corner).multiplyByMatrix(lightRotation)
            val dx = scratchVec.x - cx
            val dy = scratchVec.y - cy
            val r2 = dx * dx + dy * dy
            if (r2 > maxR2) maxR2 = r2
        }
        val sphereRadius = sqrt(maxR2)

        // Pull the near plane (closest-to-light = highest eye_z) further toward the sun so
        // tall casters between the sun and the slice are captured. The far plane stays at
        // the slice's deepest corner.
        val zNearLight = zMax + effectiveCasterPullback
        val zFarLight = zMin
        val depthRange = zNearLight - zFarLight
        if (depthRange <= 0.0 || sphereRadius <= 0.0) return false

        // Texel-grid snap on the sphere centre. Each shadow texel covers `2*sphereRadius /
        // SHADOW_MAP_SIZE` light-eye metres; snapping the centre to integer multiples pins
        // the discrete cells to fixed world positions across frames.
        val mapSize = DrawContext.SHADOW_MAP_SIZE.toDouble()
        val texelSize = 2.0 * sphereRadius / mapSize
        cx = floor(cx / texelSize) * texelSize
        cy = floor(cy / texelSize) * texelSize

        val xMin = cx - sphereRadius
        val xMax = cx + sphereRadius
        val yMin = cy - sphereRadius
        val yMax = cy + sphereRadius

        // Translate light-eye space so eye_z = 0 lies at the near plane. Equivalent to
        //   lightView = translate(0, 0, -zNearLight) * lightRotation
        // Build it directly to avoid an extra matrix multiply.
        cascade.lightView.set(
            lightRotation.m[0], lightRotation.m[1], lightRotation.m[2], 0.0,
            lightRotation.m[4], lightRotation.m[5], lightRotation.m[6], 0.0,
            lightRotation.m[8], lightRotation.m[9], lightRotation.m[10], -zNearLight,
            0.0, 0.0, 0.0, 1.0,
        )

        // Orthographic projection: ortho(xMin, xMax, yMin, yMax, near=0, far=depthRange).
        // After translation, eye_z ∈ [-depthRange, 0] in this cascade. Standard OpenGL ortho:
        //   [2/(r-l)  0       0           -(r+l)/(r-l)]
        //   [0        2/(t-b) 0           -(t+b)/(t-b)]
        //   [0        0       -2/(f-n)    -(f+n)/(f-n)]
        //   [0        0       0           1]
        // With near=0, far=depthRange: m[10] = -2/depthRange, m[11] = -1.
        val invX = 2.0 / (xMax - xMin)
        val invY = 2.0 / (yMax - yMin)
        val invZ = 2.0 / depthRange
        cascade.lightProjection.set(
            invX, 0.0, 0.0, -(xMax + xMin) / (xMax - xMin),
            0.0, invY, 0.0, -(yMax + yMin) / (yMax - yMin),
            0.0, 0.0, -invZ, -1.0,
            0.0, 0.0, 0.0, 1.0,
        )

        // Composed lightProjection * lightView for the receivers.
        cascade.lightProjectionView.copy(cascade.lightProjection).multiplyByMatrix(cascade.lightView)

        cascade.range = depthRange
        cascade.nearViewDepth = sliceNear
        cascade.farViewDepth = sliceFar
        // Cache the snapped xy AABB for per-cascade caster culling. Same coordinate frame as
        // [CascadeState.lightView]'s rotation: x/y are light-eye-rotated, z is the slice's
        // post-translation depth window — handled separately inside [intersectsSphere].
        cascade.boxXMin = xMin
        cascade.boxXMax = xMax
        cascade.boxYMin = yMin
        cascade.boxYMax = yMax
        cascade.isValid = true
        return true
    }
}
