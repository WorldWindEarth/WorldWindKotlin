package earth.worldwind.globe.projection

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.degrees

/**
 * Implements a Transverse Mercator projection for a specified UTM zone.
 *
 * @param zone The UTM zone of this projection, a value between 1 and 60, inclusive.
 */
open class UtmProjection(zone: Int = DEFAULT_ZONE) : TransverseMercatorProjection(centralMeridianForZone(zone), ZERO) {
    override val scale = 0.9996

    /**
     * Indicates the UTM zone of this projection.
     */
    var zone: Int = zone
        set(value) {
            require(value in 1..60) { "Invalid UTM zone $value" }
            field = value
            centralMeridian = centralMeridianForZone(value)
        }

    companion object {
        private const val DEFAULT_ZONE = 1

        fun centralMeridianForZone(zone: Int): Angle {
            require(zone in 1..60) { "Invalid UTM zone $zone" }
            return ((3 + (zone - 1) * 6) - if (zone > 30) 360 else 0).toDouble().degrees
        }
    }
}
