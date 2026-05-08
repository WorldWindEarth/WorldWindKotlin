package earth.worldwind.layer.shadow

// Adreno reorders the Cholesky catastrophic-cancellation; cascade shadow's wide depth range
// needs 3e-2 to mask the noise. Sightline's tighter frustum reconstructs cleanly at 3e-5;
// 3e-2 there over-mixes and bleeds light past the caster.
actual val defaultMsmMomentBias: Float = 3e-2f
actual val defaultSightlineMomentBias: Float = 3e-5f
