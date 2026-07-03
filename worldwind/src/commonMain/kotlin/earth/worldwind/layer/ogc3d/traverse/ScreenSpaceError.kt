package earth.worldwind.layer.ogc3d.traverse

import earth.worldwind.render.RenderContext
import kotlin.math.tan

/**
 * Screen-space-error computation per the 3D Tiles 1.0 specification. SSE is the projected
 * size in pixels of a tile's `geometricError` meters at `cameraDistance`. A traversal step
 * descends when SSE > target; otherwise it renders the current LOD.
 *
 * Formula:
 *   sse = (geometricError * viewportHeight) / (cameraDistance * 2 * tan(fov / 2))
 *
 * Pulled into its own object both so traversal code reads more linearly and so it can be
 * unit-tested in commonTest without a full [RenderContext].
 */
object ScreenSpaceError {
    /**
     * @param geometricError tile's reported geometric error in meters
     * @param cameraDistance distance from the camera eye to the nearest point on the tile's
     *   bounding volume, in meters
     * @param viewportHeightPixels viewport height in pixels
     * @param verticalFovRadians vertical field of view in radians
     * @return projected pixel error. Returns [Double.POSITIVE_INFINITY] when `cameraDistance`
     *   is zero (camera inside the tile -> always refine).
     */
    fun compute(
        geometricError: Double,
        cameraDistance: Double,
        viewportHeightPixels: Double,
        verticalFovRadians: Double,
    ): Double {
        if (cameraDistance <= 0.0) return Double.POSITIVE_INFINITY
        val tanHalfFov = tan(verticalFovRadians * 0.5)
        if (tanHalfFov <= 0.0) return 0.0
        return (geometricError * viewportHeightPixels) / (cameraDistance * 2.0 * tanHalfFov)
    }

    /**
     * Convenience overload reading viewport + FOV directly from [rc].
     */
    fun compute(rc: RenderContext, geometricError: Double, cameraDistance: Double): Double = compute(
        geometricError = geometricError,
        cameraDistance = cameraDistance,
        viewportHeightPixels = rc.viewport.height.toDouble(),
        verticalFovRadians = rc.camera.fieldOfView.inRadians,
    )

    /**
     * Focal length in pixels — `viewportHeight / (2·tan(fov / 2))` — the projection scale that keeps a
     * world-space point/splat radius mapping to a constant on-screen size with distance. Shares the same
     * `2·tan(fov / 2)` denominator as [compute]. Falls back to `1` for a degenerate (non-finite or
     * non-positive) viewport / FOV so callers never divide by zero.
     */
    fun focalLengthPixels(rc: RenderContext): Float {
        val focal = (rc.viewport.height / (2.0 * tan(rc.camera.fieldOfView.inRadians * 0.5))).toFloat()
        return if (focal.isFinite() && focal > 0f) focal else 1f
    }
}
