package earth.worldwind.layer.shadow

// Sightline's tight frustum reconstructs cleanly at the IEEE-strict 3e-5 even on Adreno;
// larger values over-mix and bleed light past the caster.
actual val defaultSightlineMomentBias: Float = 3e-5f
