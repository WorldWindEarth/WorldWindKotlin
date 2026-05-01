package earth.worldwind.layer.shadow

/**
 * Receiver-side soft-shadow algorithm. Both run on the same `RGBA32F` cascade depth pass;
 * they differ in how the receiver computes occlusion.
 */
enum class ShadowAlgorithm {
    /** 9-tap rotated PCF. Portable, GIS industry standard. Default. */
    PCF,

    /**
     * Hamburger 4-moment Cholesky (Peters & Klein 2015). Smoother analytic penumbra, but
     * the reconstruction's `D22 = b.y - b.x*b.x` is a catastrophic-cancellation subtraction
     * that some mobile shader compilers (Adreno) fold into noise. Choose PCF on those.
     */
    MSM,
}
