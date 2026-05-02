package earth.worldwind.layer.shadow

// Adreno-class compilers reorder the Cholesky's catastrophic-cancellation subtractions
// and produce noise at `3e-5`. The larger bias here mixes the moments far enough toward
// the uniform-distribution sentinel that the noise washes out, at the cost of a visibly
// translucent / "glass-bottle" shadow. Apps that target only IEEE-FP-strict Android GPUs
// can override [ShadowLayer] knobs to recover sharp MSM if their device tolerates it.
actual val defaultMsmMomentBias: Float = 3e-2f
