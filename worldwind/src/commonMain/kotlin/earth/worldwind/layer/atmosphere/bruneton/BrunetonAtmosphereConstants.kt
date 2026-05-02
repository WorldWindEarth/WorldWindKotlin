/*
 * Adapted from ebruneton/precomputed_atmospheric_scattering, BSD-3-Clause,
 * Copyright (c) 2017 Eric Bruneton.
 *
 * Physical constants for Earth's atmosphere, in the parameterization used by Bruneton's
 * "Precomputed Atmospheric Scattering" model. Values match the reference implementation's
 * `constants.h` (the "Earth (clear sky)" case): radii in metres, scattering / extinction
 * coefficients in m^-1 at the chosen reference wavelengths (680 / 550 / 440 nm), scale
 * heights in metres.
 *
 * Kept as plain `Double`s so the precomputation driver can pass them into shader uniforms
 * without conversion noise. Wavelength-domain conversions to spectrum-resampled coefficients
 * are deliberately *not* done here — the LUTs are precomputed with these direct RGB values,
 * matching the simpler reference path (`use_constant_solar_spectrum=true`,
 * `use_ozone=true`, three-channel evaluation rather than 40-channel spectral sums).
 */
package earth.worldwind.layer.atmosphere.bruneton

internal object BrunetonAtmosphereConstants {

    // --- Geometry (metres) ---------------------------------------------------

    /**
     * Bottom of the atmosphere: planetary mean radius. WorldWind's WGS84 equatorial radius
     * (6 378 137 m) instead of the reference's tidy 6 360 km, so runtime ECEF camera /
     * surface positions feed the LUT lookups directly without rescaling. The 0.28 % shift
     * is physically irrelevant — atmosphere parameters (Rayleigh / Mie / ozone scale heights
     * and per-channel scattering coefficients) are altitude-relative, not radius-relative —
     * and the reference's choice was just a round number for a generic "Earth-like" planet
     * rather than the actual WGS84 figure.
     */
    const val BOTTOM_RADIUS = 6_378_137.0

    /** Top of the atmosphere (where transmittance is treated as 1.0). 60 km above bottom. */
    const val TOP_RADIUS = 6_438_137.0

    /** Atmosphere thickness in metres. Convenience derived value. */
    const val ATMOSPHERE_THICKNESS = TOP_RADIUS - BOTTOM_RADIUS

    // --- Rayleigh scattering -------------------------------------------------
    // Wavelengths assumed: λ_R = 680 nm, λ_G = 550 nm, λ_B = 440 nm.
    // Rayleigh density profile is a single exponential layer: density(h) = exp(-h / H_R).

    const val RAYLEIGH_SCALE_HEIGHT = 8_000.0
    const val RAYLEIGH_SCATTERING_R = 5.802e-6
    const val RAYLEIGH_SCATTERING_G = 13.558e-6
    const val RAYLEIGH_SCATTERING_B = 33.1e-6

    // --- Mie scattering ------------------------------------------------------
    // Mie density profile is a single exponential layer with H_M = 1.2 km.
    // Reference uses scattering = 3.996e-6, extinction = 4.4e-6 across all channels.

    const val MIE_SCALE_HEIGHT = 1_200.0
    const val MIE_SCATTERING = 3.996e-6
    const val MIE_EXTINCTION = 4.4e-6
    /** Asymmetry parameter for the Cornette-Shanks phase function. */
    const val MIE_PHASE_FUNCTION_G = 0.8

    // --- Ozone absorption ----------------------------------------------------
    // Ozone density profile is a tent function peaking at altitude 25 km, full width 30 km
    // (i.e. layer 0 ramps up from 0 km → 25 km, layer 1 ramps down from 25 km → 40 km).
    // Layer-0:  density(h) = (h / 15 km)             for 0   ≤ h < 25 km
    // Layer-1:  density(h) = (40 km - h) / 15 km     for 25  ≤ h < 40 km
    // Encoded as (width, exp_term, exp_scale, linear_term, constant_term) per layer
    // in the reference's DensityProfileLayer struct; we re-derive these inline in
    // [BrunetonShaders] so callers don't have to hand-marshal the layer arrays.

    const val OZONE_BOTTOM_LAYER_WIDTH = 25_000.0
    const val OZONE_LAYER_TOP_HEIGHT = 40_000.0

    /** Per-channel ozone absorption coefficients (m^-1). Reference: peak ozone density × 0.65/1.881/0.085 µm⁻¹. */
    const val OZONE_ABSORPTION_R = 0.650e-6
    const val OZONE_ABSORPTION_G = 1.881e-6
    const val OZONE_ABSORPTION_B = 0.085e-6

    // --- Sun -----------------------------------------------------------------

    /** Sun's angular radius as seen from Earth's surface, radians. ≈ 0.00935 / 2. */
    const val SUN_ANGULAR_RADIUS = 0.004675

    /** Solar irradiance at the top of the atmosphere (W·m⁻²) per channel. */
    const val SOLAR_IRRADIANCE_R = 1.474
    const val SOLAR_IRRADIANCE_G = 1.8504
    const val SOLAR_IRRADIANCE_B = 1.91198

    // --- Surface reflectance & misc -----------------------------------------

    /**
     * Average ground albedo used during multiple-scattering precomputation. The runtime
     * ground shader scales by the actual surface texture; this is just the integration
     * prior on indirect bounces.
     */
    const val GROUND_ALBEDO = 0.1

    /**
     * Maximum sun-zenith cosine value below which transmittance lookups fall back to a
     * fully-attenuated result. Matches the reference's `mu_s_min = cos(102°) ≈ -0.207912`,
     * which corresponds to the sun being just below the geometric horizon at sea level.
     */
    const val MU_S_MIN = -0.20791169081775931 // cos(102°)

    // --- LUT dimensions ------------------------------------------------------
    // Match the reference repo defaults. These determine GPU memory:
    //   transmittance ≈ 256·64·8B  ≈ 128 KB    (RGBA16F, 4 channels × 2B; we use RGB16F = 6B but
    //                                          most drivers pad to 8B/texel — figure with that)
    //   irradiance    ≈ 64·16·8B   ≈ 8 KB
    //   scattering    ≈ 256·128·32·8B ≈ 8 MB
    // Total LUT footprint: ~8 MB.

    const val TRANSMITTANCE_TEXTURE_WIDTH = 256
    const val TRANSMITTANCE_TEXTURE_HEIGHT = 64

    const val IRRADIANCE_TEXTURE_WIDTH = 64
    const val IRRADIANCE_TEXTURE_HEIGHT = 16

    const val SCATTERING_TEXTURE_R_SIZE = 32
    const val SCATTERING_TEXTURE_MU_SIZE = 128
    const val SCATTERING_TEXTURE_MU_S_SIZE = 32
    const val SCATTERING_TEXTURE_NU_SIZE = 8

    /** Width = mu_s × nu (compressed 4D → 3D). */
    const val SCATTERING_TEXTURE_WIDTH = SCATTERING_TEXTURE_MU_S_SIZE * SCATTERING_TEXTURE_NU_SIZE
    /** Height = mu. */
    const val SCATTERING_TEXTURE_HEIGHT = SCATTERING_TEXTURE_MU_SIZE
    /** Depth = r. */
    const val SCATTERING_TEXTURE_DEPTH = SCATTERING_TEXTURE_R_SIZE

    /**
     * Number of multiple-scattering orders to integrate during precomputation. The
     * reference iterates 4 orders; visually the difference between 2 and 4 is small but
     * noticeable on the bright twilight rim, so we match the reference.
     */
    const val NUM_SCATTERING_ORDERS = 4
}
