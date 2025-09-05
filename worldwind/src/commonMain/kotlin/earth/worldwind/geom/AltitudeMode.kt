package earth.worldwind.geom

/**
 * Altitude mode indicates how WorldWind interprets a position's altitude component.
 * Accepted values are [ABSOLUTE], [ABOVE_SEA_LEVEL], [CLAMP_TO_GROUND] and [RELATIVE_TO_GROUND].
 */
enum class AltitudeMode {
    /**
     * Indicating an altitude relative to the globe's ellipsoid (HAE). Ignores the elevation of
     * the terrain directly beneath the position's latitude and longitude.
     */
    ABSOLUTE,
    /**
     * Indicating an altitude relative to sea level (ASL), regardless of the actual elevation of
     * the terrain beneath the position's latitude and longitude.
     */
    ABOVE_SEA_LEVEL,
    /**
     * Indicating an altitude on the terrain. Ignores a position's specified altitude, and
     * always places the position on the terrain.
     */
    CLAMP_TO_GROUND,
    /**
     * Indicating an altitude relative to ground level (AGL). The altitude indicates height above
     * the terrain directly beneath the position's latitude and longitude.
     */
    RELATIVE_TO_GROUND;
}