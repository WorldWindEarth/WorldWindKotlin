package earth.worldwind.layer.shadow

/**
 * Receiver-side soft-shadow algorithm. Both run on the same `RGBA32F` cascade depth pass;
 * they differ in how the receiver computes occlusion.
 */
enum class ShadowAlgorithm {
    /** 9-tap rotated PCF. Portable, GIS industry standard. */
    PCF,

    /**
     * Hamburger 4-moment Cholesky (Peters & Klein 2015). Smoother analytic penumbra, but
     * the reconstruction's `D22 = b.y - b.x*b.x` is a catastrophic-cancellation subtraction
     * that some mobile shader compilers (Adreno) fold into noise. Choose PCF on those.
     */
    MSM,
}

/**
 * Platform-default shadow algorithm. JVM and Android default to PCF (Adreno can't do MSM
 * cleanly; desktop GL is fast enough either way). JS defaults to MSM because WebGL2 / ANGLE
 * handles MSM's 1-tap analytic reconstruction far faster than PCF's 9 manual rotated taps,
 * and ANGLE's IEEE-FP-strict translation produces clean Cholesky output.
 */
expect val defaultShadowAlgorithm: ShadowAlgorithm

/**
 * Platform-default per-cascade Gaussian-blur tap spacing applied to the moments texture
 * before the receiver pass. Non-zero values widen MSM's analytic penumbra; PCF muddies
 * the receiver-vs-caster compare if the moments are pre-blurred, so PCF defaults skip it.
 * Index 0 is the closest cascade.
 */
expect val defaultMomentsBlurTexelSpacing: FloatArray
