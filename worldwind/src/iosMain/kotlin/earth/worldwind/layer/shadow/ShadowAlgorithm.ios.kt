package earth.worldwind.layer.shadow

// Mac iPad Simulator's Metal-backed GLES3 reorders the Cholesky catastrophic-cancellation
// subtraction; sightline light-leaks at the strict 3e-5, and 3e-3 still showed noise.
actual val defaultSightlineMomentBias: Float = 3e-2f
