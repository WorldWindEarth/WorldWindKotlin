package earth.worldwind.layer.ogc3d.content

/**
 * Codec-agnostic envelope for a parsed Gaussian-splat payload — what every
 * [GaussianLoader] implementation returns regardless of the wire format it consumes
 * (Niantic SPZ, SuperSplat .splat, Cesium's draft EXT_3DTILES_gaussian_splat glTF wrapper,
 * or any future codec).
 *
 * All arrays are flat tile-local. Sizes are bounded by [splatCount]:
 *  - [centers]: `splatCount * 3` Float32 (vec3 splat positions)
 *  - [scales]: `splatCount * 3` Float32 (vec3 anisotropic scales; one std-dev per axis)
 *  - [rotations]: `splatCount * 4` Float32 (quaternion w, x, y, z per splat)
 *  - [opacities]: `splatCount` Float32 (one alpha per splat, typically in [0, 1])
 *  - [rgba]: `splatCount * 4` UInt8 — direct RGBA colors. Mutually exclusive with
 *    [sphericalHarmonics]; codecs supply exactly one of the two color paths. Null when
 *    [sphericalHarmonics] is the colour source.
 *  - [sphericalHarmonics]: `splatCount * 3 * (sphericalHarmonicsBands + 1)^2` Float32 —
 *    SH coefficients for view-dependent color, packed in interleaved RGB-per-coefficient
 *    layout. Null when [rgba] is the colour source.
 *  - [sphericalHarmonicsBands]: SH band count. 0 = degree-0 only (DC term), 1 = degree-1
 *    (4 coefficients per channel), 2 = degree-2 (9), 3 = degree-3 (16). Renderers that
 *    don't implement higher bands can stop at their supported level.
 *  - [rtcCenter]: Optional tile-local origin offset; renderer applies before
 *    tile-to-world (parallel to b3dm / i3dm / pnts RTC_CENTER semantics).
 *
 * Codec-supplied. Construction is via the codec-side loader; the engine does not validate
 * field consistency beyond what's needed to enqueue a GPU upload.
 */
class GaussianPayload(
    val splatCount: Int,
    val centers: FloatArray,
    val scales: FloatArray,
    val rotations: FloatArray,
    val opacities: FloatArray,
    val rgba: ByteArray? = null,
    val sphericalHarmonics: FloatArray? = null,
    val sphericalHarmonicsBands: Int = 0,
    val rtcCenter: DoubleArray? = null,
)
