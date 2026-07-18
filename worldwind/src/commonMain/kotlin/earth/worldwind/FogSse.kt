package earth.worldwind

import earth.worldwind.geom.Angle
import kotlin.math.cos
import kotlin.math.pow

/**
 * Far-field tile detail degradation driven by an exponential distance-fog model: tile selection
 * subtracts a bounded fog term from each tile's projected screen-space error, and tiles past full
 * fog saturation are culled from assembly (see [earth.worldwind.util.Tile.mustSubdivide] and
 * [earth.worldwind.util.Tile.isFullyFogged]). Density falls off with camera altitude, so
 * degradation concentrates near the horizon at low altitudes and vanishes at high ones.
 * Per-engine settings, snapshotted into the render context once per frame.
 */
class FogSse {
    /**
     * Master switch for fog-based detail degradation and the full-fog assembly cull.
     */
    var isEnabled = true

    /**
     * Base fog density; larger values move degradation closer to the camera.
     */
    var density = 6.0e-4

    /**
     * Scales base density by camera altitude before the height falloff is applied.
     */
    var heightScalar = 1.0e-3

    /**
     * Exponent of the camera-altitude falloff; smaller values thin the fog more gradually with altitude.
     */
    var heightFalloff = 0.59

    /**
     * Camera altitude in meters above which fog degradation is disabled entirely.
     */
    var maxHeight = 800_000.0

    /**
     * Error demotion at full fog, in units of the subdivision threshold; 1.0 ~ one level coarser.
     */
    var screenSpaceErrorFactor = 4.0

    /**
     * Fog density for the frame's camera [height] and [tilt]; 0 when disabled or above [maxHeight].
     */
    fun frameDensity(height: Double, tilt: Angle): Double {
        if (!isEnabled || height >= maxHeight) return 0.0
        var d = density * heightScalar * (height / maxHeight).coerceAtLeast(1.0e-4).pow(-heightFalloff)
        // Fade fog out as the camera pitches above the horizon toward the sky
        d *= 1.0 + cos(tilt.inRadians).coerceIn(-1.0, 0.0)
        // Taper to zero over the top fifth of maxHeight so crossing it can't pop tile detail
        d *= (5.0 * (1.0 - height / maxHeight)).coerceAtMost(1.0)
        return d
    }

    companion object {
        /**
         * Density-distance product treated as full fog saturation by the assembly cull; ~2x past
         * visual saturation, beyond the geometric horizon at default density.
         */
        const val FULL_FOG_DENSITY_DISTANCE = 6.0
    }
}
