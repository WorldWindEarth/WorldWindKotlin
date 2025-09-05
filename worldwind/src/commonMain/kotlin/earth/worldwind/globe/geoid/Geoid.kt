package earth.worldwind.globe.geoid

import earth.worldwind.geom.Angle

/**
 * Representation of the globe Gravitational Model
 */
interface Geoid {
    /**
     * This Gravitational Model display name.
     */
    val displayName: String

    /**
     * Calculates Gravitational Model offset at specified location
     *
     * @param latitude Input latitude
     * @param longitude Input longitude
     * @return Gravitational Model offset
     */
    fun getOffset(latitude: Angle, longitude: Angle): Float
}