package earth.worldwind.layer.shadow

/**
 * Platform-default MSM moment bias for the sightline pipeline (directional + omni cube-map).
 * The Cholesky reconstruction mixes sampled moments toward a uniform-distribution sentinel by
 * this fraction; most platforms reconstruct cleanly at IEEE-strict `3e-5`, while iOS Mac Sim's
 * Metal-backed GLES3 reorders the catastrophic-cancellation subtraction and needs `3e-2`.
 *
 * The cascaded sun-shadow pipeline no longer uses moment shadow mapping — it renders plain
 * depth textures resolved with a bilinear-weighted PCF (see [ShadowReceiverGlsl]) — so this
 * is the only platform-templated moments constant left.
 */
expect val defaultSightlineMomentBias: Float
